package com.photoframe

import android.content.Context
import com.photoframe.data.PhotoRepository
import com.photoframe.data.cache.PhotoCache
import com.photoframe.data.db.PhotoFrameDatabase
import com.photoframe.data.fixture.FixtureImporter
import com.photoframe.data.secrets.EncryptedSecrets
import com.photoframe.data.source.SourceRepository
import com.photoframe.data.sync.SyncManager
import com.photoframe.settings.DisplaySettingsRepository
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
    val fixtureImporter: FixtureImporter by lazy {
        FixtureImporter(appContext, cache, photoRepository)
    }
    val displaySettingsRepository: DisplaySettingsRepository by lazy {
        DisplaySettingsRepository(appContext)
    }
    val syncSettingsRepository: SyncSettingsRepository by lazy {
        SyncSettingsRepository(appContext)
    }
    val syncManager: SyncManager by lazy {
        SyncManager(
            sourceRepository = sourceRepository,
            photoDao = database.photoDao(),
            syncLogDao = database.syncLogDao(),
            cache = cache,
            syncSettingsRepository = syncSettingsRepository,
        )
    }
}
