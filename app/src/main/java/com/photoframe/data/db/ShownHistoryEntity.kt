package com.photoframe.data.db

import androidx.room.Entity

/**
 * When each photo was last shown, kept apart from the `photos` index on purpose. Sync can delete a
 * `photos` row (the server's listing missed it a few passes running — Immich pagination is
 * unstable over photos that share a timestamp) and later re-insert it. Without this table the
 * re-inserted row starts with no show history and plays again as "never shown". Sync never
 * deletes from here; re-inserted rows get their `lastShownAt` back from it
 * ([PhotoDao.insertNewRestoringHistory]).
 */
@Entity(tableName = "shown_history", primaryKeys = ["sourceId", "remoteId"])
data class ShownHistoryEntity(
    val sourceId: String,
    val remoteId: String,
    val lastShownAt: Long,
)
