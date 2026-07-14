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
 * docs/STORAGE_PLAN.md). The source streams its listing page by page; each page is classified
 * against the index ([SyncDiff]) and new rows are inserted immediately, so the prefetcher can start
 * pulling and the frame lights up within seconds — and each page is freed rather than holding the
 * whole (60k+) library in memory. Nothing is downloaded here.
 *
 * A failure to reach the source is reported OFFLINE and leaves the index intact (the frame keeps
 * playing from the retained pool, DESIGN §6). Crucially, **mirror deletion only runs after the full
 * listing succeeds** — a partial listing must never delete the photos it didn't reach. Writes are
 * split so a refresh never clobbers cache state.
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

        // Snapshot the current index for this source (paged so a 60k single-source library doesn't
        // overflow the CursorWindow). Only the id set is retained across the whole listing.
        val existing = HashMap<String, SourceIndexRow>()
        loadExistingIndexInto(existing, sourceId)
        val seen = HashSet<String>(existing.size)
        var indexed = 0
        var reindexed = 0

        // Stream the remote listing, applying each page as it arrives.
        val listedOk = try {
            source.listPhotos { page ->
                val toInsert = ArrayList<PhotoEntity>(page.size)
                for (photo in page) {
                    if (!seen.add(photo.remoteId)) continue
                    val have = existing[photo.remoteId]
                    when (SyncDiff.classify(photo.contentHash, have?.contentHash, have != null)) {
                        SyncDiff.Action.INSERT -> toInsert += photo.toIndexRow(sourceId)
                        SyncDiff.Action.REINDEX -> {
                            have?.localPath?.let { runCatching { File(it).delete() } }
                            photoDao.reindexChanged(
                                sourceId = sourceId,
                                remoteId = photo.remoteId,
                                takenAt = photo.takenAt?.toEpochMilli(),
                                width = photo.width,
                                height = photo.height,
                                locationLabel = photo.locationLabel,
                                contentHash = photo.contentHash,
                            )
                            reindexed++
                        }
                        SyncDiff.Action.SKIP -> {}
                    }
                }
                if (toInsert.isNotEmpty()) {
                    photoDao.insertNew(toInsert) // incremental: visible to the slideshow right away
                    indexed += toInsert.size
                }
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "listPhotos failed for $sourceId", e)
            false
        }

        // On a failed/partial listing, leave the index intact — never mirror-delete what we didn't see.
        if (!listedOk) {
            return@withContext outcome(
                sourceId, indexed, reindexed, 0,
                SourceHealth(SourceHealthStatus.OFFLINE, "Could not reach source"),
            )
        }

        // Mirror deletions: indexed photos the full listing didn't cover.
        val toDelete = SyncDiff.toDelete(existing.keys, seen, deletionPolicy)
        if (toDelete.isNotEmpty()) {
            toDelete.forEach { remoteId ->
                existing[remoteId]?.localPath?.let { runCatching { File(it).delete() } }
            }
            photoDao.deleteFromSource(sourceId, toDelete)
        }

        outcome(sourceId, indexed, reindexed, toDelete.size, SourceHealth.OK)
    }

    private suspend fun outcome(
        sourceId: String,
        indexed: Int,
        reindexed: Int,
        removed: Int,
        health: SourceHealth,
    ): SyncOutcome = SyncOutcome(
        indexed = indexed,
        reindexed = reindexed,
        removed = removed,
        health = health,
        indexedCount = photoDao.countForSource(sourceId),
        cachedCount = photoDao.cachedCountForSource(sourceId),
    )

    /** Load the whole per-source index into [into], page by page (CursorWindow-safe). */
    private suspend fun loadExistingIndexInto(into: HashMap<String, SourceIndexRow>, sourceId: String) {
        var after = ""
        while (true) {
            val page = photoDao.sourceIndexPage(sourceId, after, INDEX_PAGE_SIZE)
            if (page.isEmpty()) break
            page.forEach { into[it.remoteId] = it }
            after = page.last().remoteId
            if (page.size < INDEX_PAGE_SIZE) break
        }
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
