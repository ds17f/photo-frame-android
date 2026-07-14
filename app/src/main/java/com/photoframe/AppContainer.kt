package com.photoframe

import android.content.Context
import com.photoframe.data.PhotoRepository
import com.photoframe.data.cache.PhotoCache
import com.photoframe.data.db.PhotoFrameDatabase
import com.photoframe.data.fixture.FixtureImporter
import com.photoframe.data.secrets.EncryptedSecrets
import com.photoframe.data.source.SourceRepository
import com.photoframe.data.sync.PhotoPrefetcher
import com.photoframe.data.sync.PhotoSourceFactory
import com.photoframe.data.sync.SyncManager
import com.photoframe.settings.DisplaySettingsRepository
import com.photoframe.settings.ScheduleSettingsRepository
import com.photoframe.settings.SyncSettingsRepository

/**
 * Manual dependency container (no DI framework — a personal single-module app). Constructed
 * once by [PhotoFrameApp] and reached from ViewModels via the application context.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: PhotoFrameDatabase by lazy { PhotoFrameDatabase.get(appContext) }
    val photoRepository: PhotoRepository by lazy { PhotoRepository(database.photoDao()) }
    val cache: PhotoCache by lazy { PhotoCache(appContext) }
    val secrets: EncryptedSecrets by lazy { EncryptedSecrets(appContext) }
    val sourceRepository: SourceRepository by lazy {
        SourceRepository(database.sourceDao(), secrets)
    }
    val photoSourceFactory: PhotoSourceFactory by lazy { PhotoSourceFactory(sourceRepository) }

    /**
     * Playback-side prefetcher (streaming-cache model): keeps the upcoming playlist horizon cached
     * ahead of playback. Singleton so the slideshow drives its horizon and the [SyncManager]'s
     * evictor can read its protected set. Started/stopped with the slideshow.
     */
    val photoPrefetcher: PhotoPrefetcher by lazy {
        PhotoPrefetcher(database.photoDao(), cache, sourceRepository, photoSourceFactory)
    }
    val fixtureImporter: FixtureImporter by lazy {
        FixtureImporter(appContext, cache, photoRepository)
    }
    val displaySettingsRepository: DisplaySettingsRepository by lazy {
        DisplaySettingsRepository(appContext)
    }
    val syncSettingsRepository: SyncSettingsRepository by lazy {
        SyncSettingsRepository(appContext)
    }
    val scheduleSettingsRepository: ScheduleSettingsRepository by lazy {
        ScheduleSettingsRepository(appContext)
    }
    val syncManager: SyncManager by lazy {
        SyncManager(
            sourceRepository = sourceRepository,
            photoDao = database.photoDao(),
            syncLogDao = database.syncLogDao(),
            cache = cache,
            syncSettingsRepository = syncSettingsRepository,
            scheduleSettingsRepository = scheduleSettingsRepository,
            prefetcher = photoPrefetcher,
        )
    }
}
