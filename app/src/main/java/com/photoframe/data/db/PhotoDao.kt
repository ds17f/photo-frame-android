package com.photoframe.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoDao {

    /** All displayable photos, observed live so the playlist reacts to sync changes. */
    @Query("SELECT * FROM photos WHERE status = :status ORDER BY addedAt DESC")
    fun observeByStatus(status: String = "READY"): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE status = :status")
    suspend fun listByStatus(status: String = "READY"): List<PhotoEntity>

    @Query("SELECT COUNT(*) FROM photos WHERE status = :status")
    fun observeCount(status: String = "READY"): Flow<Int>

    @Query("SELECT remoteId FROM photos WHERE sourceId = :sourceId")
    suspend fun remoteIdsForSource(sourceId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(photos: List<PhotoEntity>)

    @Query("UPDATE photos SET lastShownAt = :shownAt WHERE sourceId = :sourceId AND remoteId = :remoteId")
    suspend fun markShown(sourceId: String, remoteId: String, shownAt: Long)

    @Query("DELETE FROM photos WHERE sourceId = :sourceId AND remoteId IN (:remoteIds)")
    suspend fun deleteFromSource(sourceId: String, remoteIds: List<String>)

    @Query("DELETE FROM photos WHERE sourceId = :sourceId")
    suspend fun deleteAllForSource(sourceId: String)

    @Query("SELECT * FROM photos ORDER BY lastShownAt ASC")
    suspend fun allByLeastRecentlyShown(): List<PhotoEntity>
}
