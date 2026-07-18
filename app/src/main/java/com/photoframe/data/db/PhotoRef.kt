package com.photoframe.data.db

/**
 * Lightweight row projection over [PhotoEntity] for the playlist (DESIGN §6/§7): only the
 * columns the engine actually needs to order and pair photos. Observing the *whole* ready set as
 * full entities overflows SQLite's fixed 2 MB CursorWindow once the cache holds ~1900+ photos
 * (each wide row is ~1 KB), which crashes the read. These rows are ~60 bytes, so the same window
 * comfortably holds tens of thousands. The heavy fields (localPath, locationLabel) are fetched
 * per displayed photo via [PhotoDao.getByKey].
 */
data class PhotoRef(
    val sourceId: String,
    val remoteId: String,
    val width: Int?,
    val height: Int?,
    val takenAt: Long?,
    val addedAt: Long,
    val isFavorite: Boolean,
    /** Last time this photo was shown (epoch millis), or null if never. Orders the shuffle so
     *  recently-shown photos sink to the back and don't repeat until the library cycles — across
     *  restarts too, since it's persisted. */
    val lastShownAt: Long?,
) {
    /** Stable per-photo key, matching [PhotoEntity.key]. */
    val key: String get() = "$sourceId/$remoteId"
}

/**
 * Minimal per-source index projection for metadata-sync diffing (streaming-cache model). Carries
 * just enough to diff against the remote listing and to clean up files on change/removal, without
 * loading fat [PhotoEntity] rows (which overflow the CursorWindow at ~35k rows — see [PhotoRef]).
 */
data class SourceIndexRow(
    val remoteId: String,
    val contentHash: String?,
    val status: String,
    val localPath: String?,
    val isFavorite: Boolean,
)

/** A photo past the miss-streak threshold, to be mirror-deleted: its key and cache file to reclaim. */
data class PendingDeletion(
    val remoteId: String,
    val localPath: String?,
)

/**
 * Projection of a cached (`READY`) row for eviction: least-recently-shown selection needs only the
 * key, size and show time (plus the file path to delete). Kept narrow so the READY set fits the
 * CursorWindow even with a large cache.
 */
data class EvictionRef(
    val sourceId: String,
    val remoteId: String,
    val localPath: String?,
    val sizeBytes: Long,
    val lastShownAt: Long?,
) {
    val key: String get() = "$sourceId/$remoteId"
}
