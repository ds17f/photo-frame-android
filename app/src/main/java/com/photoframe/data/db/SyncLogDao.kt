package com.photoframe.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncLogDao {

    @Insert
    suspend fun insert(entry: SyncLogEntity)

    @Query("SELECT * FROM sync_log ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<SyncLogEntity>>

    /** Keep only the newest [keep] rows (ring-buffer trim after each sync run). */
    @Query(
        "DELETE FROM sync_log WHERE id NOT IN " +
            "(SELECT id FROM sync_log ORDER BY createdAt DESC LIMIT :keep)",
    )
    suspend fun trimTo(keep: Int)
}
