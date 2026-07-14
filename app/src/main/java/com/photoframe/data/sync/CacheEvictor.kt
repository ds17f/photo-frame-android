package com.photoframe.data.sync

import android.util.Log
import com.photoframe.data.db.PhotoDao
import com.photoframe.data.db.PhotoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * LRU cache eviction under the configured size cap (DESIGN §6). Evicts least-recently-shown
 * photos first, but **never evicts a photo that has not yet been shown** — those are freshly
 * synced and haven't had their turn, which honors "never evict below one full playlist cycle".
 * Deletes the backing file and the Room row together.
 */
class CacheEvictor(private val photoDao: PhotoDao) {

    /** Evict down to [capBytes] if over. Returns bytes freed. Runs on IO. */
    suspend fun enforce(capBytes: Long): Long = withContext(Dispatchers.IO) {
        val total = photoDao.totalBytes()
        if (total <= capBytes) return@withContext 0L

        val victims = selectVictims(photoDao.allByLeastRecentlyShown(), total, capBytes)
        var freed = 0L
        for (victim in victims) {
            runCatching { File(victim.localPath).delete() }
                .onFailure { Log.w(TAG, "Failed to delete ${victim.localPath}", it) }
            photoDao.deleteFromSource(victim.sourceId, listOf(victim.remoteId))
            freed += victim.sizeBytes
        }
        if (freed > 0) Log.i(TAG, "Evicted ${victims.size} photos, freed $freed bytes")
        freed
    }

    companion object {
        private const val TAG = "CacheEvictor"

        /**
         * Pure selection: least-recently-shown first, protecting never-shown photos
         * (`lastShownAt == null`). [ordered] must be ascending by `lastShownAt` as
         * [PhotoDao.allByLeastRecentlyShown] returns (SQLite sorts NULLs first, so never-shown
         * rows lead — we skip them explicitly rather than relying on ordering).
         */
        fun selectVictims(
            ordered: List<PhotoEntity>,
            totalBytes: Long,
            capBytes: Long,
        ): List<PhotoEntity> {
            var remaining = totalBytes
            val victims = ArrayList<PhotoEntity>()
            for (photo in ordered.filter { it.lastShownAt != null }.sortedBy { it.lastShownAt }) {
                if (remaining <= capBytes) break
                victims += photo
                remaining -= photo.sizeBytes
            }
            return victims
        }
    }
}
