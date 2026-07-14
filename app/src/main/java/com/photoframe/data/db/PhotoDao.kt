package com.photoframe.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Index access for the streaming-cache model (docs/STORAGE_PLAN.md). The playlist draws from the
 * **full** index (every remote photo, `INDEXED` + `READY`), not just cached rows, so it roams the
 * whole collection. Because a 60k index overflows SQLite's 2 MB CursorWindow even as [PhotoRef],
 * callers observe the cheap [observeRowCount] invalidation signal and reload via keyset-paged
 * [indexPage] reads rather than one observed full-table query.
 *
 * Writes are split so a metadata refresh never clobbers cache state (a blind REPLACE upsert would):
 * [insertNew] for new index rows, [updateRemoteMetadata]/[reindexChanged] for existing rows, and
 * [markCached]/[markEvicted] for the file lifecycle.
 */
@Dao
interface PhotoDao {

    // ---- Playlist feed (keyset-paged; never observe the full table) ------------------------

    /** Cheap invalidation signal: total index size. Reload the playlist via [indexPage] when it changes. */
    @Query("SELECT COUNT(*) FROM photos")
    fun observeRowCount(): Flow<Int>

    /** Count of indexed photos (the "photos" the frame can see). */
    @Query("SELECT COUNT(*) FROM photos")
    suspend fun rowCount(): Int

    /**
     * One keyset page of the full index, ordered by the composite key. Pass ("", "") for the first
     * page, then the last row's (sourceId, remoteId) for the next. All real keys sort after ("", "").
     */
    @Query(
        "SELECT sourceId, remoteId, width, height, takenAt, addedAt FROM photos " +
            "WHERE (sourceId > :afterSourceId) OR (sourceId = :afterSourceId AND remoteId > :afterRemoteId) " +
            "ORDER BY sourceId, remoteId LIMIT :limit",
    )
    suspend fun indexPage(afterSourceId: String, afterRemoteId: String, limit: Int): List<PhotoRef>

    // ---- Single-row resolution (playback / prefetch) ---------------------------------------

    /** Full row for one photo, by primary key — resolves the on-screen slide and prefetch target. */
    @Query("SELECT * FROM photos WHERE sourceId = :sourceId AND remoteId = :remoteId")
    suspend fun getByKey(sourceId: String, remoteId: String): PhotoEntity?

    // ---- Counts / usage --------------------------------------------------------------------

    @Query("SELECT COUNT(*) FROM photos WHERE status = :status")
    fun observeCount(status: String = "READY"): Flow<Int>

    @Query("SELECT COUNT(*) FROM photos WHERE sourceId = :sourceId")
    suspend fun countForSource(sourceId: String): Int

    @Query("SELECT COUNT(*) FROM photos WHERE sourceId = :sourceId AND status = :status")
    suspend fun cachedCountForSource(sourceId: String, status: String = "READY"): Int

    /** Total bytes of cached photos, from the index (evicted rows carry sizeBytes 0). */
    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM photos")
    suspend fun totalBytes(): Long

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM photos")
    fun observeTotalBytes(): Flow<Long>

    // ---- Metadata sync (diff + split writes) -----------------------------------------------

    /**
     * One keyset page of the lightweight per-source index, for diffing against the remote listing.
     * Paged so a 60k single-source library doesn't overflow the CursorWindow.
     */
    @Query(
        "SELECT remoteId, contentHash, status, localPath FROM photos " +
            "WHERE sourceId = :sourceId AND remoteId > :afterRemoteId " +
            "ORDER BY remoteId LIMIT :limit",
    )
    suspend fun sourceIndexPage(sourceId: String, afterRemoteId: String, limit: Int): List<SourceIndexRow>

    /** Insert new index rows; existing rows are left untouched (their cache state is preserved). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNew(photos: List<PhotoEntity>)

    /**
     * Refresh remote-owned metadata on an existing row without disturbing cache state
     * (`localPath`/`status`/`sizeBytes`/`lastShownAt`/`addedAt`). Decoded display dims on a READY
     * row are preserved — only INDEXED rows (whose dims are the remote estimate) get the new dims.
     */
    @Query(
        "UPDATE photos SET takenAt = :takenAt, locationLabel = :locationLabel, contentHash = :contentHash, " +
            "width = CASE WHEN status = 'READY' THEN width ELSE :width END, " +
            "height = CASE WHEN status = 'READY' THEN height ELSE :height END " +
            "WHERE sourceId = :sourceId AND remoteId = :remoteId",
    )
    suspend fun updateRemoteMetadata(
        sourceId: String,
        remoteId: String,
        takenAt: Long?,
        width: Int?,
        height: Int?,
        locationLabel: String?,
        contentHash: String?,
    )

    /**
     * The remote content changed: keep the row + its show history, drop the stale file (status back
     * to `INDEXED`, path/size cleared) so the prefetcher re-fetches, and adopt the new metadata.
     * Caller deletes the backing file.
     */
    @Query(
        "UPDATE photos SET localPath = NULL, sizeBytes = 0, status = 'INDEXED', " +
            "takenAt = :takenAt, width = :width, height = :height, locationLabel = :locationLabel, " +
            "contentHash = :contentHash WHERE sourceId = :sourceId AND remoteId = :remoteId",
    )
    suspend fun reindexChanged(
        sourceId: String,
        remoteId: String,
        takenAt: Long?,
        width: Int?,
        height: Int?,
        locationLabel: String?,
        contentHash: String?,
    )

    // ---- File lifecycle (prefetch / eviction) ----------------------------------------------

    /** Mark a row cached after a successful prefetch (with decode-corrected display dims). */
    @Query(
        "UPDATE photos SET localPath = :localPath, sizeBytes = :sizeBytes, status = 'READY', " +
            "width = :width, height = :height WHERE sourceId = :sourceId AND remoteId = :remoteId",
    )
    suspend fun markCached(
        sourceId: String,
        remoteId: String,
        localPath: String,
        sizeBytes: Long,
        width: Int?,
        height: Int?,
    )

    /**
     * Evict a row's file but keep the row (and its `lastShownAt` show history): path/size cleared,
     * status back to `INDEXED`. Also used to repair a row whose file went missing (SD card pulled).
     */
    @Query(
        "UPDATE photos SET localPath = NULL, sizeBytes = 0, status = 'INDEXED' " +
            "WHERE sourceId = :sourceId AND remoteId = :remoteId",
    )
    suspend fun markEvicted(sourceId: String, remoteId: String)

    /** Null out every cache path (location switch): the prefetcher repopulates from the index. */
    @Query("UPDATE photos SET localPath = NULL, sizeBytes = 0, status = 'INDEXED'")
    suspend fun evictAllFiles()

    /** READY rows ordered least-recently-shown first, for eviction victim selection. */
    @Query(
        "SELECT sourceId, remoteId, localPath, sizeBytes, lastShownAt FROM photos " +
            "WHERE status = 'READY' ORDER BY lastShownAt ASC LIMIT :limit",
    )
    suspend fun cachedByLeastRecentlyShown(limit: Int): List<EvictionRef>

    /** All READY rows (paged), for the startup consistency sweep that verifies files still exist. */
    @Query(
        "SELECT sourceId, remoteId, localPath, sizeBytes, lastShownAt FROM photos " +
            "WHERE status = 'READY' AND remoteId > :afterRemoteId AND sourceId = :sourceId " +
            "ORDER BY remoteId LIMIT :limit",
    )
    suspend fun cachedPageForSource(sourceId: String, afterRemoteId: String, limit: Int): List<EvictionRef>

    @Query("SELECT DISTINCT sourceId FROM photos WHERE status = 'READY'")
    suspend fun sourcesWithCachedFiles(): List<String>

    // ---- Show history + deletion -----------------------------------------------------------

    @Query("UPDATE photos SET lastShownAt = :shownAt WHERE sourceId = :sourceId AND remoteId = :remoteId")
    suspend fun markShown(sourceId: String, remoteId: String, shownAt: Long)

    @Query("DELETE FROM photos WHERE sourceId = :sourceId AND remoteId IN (:remoteIds)")
    suspend fun deleteFromSource(sourceId: String, remoteIds: List<String>)

    @Query("DELETE FROM photos WHERE sourceId = :sourceId")
    suspend fun deleteAllForSource(sourceId: String)
}
