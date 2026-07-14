package com.photoframe.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {

    @Query("SELECT * FROM sources ORDER BY displayName")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE enabled = 1")
    suspend fun listEnabled(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun byId(id: String): SourceEntity?

    @Query("SELECT * FROM sources")
    suspend fun listAll(): List<SourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: SourceEntity)

    @Query("UPDATE sources SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    /** Record the outcome of a sync run without disturbing the config columns. */
    @Query(
        "UPDATE sources SET health = :health, lastError = :lastError, " +
            "lastSyncAt = :lastSyncAt, photoCount = :photoCount WHERE id = :id",
    )
    suspend fun updateHealth(
        id: String,
        health: String,
        lastError: String?,
        lastSyncAt: Long?,
        photoCount: Int,
    )

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: String)
}
