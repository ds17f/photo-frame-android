package com.photoframe.data.source.gphotos

import com.photoframe.data.SourceType
import com.photoframe.data.db.PhotoDao
import com.photoframe.data.source.PhotoSource
import com.photoframe.data.source.RemotePhoto
import com.photoframe.data.source.Sink
import com.photoframe.data.source.SourceHealth
import kotlinx.coroutines.CancellationException

/**
 * Google Photos Picker source (DESIGN §5.2). Unlike every other source, its photos are **not**
 * re-derivable from the server on each sync — picker baseURLs expire, so the picked set is acquired
 * eagerly during a [GPhotosPickerSession] and stored locally as the source of truth.
 *
 * Consequences for the sync engine (all deliberate):
 * - [listPhotos] emits the **already-stored** set, so a periodic metadata sync is a no-op diff — it
 *   never adds or (with the picker's non-MIRROR policy) removes anything.
 * - [fetch] is never reached: pick rows are inserted `READY` with a file already on disk, and the
 *   prefetcher only fetches `INDEXED` rows. It throws to make a mistaken call loud.
 * - Picker rows are pinned out of eviction (SyncManager) — an evicted file could never be re-fetched.
 * - [healthCheck] verifies the stored refresh token still works (else "sign in again").
 */
class GPhotosPickerSource(
    override val id: String,
    private val clientId: String,
    private val clientSecret: String,
    private val refreshToken: String,
    private val photoDao: PhotoDao,
) : PhotoSource {

    override val type: SourceType get() = SourceType.GPHOTOS_PICKER

    override suspend fun healthCheck(): SourceHealth = try {
        GPhotosAuth.refresh(clientId, clientSecret, refreshToken)
        SourceHealth.OK
    } catch (e: CancellationException) {
        throw e
    } catch (e: GPhotosAuth.AuthException) {
        SourceHealth.degraded("Google sign-in expired — open the source to sign in again")
    } catch (e: Exception) {
        SourceHealth.offline(e.message ?: "Could not reach Google")
    }

    /** Emit the locally-stored set so the metadata diff treats every picked photo as still present. */
    override suspend fun listPhotos(onPage: suspend (List<RemotePhoto>) -> Unit) {
        var after = ""
        while (true) {
            val page = photoDao.sourceIndexPage(id, after, PAGE_SIZE)
            if (page.isEmpty()) break
            onPage(page.map { RemotePhoto(remoteId = it.remoteId) })
            after = page.last().remoteId
            if (page.size < PAGE_SIZE) break
        }
    }

    override suspend fun fetch(photo: RemotePhoto, sink: Sink) {
        throw UnsupportedOperationException(
            "Picker photos are downloaded during picking, never fetched on demand",
        )
    }

    private companion object {
        const val PAGE_SIZE = 500
    }
}
