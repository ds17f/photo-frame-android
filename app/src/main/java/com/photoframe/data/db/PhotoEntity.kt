package com.photoframe.data.db

import androidx.room.Entity
import androidx.room.Index

/**
 * One photo index row (streaming-cache model, docs/STORAGE_PLAN.md). Primary key is
 * `sourceId + remoteId` so the same remote asset from two configured sources stays distinct.
 * Times are epoch millis (UTC); nullable where the source may not provide them.
 *
 * `localPath` is **nullable**: null (with [status] `INDEXED`) means the photo is indexed but not
 * downloaded; non-null (with `READY`) means a display-sized file is on disk. The slideshow renders
 * only from READY files, never from the network. `lastShownAt` survives eviction (rows persist),
 * so it is a permanent per-photo show history.
 */
@Entity(
    tableName = "photos",
    primaryKeys = ["sourceId", "remoteId"],
    indices = [
        Index("status"),
        Index("addedAt"),
        Index("lastShownAt"),
    ],
)
data class PhotoEntity(
    val sourceId: String,
    val remoteId: String,
    /** Null when the row is `INDEXED` (metadata only); set when the file is `READY` on disk. */
    val localPath: String?,
    val takenAt: Long?,
    val width: Int?,
    val height: Int?,
    val locationLabel: String?,
    val addedAt: Long,
    val lastShownAt: Long?,
    val sizeBytes: Long,
    /** [com.photoframe.data.PhotoStatus] name. */
    val status: String,
    /** Source content hash for change detection when available (DESIGN §5). */
    val contentHash: String? = null,
    /** Whether the source marks this photo a favorite; biases the slideshow toward it. */
    val isFavorite: Boolean = false,
) {
    /** Stable per-photo key used by the playlist engine and UI. */
    val key: String get() = "$sourceId/$remoteId"
}
