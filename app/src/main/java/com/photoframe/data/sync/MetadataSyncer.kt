package com.photoframe.data.sync

import android.util.Log
import com.photoframe.data.PhotoStatus
import com.photoframe.data.cache.PhotoCache
import com.photoframe.data.db.PhotoDao
import com.photoframe.data.db.PhotoEntity
import com.photoframe.data.db.SourceIndexRow
import com.photoframe.data.source.DeletionPolicy
import com.photoframe.data.source.PhotoSource
import com.photoframe.data.source.RemotePhoto
import com.photoframe.data.source.SourceHealth
import com.photoframe.data.source.SourceHealthStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Outcome of one metadata-sync pass, for health recording + the diagnostics log. */
data class SyncOutcome(
    /** New photos added to the index this pass. */
    val indexed: Int,
    /** Existing rows re-indexed because their remote content changed. */
    val reindexed: Int,
    /** Rows removed (MIRROR deletion of vanished photos). */
    val removed: Int,
    val health: SourceHealth,
    /** Total photos indexed for this source (what the frame can see). */
    val indexedCount: Int,
    /** Of those, how many currently have a cached file. */
    val cachedCount: Int,
)

/**
 * Syncs one live [PhotoSource]'s **metadata** into the index (streaming-cache model,
 * docs/STORAGE_PLAN.md): `listPhotos()` → [SyncDiff] against the index → upsert index rows only.
 * It downloads nothing — the [PhotoPrefetcher] pulls files on demand ahead of playback — so a fresh
 * source is fully indexed in seconds and the playlist immediately roams the whole library.
 *
 * A failure to reach the source is reported OFFLINE and leaves the index untouched, so the frame
 * keeps playing from the retained pool (offline resilience, DESIGN §6). Writes are split so a
 * refresh never clobbers cache state: insert-new for new rows, re-index for changed content, and a
 * targeted delete for vanished photos.
 */
class MetadataSyncer(
    private val photoDao: PhotoDao,
    private val cache: PhotoCache,
) {

    suspend fun sync(
        source: PhotoSource,
        deletionPolicy: DeletionPolicy,
    ): SyncOutcome = withContext(Dispatchers.IO) {
        val sourceId = source.id

        // 1. Desired set from the remote. Any failure here means the source is unreachable — the
        //    index is left exactly as-is so the frame keeps playing (§6).
        val remote = try {
            source.listPhotos()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "listPhotos failed for $sourceId", e)
            return@withContext offlineOutcome(sourceId, e.message ?: "Could not reach source")
        }

        // 2. Diff against the local index (loaded via keyset paging so a 60k single-source library
        //    doesn't overflow the CursorWindow).
        val existing = loadExistingIndex(sourceId)
        val existingById = existing.associateBy { it.remoteId }
        val plan = SyncDiff.plan(
            remote = remote,
            existing = existing.map { ExistingPhoto(it.remoteId, it.contentHash) },
            deletionPolicy = deletionPolicy,
        )

        // 3. Insert new index rows (no download — the prefetcher fetches on demand).
        if (plan.toInsert.isNotEmpty()) {
            photoDao.insertNew(plan.toInsert.map { it.toIndexRow(sourceId) })
        }

        // 4. Re-index changed content: drop the stale file, flip back to INDEXED, adopt new metadata.
        for (photo in plan.toReindex) {
            existingById[photo.remoteId]?.localPath?.let { runCatching { File(it).delete() } }
            photoDao.reindexChanged(
                sourceId = sourceId,
                remoteId = photo.remoteId,
                takenAt = photo.takenAt?.toEpochMilli(),
                width = photo.width,
                height = photo.height,
                locationLabel = photo.locationLabel,
                contentHash = photo.contentHash,
            )
        }

        // 5. Mirror deletions: remove the row and its file.
        if (plan.toDelete.isNotEmpty()) {
            plan.toDelete.forEach { remoteId ->
                existingById[remoteId]?.localPath?.let { runCatching { File(it).delete() } }
            }
            photoDao.deleteFromSource(sourceId, plan.toDelete)
        }

        val indexedCount = photoDao.countForSource(sourceId)
        val cachedCount = photoDao.cachedCountForSource(sourceId)
        SyncOutcome(
            indexed = plan.toInsert.size,
            reindexed = plan.toReindex.size,
            removed = plan.toDelete.size,
            health = SourceHealth.OK,
            indexedCount = indexedCount,
            cachedCount = cachedCount,
        )
    }

    /** Load the whole per-source index as light rows, page by page (CursorWindow-safe). */
    private suspend fun loadExistingIndex(sourceId: String): List<SourceIndexRow> {
        val out = ArrayList<SourceIndexRow>()
        var after = ""
        while (true) {
            val page = photoDao.sourceIndexPage(sourceId, after, INDEX_PAGE_SIZE)
            if (page.isEmpty()) break
            out += page
            after = page.last().remoteId
            if (page.size < INDEX_PAGE_SIZE) break
        }
        return out
    }

    private suspend fun offlineOutcome(sourceId: String, message: String): SyncOutcome {
        val indexedCount = photoDao.countForSource(sourceId)
        val cachedCount = photoDao.cachedCountForSource(sourceId)
        return SyncOutcome(
            indexed = 0,
            reindexed = 0,
            removed = 0,
            health = SourceHealth(SourceHealthStatus.OFFLINE, message),
            indexedCount = indexedCount,
            cachedCount = cachedCount,
        )
    }

    private fun RemotePhoto.toIndexRow(sourceId: String): PhotoEntity = PhotoEntity(
        sourceId = sourceId,
        remoteId = remoteId,
        localPath = null,
        takenAt = takenAt?.toEpochMilli(),
        width = width,
        height = height,
        locationLabel = locationLabel,
        addedAt = System.currentTimeMillis(),
        lastShownAt = null,
        sizeBytes = 0,
        status = PhotoStatus.INDEXED.name,
        contentHash = contentHash,
    )

    companion object {
        private const val TAG = "MetadataSyncer"
        private const val INDEX_PAGE_SIZE = 5000
    }
}
