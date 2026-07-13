package com.photoframe

import android.content.Context
import com.photoframe.data.PhotoRepository
import com.photoframe.data.cache.PhotoCache
import com.photoframe.data.db.PhotoFrameDatabase
import com.photoframe.data.fixture.FixtureImporter
import com.photoframe.settings.DisplaySettingsRepository

/**
 * Manual dependency container (no DI framework — a personal single-module app). Constructed
 * once by [PhotoFrameApp] and reached from ViewModels via the application context.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: PhotoFrameDatabase by lazy { PhotoFrameDatabase.get(appContext) }
    val photoRepository: PhotoRepository by lazy { PhotoRepository(database.photoDao()) }
    val cache: PhotoCache by lazy { PhotoCache(appContext) }
    val fixtureImporter: FixtureImporter by lazy {
        FixtureImporter(appContext, cache, photoRepository)
    }
    val displaySettingsRepository: DisplaySettingsRepository by lazy {
        DisplaySettingsRepository(appContext)
    }
}
