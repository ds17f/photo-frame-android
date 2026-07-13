package com.photoframe.data

import com.photoframe.data.db.PhotoDao
import com.photoframe.data.db.PhotoEntity
import kotlinx.coroutines.flow.Flow

/**
 * Read/write access to the cached photo index. The slideshow observes [observeReadyPhotos];
 * network sources (Phase 2) write through [upsert]. Reads never touch the network — only the
 * local Room index and cache files.
 */
class PhotoRepository(private val photoDao: PhotoDao) {

    fun observeReadyPhotos(): Flow<List<PhotoEntity>> =
        photoDao.observeByStatus(PhotoStatus.READY.name)

    fun observeReadyCount(): Flow<Int> = photoDao.observeCount(PhotoStatus.READY.name)

    suspend fun listReadyPhotos(): List<PhotoEntity> = photoDao.listByStatus(PhotoStatus.READY.name)

    suspend fun upsert(photos: List<PhotoEntity>) = photoDao.upsertAll(photos)

    suspend fun markShown(photo: PhotoEntity, shownAtMillis: Long) =
        photoDao.markShown(photo.sourceId, photo.remoteId, shownAtMillis)

    suspend fun remoteIdsForSource(sourceId: String): List<String> =
        photoDao.remoteIdsForSource(sourceId)

    suspend fun deleteFromSource(sourceId: String, remoteIds: List<String>) =
        photoDao.deleteFromSource(sourceId, remoteIds)
}
