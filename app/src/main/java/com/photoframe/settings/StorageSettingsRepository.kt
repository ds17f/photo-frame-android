package com.photoframe.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.photoframe.data.cache.CacheLocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.storageDataStore: DataStore<Preferences> by preferencesDataStore(name = "storage_settings")

/** DataStore-backed store for [StorageSettings] (streaming-cache model). */
class StorageSettingsRepository(private val context: Context) {

    val settings: Flow<StorageSettings> = context.storageDataStore.data.map { it.toStorageSettings() }

    suspend fun current(): StorageSettings = context.storageDataStore.data.first().toStorageSettings()

    suspend fun setLocation(location: CacheLocation) {
        context.storageDataStore.edit { it[Keys.LOCATION] = location.name }
    }

    private fun Preferences.toStorageSettings(): StorageSettings = StorageSettings(
        location = this[Keys.LOCATION]
            ?.let { runCatching { CacheLocation.valueOf(it) }.getOrNull() }
            ?: CacheLocation.INTERNAL,
    )

    private object Keys {
        val LOCATION = stringPreferencesKey("cache_location")
    }
}
