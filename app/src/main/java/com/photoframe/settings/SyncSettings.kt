package com.photoframe.settings

import com.photoframe.data.source.DeletionPolicy

/**
 * Sync + cache settings (DESIGN §6/§11), separate from display [DisplaySettings] because they
 * change rarely and drive background work rather than rendering.
 */
data class SyncSettings(
    /** WorkManager sync period. Immich on LAN is cheap to poll; default 30 min (§6). */
    val syncIntervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,
    /**
     * Retained-pool size cap in bytes (streaming-cache model, docs/STORAGE_PLAN.md). Under the
     * streaming design the cache is a prefetch buffer over the full index, so the cap is a boring
     * buffer size (default 1 GB ≈ ~4k photos of offline variety), not a depth limit — the frame
     * sees the whole library regardless. Files are LRU-evicted above this; rows persist.
     */
    val cacheCapBytes: Long = DEFAULT_CACHE_CAP_BYTES,
    val deletionPolicy: DeletionPolicy = DeletionPolicy.MIRROR,
    /** Only sync on unmetered (Wi-Fi) networks (§6). On for the always-plugged-in frame. */
    val requireUnmetered: Boolean = true,
) {
    companion object {
        const val DEFAULT_INTERVAL_MINUTES = 30
        const val DEFAULT_CACHE_CAP_BYTES = 1L * 1024 * 1024 * 1024 // 1 GB (~4k photos)

        /** WorkManager's hard floor for periodic work is 15 minutes; the UI clamps to this. */
        val INTERVAL_RANGE = 15..(24 * 60)

        /** 256 MB comfortably holds a playlist cycle's worth of variety; 32 GB fits a big SD card. */
        val CACHE_CAP_RANGE = (256L * 1024 * 1024)..(32L * 1024 * 1024 * 1024)
    }
}
