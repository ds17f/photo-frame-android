package com.photoframe.settings

import com.photoframe.data.cache.CacheLocation

/**
 * Storage settings (streaming-cache model, docs/STORAGE_PLAN.md): where the retained-pool cache
 * files live. The cap itself stays in [SyncSettings] (the evictor/prefetcher read it there); the
 * Storage screen surfaces both. Default is internal storage; the SD card is an option for a bigger
 * pool. Switching location is cheap now — the index (and all show history) is untouched; only files
 * move, and the prefetcher repopulates.
 */
data class StorageSettings(
    val location: CacheLocation = CacheLocation.INTERNAL,
)
