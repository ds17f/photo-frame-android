package com.photoframe.data.source.immich

import com.photoframe.data.source.SourceHealth
import kotlinx.coroutines.CancellationException

/**
 * Read-only Immich queries used by the source-setup UI *before* a source is saved (DESIGN §5.1):
 * test the connection and enumerate albums/people so the user can pick. Distinct from
 * [ImmichSource], which is bound to a saved selection and drives sync.
 */
class ImmichCatalog(private val api: ImmichApi) {

    suspend fun testConnection(): SourceHealth {
        return try {
            val res = api.me()
            when {
                res.isSuccessful -> SourceHealth.OK
                res.code() == 401 || res.code() == 403 ->
                    SourceHealth.degraded("API key rejected (HTTP ${res.code()})")
                else -> SourceHealth.degraded("Server returned HTTP ${res.code()}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SourceHealth.offline(e.message ?: "Could not reach server")
        }
    }

    suspend fun albums(): List<ImmichAlbumDto> = api.albums().sortedBy { it.albumName.lowercase() }

    /**
     * Named people for the picker. The people endpoint pages at 500 and lists many *unnamed*
     * faces first, so we page through (bounded) collecting only named people — otherwise a
     * library with thousands of faces would show an empty or arbitrary picker.
     */
    suspend fun people(): List<ImmichPersonDto> {
        val named = ArrayList<ImmichPersonDto>()
        var page = 1
        while (page <= MAX_PEOPLE_PAGES) {
            val response = api.people(page = page, size = PEOPLE_PAGE_SIZE)
            named += response.people.filter { it.name.isNotBlank() }
            if (!response.hasNextPage) break
            page++
        }
        return named.sortedBy { it.name.lowercase() }
    }

    companion object {
        private const val PEOPLE_PAGE_SIZE = 500
        private const val MAX_PEOPLE_PAGES = 20 // safety bound (~10k faces) for huge libraries

        fun create(baseUrl: String, apiKey: String): ImmichCatalog =
            ImmichCatalog(ImmichClient.create(baseUrl, apiKey))

        /** URL of a person's face thumbnail (needs the `x-api-key` header when loaded). */
        fun personThumbnailUrl(baseUrl: String, personId: String): String =
            "${ImmichClient.normalizeBaseUrl(baseUrl)}api/people/$personId/thumbnail"
    }
}
