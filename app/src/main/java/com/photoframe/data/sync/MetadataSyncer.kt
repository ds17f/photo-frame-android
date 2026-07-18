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
                    // Favoriting doesn't change the content hash, so REINDEX won't catch it —
                    // reconcile it directly on any existing row whose flag drifted (INSERT rows
                    // get it from toIndexRow).
                    if (have != null && have.isFavorite != photo.isFavorite) {
                        photoDao.markFavorite(sourceId, photo.remoteId, photo.isFavorite)
                    }
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

        // Mirror deletions, DEBOUNCED. A photo absent from this listing is only a *candidate*:
        // Immich's offset pagination over non-unique `fileCreatedAt` intermittently drops ~1 asset
        // per page boundary, so a single miss is unreliable (it re-appears next pass). We delete only
        // after [DELETE_MISS_STREAK] consecutive misses — a real removal/hide is absent every pass and
        // crosses the threshold; pagination flicker recovers and resets to 0, so the ~70k stable set
        // never churns. See docs/API_NOTES.md.
        val missing = SyncDiff.toDelete(existing.keys, seen, deletionPolicy) // policy-gated candidates
        var removed = 0
        if (deletionPolicy == DeletionPolicy.MIRROR) {
            // Recoveries: rows previously mid-streak that the listing covered again → reset to 0.
            val recovered = photoDao.remoteIdsWithMissStreak(sourceId).toSet() - missing.toSet()
            recovered.chunked(SQLITE_BIND_LIMIT).forEach { photoDao.resetMissStreak(sourceId, it) }
            // Misses: bump the streak; then delete only those that have now crossed the threshold.
            missing.chunked(SQLITE_BIND_LIMIT).forEach { photoDao.incrementMissStreak(sourceId, it) }
            val doomed = photoDao.missStreakExceeding(sourceId, DELETE_MISS_STREAK)
            if (doomed.isNotEmpty()) {
                doomed.forEach { it.localPath?.let { p -> runCatching { File(p).delete() } } }
                doomed.map { it.remoteId }.chunked(SQLITE_BIND_LIMIT)
                    .forEach { photoDao.deleteFromSource(sourceId, it) }
                removed = doomed.size
            }
        }

        outcome(sourceId, indexed, reindexed, removed, SourceHealth.OK)
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
        isFavorite = isFavorite,
    )

    companion object {
        private const val TAG = "MetadataSyncer"
        private const val INDEX_PAGE_SIZE = 5000

        /** Consecutive full syncs a photo must be missing from the listing before it's mirror-deleted.
         *  At the 30-min default sync interval this is ~1.5 h — long enough that pagination flicker
         *  (which recovers on the very next pass) never deletes a stable photo, short enough that a
         *  genuine remote removal still leaves the frame promptly. */
        private const val DELETE_MISS_STREAK = 3

        /** SQLite caps host parameters per statement (999); chunk `IN (:ids)` writes below it. */
        private const val SQLITE_BIND_LIMIT = 900
    }
}
