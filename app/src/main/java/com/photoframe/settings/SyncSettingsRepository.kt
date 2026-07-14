package com.photoframe.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.photoframe.data.source.DeletionPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.syncDataStore: DataStore<Preferences> by preferencesDataStore(name = "sync_settings")

/** DataStore-backed store for [SyncSettings] (DESIGN §6/§11). */
class SyncSettingsRepository(private val context: Context) {

    val settings: Flow<SyncSettings> = context.syncDataStore.data.map { it.toSyncSettings() }

    suspend fun current(): SyncSettings = context.syncDataStore.data.first().toSyncSettings()

    suspend fun update(transform: (SyncSettings) -> SyncSettings) {
        context.syncDataStore.edit { prefs ->
            val next = transform(prefs.toSyncSettings())
            prefs[Keys.INTERVAL] = next.syncIntervalMinutes.coerceIn(SyncSettings.INTERVAL_RANGE)
            prefs[Keys.CACHE_CAP] = next.cacheCapBytes.coerceIn(SyncSettings.CACHE_CAP_RANGE)
            prefs[Keys.DELETION_POLICY] = next.deletionPolicy.name
            prefs[Keys.REQUIRE_UNMETERED] = next.requireUnmetered
        }
    }

    private fun Preferences.toSyncSettings(): SyncSettings {
        val d = SyncSettings()
        return SyncSettings(
            syncIntervalMinutes = this[Keys.INTERVAL] ?: d.syncIntervalMinutes,
            cacheCapBytes = this[Keys.CACHE_CAP] ?: d.cacheCapBytes,
            deletionPolicy = this[Keys.DELETION_POLICY]
                ?.let { runCatching { DeletionPolicy.valueOf(it) }.getOrNull() } ?: d.deletionPolicy,
            requireUnmetered = this[Keys.REQUIRE_UNMETERED] ?: d.requireUnmetered,
        )
    }

    private object Keys {
        val INTERVAL = intPreferencesKey("sync_interval_minutes")
        val CACHE_CAP = longPreferencesKey("cache_cap_bytes")
        val DELETION_POLICY = stringPreferencesKey("deletion_policy")
        val REQUIRE_UNMETERED = booleanPreferencesKey("require_unmetered")
    }
}
