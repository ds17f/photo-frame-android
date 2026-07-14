package com.photoframe.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.scheduleDataStore: DataStore<Preferences> by preferencesDataStore(name = "schedule_settings")

/** DataStore-backed store for [ScheduleSettings] (DESIGN §10). */
class ScheduleSettingsRepository(private val context: Context) {

    val settings: Flow<ScheduleSettings> = context.scheduleDataStore.data.map { it.toSchedule() }

    suspend fun current(): ScheduleSettings = context.scheduleDataStore.data.first().toSchedule()

    suspend fun update(transform: (ScheduleSettings) -> ScheduleSettings) {
        context.scheduleDataStore.edit { prefs ->
            val next = transform(prefs.toSchedule())
            prefs[Keys.QUIET_ENABLED] = next.quietHoursEnabled
            prefs[Keys.QUIET_START] = next.quietStartMinute.coerceIn(0, 1439)
            prefs[Keys.QUIET_END] = next.quietEndMinute.coerceIn(0, 1439)
            prefs[Keys.CHARGING_FAKE_OFF] = next.chargingFakeOffEnabled
            prefs[Keys.START_ON_BOOT] = next.startOnBoot
        }
    }

    private fun Preferences.toSchedule(): ScheduleSettings {
        val d = ScheduleSettings()
        return ScheduleSettings(
            quietHoursEnabled = this[Keys.QUIET_ENABLED] ?: d.quietHoursEnabled,
            quietStartMinute = this[Keys.QUIET_START] ?: d.quietStartMinute,
            quietEndMinute = this[Keys.QUIET_END] ?: d.quietEndMinute,
            chargingFakeOffEnabled = this[Keys.CHARGING_FAKE_OFF] ?: d.chargingFakeOffEnabled,
            startOnBoot = this[Keys.START_ON_BOOT] ?: d.startOnBoot,
        )
    }

    private object Keys {
        val QUIET_ENABLED = booleanPreferencesKey("quiet_enabled")
        val QUIET_START = intPreferencesKey("quiet_start_minute")
        val QUIET_END = intPreferencesKey("quiet_end_minute")
        val CHARGING_FAKE_OFF = booleanPreferencesKey("charging_fake_off")
        val START_ON_BOOT = booleanPreferencesKey("start_on_boot")
    }
}
