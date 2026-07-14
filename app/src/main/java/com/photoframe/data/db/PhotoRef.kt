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
) {
    /** Stable per-photo key, matching [PhotoEntity.key]. */
    val key: String get() = "$sourceId/$remoteId"
}
