package com.photoframe.data

import com.photoframe.data.db.PhotoDao
import com.photoframe.data.db.PhotoEntity
import com.photoframe.data.db.PhotoRef
import kotlinx.coroutines.flow.Flow

/**
 * Read/write access to the photo index (streaming-cache model, docs/STORAGE_PLAN.md). The playlist
 * draws from the **full** index — reload it via [loadFullIndex] whenever [observeRowCount] fires —
 * and resolves each on-screen photo through [photoByKey]. The prefetcher flips rows to READY via
 * [markCached]; eviction drops files but keeps rows via [markEvicted]. Reads never touch the
 * network — only the local Room index and cache files.
 */
class PhotoRepository(private val photoDao: PhotoDao) {

    /** Cheap invalidation signal (index size); reload the playlist feed when it changes. */
    fun observeRowCount(): Flow<Int> = photoDao.observeRowCount()

    /** Live count of cached (READY) photos, for the "N cached" secondary counter. */
    fun observeCachedCount(): Flow<Int> = photoDao.observeCount(PhotoStatus.READY.name)

    /**
     * Load the entire index as lightweight [PhotoRef]s via keyset paging (never one observed
     * full-table query — that overflows the CursorWindow at ~35k rows). Off the main thread.
     */
    suspend fun loadFullIndex(pageSize: Int = INDEX_PAGE_SIZE): List<PhotoRef> {
        val out = ArrayList<PhotoRef>()
        var afterSource = ""
        var afterRemote = ""
        while (true) {
            val page = photoDao.indexPage(afterSource, afterRemote, pageSize)
            if (page.isEmpty()) break
            out += page
            val last = page.last()
            afterSource = last.sourceId
            afterRemote = last.remoteId
            if (page.size < pageSize) break
        }
        return out
    }

    suspend fun photoByKey(sourceId: String, remoteId: String): PhotoEntity? =
        photoDao.getByKey(sourceId, remoteId)

    /** Index fixture rows (debug/bundled samples): already-READY local files, insert if absent. */
    suspend fun indexFixtures(photos: List<PhotoEntity>) = photoDao.insertNew(photos)

    suspend fun markShown(sourceId: String, remoteId: String, shownAtMillis: Long) =
        photoDao.markShown(sourceId, remoteId, shownAtMillis)

    suspend fun deleteFromSource(sourceId: String, remoteIds: List<String>) =
        photoDao.deleteFromSource(sourceId, remoteIds)

    // ---- File lifecycle --------------------------------------------------------------------

    suspend fun markCached(
        sourceId: String,
        remoteId: String,
        localPath: String,
        sizeBytes: Long,
        width: Int?,
        height: Int?,
    ) = photoDao.markCached(sourceId, remoteId, localPath, sizeBytes, width, height)

    /** Repair a row whose backing file is gone (evicted or SD card pulled) → INDEXED for re-fetch. */
    suspend fun repairMissing(sourceId: String, remoteId: String) =
        photoDao.markEvicted(sourceId, remoteId)

    companion object {
        /** Keyset page size for full-index reads. 60k rows / 5000 = ~12 cheap queries. */
        const val INDEX_PAGE_SIZE = 5000
    }
}
