package com.photoframe.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One line in the sync log surfaced on the diagnostics screen (DESIGN §6/§11) — "invaluable for
 * a headless frame". Old rows are trimmed by [SyncLogDao.trimTo]; this is a ring buffer, not an
 * audit trail. Never contains secrets (§12).
 */
@Entity(
    tableName = "sync_log",
    indices = [Index("createdAt")],
)
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val sourceId: String?,
    /** [Level] name. */
    val level: String,
    val message: String,
) {
    enum class Level { INFO, WARN, ERROR }
}
