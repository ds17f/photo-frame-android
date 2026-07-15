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
 * permanent.
 *
 * The only photos never evicted are those in the prefetcher's **protected set** (its horizon +
 * in-flight downloads), so eviction and prefetch don't fight over the same bytes. Never-shown
 * photos are evicted least-recently-shown-first like any other (SQLite orders NULL `lastShownAt`
 * first): they are speculative — prefetched-then-skipped orphans — and the "freshly prefetched,
 * awaiting its turn" case is already covered by horizon protection. (Protecting *all* never-shown
 * rows, as an earlier design did, let such orphans accumulate past the cap so it couldn't be held.)
 */
class CacheEvictor(
    private val photoDao: PhotoDao,
    /**
     * Source ids whose files must never be evicted — Picker sources, whose photos can't be
     * re-downloaded once evicted (baseURLs expire), so they persist until the user clears them
     * (DESIGN §5.2/§6). Resolved per-enforce so newly-added picker sources are covered.
     */
    private val pinnedSourceIds: suspend () -> Set<String> = { emptySet() },
) {

    /**
     * Evict files down to [capBytes] if over. [protectedKeys] (the prefetcher's horizon/in-flight
     * set) and any [pinnedSourceIds] source are never evicted. Returns bytes freed. Runs on IO.
     */
    suspend fun enforce(capBytes: Long, protectedKeys: Set<String> = emptySet()): Long =
        withContext(Dispatchers.IO) {
            val total = photoDao.totalBytes()
            if (total <= capBytes) return@withContext 0L

            val pinned = pinnedSourceIds()
            val candidates = photoDao.cachedByLeastRecentlyShown(EVICTION_SCAN_LIMIT)
            val victims = selectVictims(candidates, total, capBytes, protectedKeys, pinned)
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
         * Pure victim selection: least-recently-shown first (never-shown/NULL first — speculative
         * orphans are the least valuable to keep), skipping only anything in [protectedKeys] (the
         * prefetch horizon + in-flight). [ordered] must be ascending by `lastShownAt` (as
         * [PhotoDao.cachedByLeastRecentlyShown] returns; SQLite sorts NULLs first). Evicts until the
         * cached total would fall to [capBytes].
         */
        fun selectVictims(
            ordered: List<EvictionRef>,
            totalBytes: Long,
            capBytes: Long,
            protectedKeys: Set<String> = emptySet(),
            pinnedSourceIds: Set<String> = emptySet(),
        ): List<EvictionRef> {
            var remaining = totalBytes
            val victims = ArrayList<EvictionRef>()
            for (photo in ordered) {
                if (remaining <= capBytes) break
                if (photo.key in protectedKeys) continue // prefetch horizon / in-flight
                if (photo.sourceId in pinnedSourceIds) continue // Picker photos never evict
                victims += photo
                remaining -= photo.sizeBytes
            }
            return victims
        }
    }
}
