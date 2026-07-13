package com.photoframe.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.displayDataStore: DataStore<Preferences> by preferencesDataStore(name = "display_settings")

/**
 * DataStore-backed store for [DisplaySettings] (DESIGN §11). Emits a fresh snapshot on every
 * change so the running slideshow applies settings live, without restarting.
 */
class DisplaySettingsRepository(private val context: Context) {

    val settings: Flow<DisplaySettings> = context.displayDataStore.data.map { it.toDisplaySettings() }

    suspend fun update(transform: (DisplaySettings) -> DisplaySettings) {
        context.displayDataStore.edit { prefs ->
            val next = transform(prefs.toDisplaySettings())
            prefs[Keys.INTERVAL] = next.intervalSeconds.coerceIn(DisplaySettings.INTERVAL_RANGE)
            prefs[Keys.TRANSITION] = next.transition.name
            prefs[Keys.TRANSITION_SECONDS] =
                next.transitionSeconds.coerceIn(DisplaySettings.TRANSITION_SECONDS_RANGE)
            prefs[Keys.KEN_BURNS] = next.kenBurnsEnabled
            prefs[Keys.PORTRAIT_MODE] = next.portraitMode.name
            prefs[Keys.ORDER_MODE] = next.orderMode.name
            prefs[Keys.RECENCY_DAYS] = next.recencyWindowDays.coerceIn(DisplaySettings.RECENCY_WINDOW_DAYS_RANGE)
            prefs[Keys.CLOCK_ENABLED] = next.clockEnabled
            prefs[Keys.CLOCK_CORNER] = next.clockCorner.name
            prefs[Keys.CLOCK_SIZE] = next.clockSize.name
            prefs[Keys.CAPTION_ENABLED] = next.captionEnabled
        }
    }

    private fun Preferences.toDisplaySettings(): DisplaySettings {
        val d = DisplaySettings()
        return DisplaySettings(
            intervalSeconds = this[Keys.INTERVAL] ?: d.intervalSeconds,
            transition = enumOr(this[Keys.TRANSITION], d.transition),
            transitionSeconds = this[Keys.TRANSITION_SECONDS] ?: d.transitionSeconds,
            kenBurnsEnabled = this[Keys.KEN_BURNS] ?: d.kenBurnsEnabled,
            portraitMode = enumOr(this[Keys.PORTRAIT_MODE], d.portraitMode),
            orderMode = enumOr(this[Keys.ORDER_MODE], d.orderMode),
            recencyWindowDays = this[Keys.RECENCY_DAYS] ?: d.recencyWindowDays,
            clockEnabled = this[Keys.CLOCK_ENABLED] ?: d.clockEnabled,
            clockCorner = enumOr(this[Keys.CLOCK_CORNER], d.clockCorner),
            clockSize = enumOr(this[Keys.CLOCK_SIZE], d.clockSize),
            captionEnabled = this[Keys.CAPTION_ENABLED] ?: d.captionEnabled,
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

    private object Keys {
        val INTERVAL = intPreferencesKey("interval_seconds")
        val TRANSITION = stringPreferencesKey("transition")
        val TRANSITION_SECONDS = floatPreferencesKey("transition_seconds")
        val KEN_BURNS = booleanPreferencesKey("ken_burns")
        val PORTRAIT_MODE = stringPreferencesKey("portrait_mode")
        val ORDER_MODE = stringPreferencesKey("order_mode")
        val RECENCY_DAYS = intPreferencesKey("recency_days")
        val CLOCK_ENABLED = booleanPreferencesKey("clock_enabled")
        val CLOCK_CORNER = stringPreferencesKey("clock_corner")
        val CLOCK_SIZE = stringPreferencesKey("clock_size")
        val CAPTION_ENABLED = booleanPreferencesKey("caption_enabled")
    }
}
