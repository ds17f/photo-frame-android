package com.photoframe.data.cache

import android.util.Log
import com.photoframe.data.db.PhotoDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Coordinates cache-file maintenance that spans the DB index and the on-disk files (streaming-cache
 * model, docs/STORAGE_PLAN.md): switching the cache location, and the startup consistency sweep.
 * Both keep the index intact — only files (and their `localPath`/`status`) change; the prefetcher
 * repopulates.
 */
class CacheMaintainer(
    private val photoDao: PhotoDao,
    private val cache: PhotoCache,
) {

    /**
     * Switch the cache location: delete the old location's files, null every row's `localPath`
     * (back to `INDEXED`), then point the cache at [to]. The index — including all show history — is
     * untouched; the running prefetcher refills the new location within seconds. No-op if already
     * there.
     */
    suspend fun switchLocation(to: CacheLocation): Boolean = withContext(Dispatchers.IO) {
        if (cache.currentLocation() == to) return@withContext false
        cache.deleteAllFiles()   // old location
        photoDao.evictAllFiles() // localPath = null, sizeBytes = 0, status = INDEXED for every row
        cache.setLocation(to)
        Log.i(TAG, "Cache location switched to $to")
        true
    }

    /**
     * Apply the persisted location on startup (before any fetch) without touching files — just
     * points the cache at the saved location. Files written under the other location are reconciled
     * by [sweepMissingFiles] + render-time repair.
     */
    fun applyLocation(location: CacheLocation) = cache.setLocation(location)

    /**
     * Startup consistency sweep: walk every `READY` row and, if its backing file is gone (e.g. the
     * SD card was pulled while the app was off), repair it to `INDEXED` so the prefetcher re-fetches.
     * Paged so a large cache doesn't overflow the CursorWindow. Returns how many rows were repaired.
     */
    suspend fun sweepMissingFiles(): Int = withContext(Dispatchers.IO) {
        var repaired = 0
        for (sourceId in photoDao.sourcesWithCachedFiles()) {
            var after = ""
            while (true) {
                val page = photoDao.cachedPageForSource(sourceId, after, SWEEP_PAGE)
                if (page.isEmpty()) break
                for (row in page) {
                    val path = row.localPath
                    if (path == null || !File(path).exists()) {
                        photoDao.markEvicted(sourceId, row.remoteId)
                        repaired++
                    }
                }
                after = page.last().remoteId
                if (page.size < SWEEP_PAGE) break
            }
        }
        if (repaired > 0) Log.i(TAG, "Startup sweep repaired $repaired missing-file rows")
        repaired
    }

    companion object {
        private const val TAG = "CacheMaintainer"
        private const val SWEEP_PAGE = 5000
    }
}
