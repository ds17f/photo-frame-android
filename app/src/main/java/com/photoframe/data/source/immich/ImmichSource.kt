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

    override suspend fun listPhotos(onPage: suspend (List<RemotePhoto>) -> Unit) {
        val selection = config.selection
        if (selection.isEmpty) return

        // Blacklist: assets in the "hidden from frame" album are excluded from the listing. Because
        // sync mirrors the source (MIRROR policy), a photo added to that album stops being emitted
        // and is removed from the index on the next pass — whether hidden from the frame or in Immich.
        val hidden = hiddenAssetIds()

        // Dedupe across facets (an asset can match several) without holding the whole library:
        // only the id set is retained; each page's DTOs are emitted then freed.
        val seen = HashSet<String>()
        suspend fun emit(assets: List<ImmichAssetDto>) {
            val page = assets.asSequence()
                .filter { it.isDisplayable && it.id !in hidden && seen.add(it.id) }
                .map { it.toRemotePhoto() }
                .toList()
            if (page.isNotEmpty()) onPage(page)
        }

        // Albums — v3 serves album assets via metadata search filtered by album id (the album
        // detail endpoint no longer embeds assets). One search per album keeps the union clean.
        for (albumId in selection.albumIds) {
            searchEach(ImmichSearchRequestDto(albumIds = listOf(albumId))) { emit(it) }
        }
        if (selection.personIds.isNotEmpty()) {
            searchEach(ImmichSearchRequestDto(personIds = selection.personIds)) { emit(it) }
        }
        if (selection.favoritesOnly) {
            searchEach(ImmichSearchRequestDto(isFavorite = true)) { emit(it) }
        }
        if (selection.wholeLibrary) {
            val takenAfter = selection.rollingWindowDays
                ?.takeIf { it > 0 }
                ?.let { Instant.now().minusSeconds(it.toLong() * 86_400).toString() }
            searchEach(ImmichSearchRequestDto(takenAfter = takenAfter)) { emit(it) }
        }
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

    override val canHide: Boolean get() = true

    /**
     * Hide the asset from the frame by adding it to the shared "hidden from frame" album (created
     * on first use), which [listPhotos] excludes. Kept at the source; visible to any frame pointing
     * at the same server. Throws with a clear message on failure.
     */
    override suspend fun hideRemote(remoteId: String) {
        val albumId = hiddenAlbumId(create = true) ?: error("Could not create the hidden album")
        val response = api.addAssetsToAlbum(albumId, ImmichBulkIdsDto(ids = listOf(remoteId)))
        if (!response.isSuccessful) {
            val hint = if (response.code() == 403) " — API key needs album.create + albumAsset.create" else ""
            error("Hide failed (HTTP ${response.code()})$hint")
        }
    }

    /** Id of the "hidden from frame" album, or null if it doesn't exist (and [create] is false). */
    private suspend fun hiddenAlbumId(create: Boolean): String? {
        api.albums().firstOrNull { it.albumName == HIDDEN_ALBUM_NAME }?.let { return it.id }
        return if (create) api.createAlbum(ImmichCreateAlbumDto(HIDDEN_ALBUM_NAME)).id else null
    }

    /** Asset ids in the hidden album (empty if the album doesn't exist yet). */
    private suspend fun hiddenAssetIds(): Set<String> {
        val albumId = hiddenAlbumId(create = false) ?: return emptySet()
        val ids = HashSet<String>()
        searchEach(ImmichSearchRequestDto(albumIds = listOf(albumId))) { page ->
            page.forEach { ids.add(it.id) }
        }
        return ids
    }

    /**
     * Page through metadata search (newest-first), filtering to displayable images. Under the
     * streaming-cache model the index must hold a row for **every** remote photo (the owner's is
     * ~60k) so the playlist roams the whole library — this only lists metadata (no downloads), which
     * is cheap, so the bound [MAX_LIST_ITEMS] is set well above any realistic library as a safety
     * valve, not a cache-depth limit.
     */
    private suspend fun searchEach(
        base: ImmichSearchRequestDto,
        onPage: suspend (List<ImmichAssetDto>) -> Unit,
    ) {
        var page = 1
        var listed = 0
        while (listed < MAX_LIST_ITEMS) {
            val response = api.searchMetadata(base.copy(page = page, size = PAGE_SIZE))
            val items = response.assets.items
            if (items.isNotEmpty()) {
                onPage(items)
                listed += items.size
            }
            val next = response.assets.nextPage?.toIntOrNull()
            if (next == null || items.isEmpty() || next <= page) break
            page = next
        }
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
        isFavorite = isFavorite,
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

        /** Album whose members are excluded from the frame (the shared blacklist). A fixed name so
         *  every frame instance pointing at the same server hides the same photos. */
        const val HIDDEN_ALBUM_NAME = "Hidden from frame"

        /**
         * Safety bound on how many photos one facet lists (newest-first). Well above any realistic
         * library so the full collection is indexed; only caps memory/time for a pathological set.
         */
        private const val MAX_LIST_ITEMS = 500_000

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
