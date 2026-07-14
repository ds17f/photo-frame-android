package com.photoframe.data.source.immich

import com.photoframe.data.SourceType
import com.photoframe.data.source.ImmichConfig
import com.photoframe.data.source.PhotoSource
import com.photoframe.data.source.RemotePhoto
import com.photoframe.data.source.Sink
import com.photoframe.data.source.SourceHealth
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Immich-backed [PhotoSource] (DESIGN §5.1). Translates the source's combinable selection
 * (albums / people / favorites / whole-library-or-window) into Immich API calls, unions and
 * dedupes the results by asset id, and streams preview thumbnails on [fetch]. Network failures
 * surface as [SourceHealth]; the sync engine treats those as "leave the cache alone" (§6).
 */
class ImmichSource(
    override val id: String,
    private val config: ImmichConfig,
    apiKey: String,
    private val api: ImmichApi = ImmichClient.create(config.baseUrl, apiKey),
) : PhotoSource {

    override val type: SourceType get() = SourceType.IMMICH

    override suspend fun healthCheck(): SourceHealth {
        return try {
            api.albums() // validates reachability + key without needing the user.read permission
            SourceHealth.OK
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            SourceHealth.degraded(ImmichClient.errorMessage(e))
        } catch (e: Exception) {
            SourceHealth.offline(e.message ?: "Could not reach Immich server")
        }
    }

    override suspend fun listPhotos(): List<RemotePhoto> {
        val selection = config.selection
        if (selection.isEmpty) return emptyList()

        // Union across facets, deduped by asset id (an asset can match several facets).
        val byId = LinkedHashMap<String, RemotePhoto>()

        // Albums — v3 serves album assets via metadata search filtered by album id (the album
        // detail endpoint no longer embeds assets). One search per album keeps the union clean.
        for (albumId in selection.albumIds) {
            searchAll(ImmichSearchRequestDto(albumIds = listOf(albumId)))
                .forEach { byId.putIfAbsent(it.id, it.toRemotePhoto()) }
        }

        // People
        if (selection.personIds.isNotEmpty()) {
            searchAll(ImmichSearchRequestDto(personIds = selection.personIds))
                .forEach { byId.putIfAbsent(it.id, it.toRemotePhoto()) }
        }

        // Favorites
        if (selection.favoritesOnly) {
            searchAll(ImmichSearchRequestDto(isFavorite = true))
                .forEach { byId.putIfAbsent(it.id, it.toRemotePhoto()) }
        }

        // Whole library / rolling window
        if (selection.wholeLibrary) {
            val takenAfter = selection.rollingWindowDays
                ?.takeIf { it > 0 }
                ?.let { Instant.now().minusSeconds(it.toLong() * 86_400).toString() }
            searchAll(ImmichSearchRequestDto(takenAfter = takenAfter))
                .forEach { byId.putIfAbsent(it.id, it.toRemotePhoto()) }
        }

        return byId.values.toList()
    }

    override suspend fun fetch(photo: RemotePhoto, sink: Sink) {
        val response = api.thumbnail(photo.remoteId, size = PREVIEW_SIZE)
        if (!response.isSuccessful) {
            error("thumbnail ${photo.remoteId} → HTTP ${response.code()}")
        }
        val body = response.body() ?: error("thumbnail ${photo.remoteId} → empty body")
        body.byteStream().use { stream ->
            sink.write(stream, body.contentType()?.toString())
        }
    }

    override val canDelete: Boolean get() = true

    /** Move the asset to the Immich trash (recoverable). Throws with a clear message on failure. */
    override suspend fun deleteRemote(remoteId: String) {
        val response = api.deleteAssets(ImmichDeleteDto(ids = listOf(remoteId), force = false))
        if (!response.isSuccessful) {
            val hint = if (response.code() == 403) " — API key needs the asset.delete permission" else ""
            error("Delete failed (HTTP ${response.code()})$hint")
        }
    }

    override val canFavorite: Boolean get() = true

    /** Mark the asset as a favorite in Immich. Throws with a clear message on failure. */
    override suspend fun favoriteRemote(remoteId: String) {
        val response = api.updateAssets(ImmichUpdateDto(ids = listOf(remoteId), isFavorite = true))
        if (!response.isSuccessful) {
            val hint = if (response.code() == 403) " — API key needs the asset.update permission" else ""
            error("Favorite failed (HTTP ${response.code()})$hint")
        }
    }

    /**
     * Page through metadata search (newest-first), filtering to displayable images. Bounded by
     * [MAX_LIST_ITEMS] so a huge whole-library selection (the owner's is ~60k) doesn't hold the
     * entire library in memory — we keep the newest, which is all the bounded cache would retain
     * anyway. Only the download budget in [SourceSyncer] decides what actually lands on disk.
     */
    private suspend fun searchAll(base: ImmichSearchRequestDto): List<ImmichAssetDto> {
        val out = ArrayList<ImmichAssetDto>()
        var page = 1
        while (out.size < MAX_LIST_ITEMS) {
            val response = api.searchMetadata(base.copy(page = page, size = PAGE_SIZE))
            val items = response.assets.items
            out += items.filter { it.isDisplayable }
            val next = response.assets.nextPage?.toIntOrNull()
            if (next == null || items.isEmpty() || next <= page) break
            page = next
        }
        return out
    }

    private fun ImmichAssetDto.toRemotePhoto(): RemotePhoto = RemotePhoto(
        remoteId = id,
        takenAt = parseImmichTime(exifInfo?.dateTimeOriginal ?: localDateTime ?: fileCreatedAt),
        // Prefer v3's oriented top-level dims; fall back to raw exif dims. The syncer still
        // decodes the downloaded preview for the definitive display size (portrait pairing).
        width = width ?: exifInfo?.exifImageWidth,
        height = height ?: exifInfo?.exifImageHeight,
        locationLabel = locationLabelOf(exifInfo),
        contentHash = checksum,
    )

    private fun locationLabelOf(exif: ImmichExifDto?): String? {
        if (exif == null) return null
        val city = exif.city?.takeIf { it.isNotBlank() }
        val country = exif.country?.takeIf { it.isNotBlank() }
        return listOfNotNull(city, country).joinToString(", ").takeIf { it.isNotBlank() }
    }

    companion object {
        private const val PREVIEW_SIZE = "preview"
        private const val PAGE_SIZE = 1000

        /**
         * Upper bound on how many photos one facet lists (newest-first). Comfortably above the
         * default cache capacity (~12k previews at 4 GB) so the cache is always full of the
         * newest photos, while capping memory/time for enormous libraries.
         */
        private const val MAX_LIST_ITEMS = 30_000

        /** Tolerant parse across Immich's date shapes (UTC instant, offset, or local). */
        fun parseImmichTime(raw: String?): Instant? {
            if (raw.isNullOrBlank()) return null
            return runCatching { Instant.parse(raw) }
                .recoverCatching { OffsetDateTime.parse(raw).toInstant() }
                .recoverCatching {
                    LocalDateTime.parse(raw).atZone(ZoneId.systemDefault()).toInstant()
                }
                .getOrNull()
        }
    }
}
