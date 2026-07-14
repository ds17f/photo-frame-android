package com.photoframe.settings

import com.photoframe.data.source.DeletionPolicy

/**
 * Sync + cache settings (DESIGN §6/§11), separate from display [DisplaySettings] because they
 * change rarely and drive background work rather than rendering.
 */
data class SyncSettings(
    /** WorkManager sync period. Immich on LAN is cheap to poll; default 30 min (§6). */
    val syncIntervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,
    /** Cache size cap in bytes; LRU-evicted above this (default 4 GB, §6). */
    val cacheCapBytes: Long = DEFAULT_CACHE_CAP_BYTES,
    val deletionPolicy: DeletionPolicy = DeletionPolicy.MIRROR,
    /** Only sync on unmetered (Wi-Fi) networks (§6). On for the always-plugged-in frame. */
    val requireUnmetered: Boolean = true,
) {
    companion object {
        const val DEFAULT_INTERVAL_MINUTES = 30
        const val DEFAULT_CACHE_CAP_BYTES = 4L * 1024 * 1024 * 1024 // 4 GB

        /** WorkManager's hard floor for periodic work is 15 minutes; the UI clamps to this. */
        val INTERVAL_RANGE = 15..(24 * 60)
        val CACHE_CAP_RANGE = (512L * 1024 * 1024)..(32L * 1024 * 1024 * 1024)
    }
}
