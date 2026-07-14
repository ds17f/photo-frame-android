package com.photoframe.data

import com.photoframe.data.db.PhotoDao
import com.photoframe.data.db.PhotoEntity
import com.photoframe.data.db.PhotoRef
import kotlinx.coroutines.flow.Flow

/**
 * Read/write access to the cached photo index. The slideshow observes [observeReadyPhotos] as
 * lightweight [PhotoRef]s and resolves each on-screen photo through [photoByKey]; network sources
 * (Phase 2) write through [upsert]. Reads never touch the network — only the local Room index and
 * cache files.
 */
class PhotoRepository(private val photoDao: PhotoDao) {

    fun observeReadyPhotos(): Flow<List<PhotoRef>> =
        photoDao.observeRefsByStatus(PhotoStatus.READY.name)

    fun observeReadyCount(): Flow<Int> = photoDao.observeCount(PhotoStatus.READY.name)

    suspend fun photoByKey(sourceId: String, remoteId: String): PhotoEntity? =
        photoDao.getByKey(sourceId, remoteId)

    suspend fun upsert(photos: List<PhotoEntity>) = photoDao.upsertAll(photos)

    suspend fun markShown(sourceId: String, remoteId: String, shownAtMillis: Long) =
        photoDao.markShown(sourceId, remoteId, shownAtMillis)

    suspend fun remoteIdsForSource(sourceId: String): List<String> =
        photoDao.remoteIdsForSource(sourceId)

    suspend fun deleteFromSource(sourceId: String, remoteIds: List<String>) =
        photoDao.deleteFromSource(sourceId, remoteIds)
}
