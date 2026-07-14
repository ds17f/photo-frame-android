package com.photoframe.data.sync

import android.util.Log
import com.photoframe.data.db.EvictionRef
import com.photoframe.data.db.PhotoDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * LRU cache eviction under the configured size cap (streaming-cache model, docs/STORAGE_PLAN.md).
 * Evicts the **file** of the least-recently-shown cached photos, but **keeps the index row** (status
 * back to `INDEXED`, `lastShownAt` preserved) — reach lives in the index, and show history is
 * permanent. Never evicts a photo that hasn't been shown yet (freshly prefetched, awaiting its turn)
 * nor any photo in the prefetcher's protected set (its horizon + in-flight downloads), so eviction
 * and prefetch don't fight over the same bytes.
 */
class CacheEvictor(private val photoDao: PhotoDao) {

    /**
     * Evict files down to [capBytes] if over. [protectedKeys] (the prefetcher's horizon/in-flight
     * set) are never evicted. Returns bytes freed. Runs on IO.
     */
    suspend fun enforce(capBytes: Long, protectedKeys: Set<String> = emptySet()): Long =
        withContext(Dispatchers.IO) {
            val total = photoDao.totalBytes()
            if (total <= capBytes) return@withContext 0L

            val candidates = photoDao.cachedByLeastRecentlyShown(EVICTION_SCAN_LIMIT)
            val victims = selectVictims(candidates, total, capBytes, protectedKeys)
            var freed = 0L
            for (victim in victims) {
                victim.localPath?.let { path ->
                    runCatching { File(path).delete() }
                        .onFailure { Log.w(TAG, "Failed to delete $path", it) }
                }
                photoDao.markEvicted(victim.sourceId, victim.remoteId) // file gone, row kept
                freed += victim.sizeBytes
            }
            if (freed > 0) Log.i(TAG, "Evicted ${victims.size} files, freed $freed bytes")
            freed
        }

    companion object {
        private const val TAG = "CacheEvictor"

        /**
         * Upper bound on how many READY rows one pass considers. Comfortably above the cached-row
         * count at any sane cap (1 GB ≈ 4k files); keeps the projection inside the CursorWindow and
         * bounds a single pass — eviction runs again next sync/prefetch batch if more is needed.
         */
        const val EVICTION_SCAN_LIMIT = 20_000

        /**
         * Pure victim selection: least-recently-shown first, skipping never-shown photos
         * (`lastShownAt == null`, the one-full-cycle protection) and anything in [protectedKeys].
         * [ordered] must be ascending by `lastShownAt` (as [PhotoDao.cachedByLeastRecentlyShown]
         * returns). Evicts until the cached total would fall to [capBytes].
         */
        fun selectVictims(
            ordered: List<EvictionRef>,
            totalBytes: Long,
            capBytes: Long,
            protectedKeys: Set<String> = emptySet(),
        ): List<EvictionRef> {
            var remaining = totalBytes
            val victims = ArrayList<EvictionRef>()
            for (photo in ordered) {
                if (remaining <= capBytes) break
                if (photo.lastShownAt == null) continue // never shown — protected
                if (photo.key in protectedKeys) continue // prefetch horizon / in-flight
                victims += photo
                remaining -= photo.sizeBytes
            }
            return victims
        }
    }
}
