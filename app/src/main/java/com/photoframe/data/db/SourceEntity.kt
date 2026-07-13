package com.photoframe.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Config snapshot + health for one configured photo source (DESIGN §6). Populated more fully
 * by the source-management UI and sync engine in Phase 2; Phase 1 seeds fixture rows.
 */
@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey val id: String,
    /** [com.photoframe.data.SourceType] name. */
    val type: String,
    val displayName: String,
    val enabled: Boolean,
    /** Opaque per-type JSON configuration (album ids, URL, folder, …). */
    val configJson: String,
    /** [SourceHealth] name, e.g. OK / DEGRADED / OFFLINE. */
    val health: String,
    val lastSyncAt: Long?,
    val lastError: String?,
    val photoCount: Int,
)
