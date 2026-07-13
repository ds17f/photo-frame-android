package com.photoframe.data.db

import androidx.room.Entity
import androidx.room.Index

/**
 * One cached photo (DESIGN §6). Primary key is `sourceId + remoteId` so the same remote
 * asset from two configured sources stays distinct. Times are epoch millis (UTC); nullable
 * where the source may not provide them. `localPath` points into app-private cache storage —
 * the slideshow renders only from these files, never from the network.
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
    val localPath: String,
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
) {
    /** Stable per-photo key used by the playlist engine and UI. */
    val key: String get() = "$sourceId/$remoteId"
}
