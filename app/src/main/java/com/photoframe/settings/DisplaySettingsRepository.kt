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
            prefs[Keys.FAVORITE_SHARE] = next.favoriteSharePercent.coerceIn(DisplaySettings.FAVORITE_SHARE_PERCENT_RANGE)
            prefs[Keys.CLOCK_ENABLED] = next.clockEnabled
            prefs[Keys.CLOCK_CORNER] = next.clockCorner.name
            prefs[Keys.CLOCK_SIZE] = next.clockSize.name
            prefs[Keys.CAPTION_ENABLED] = next.captionEnabled
            prefs[Keys.DASHBOARD_MODE] = next.dashboardMode.name
            prefs[Keys.DASHBOARD_URL] = next.dashboardUrl.trim().let { url ->
                url.takeIf(::isValidDashboardUrl) ?: ""
            }
            prefs[Keys.DASHBOARD_DIM] = next.dashboardDimPercent.coerceIn(DisplaySettings.DASHBOARD_DIM_PERCENT_RANGE)
            prefs[Keys.PLAYBACK_LOG] = next.playbackLogEnabled
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
            favoriteSharePercent = this[Keys.FAVORITE_SHARE] ?: d.favoriteSharePercent,
            clockEnabled = this[Keys.CLOCK_ENABLED] ?: d.clockEnabled,
            clockCorner = enumOr(this[Keys.CLOCK_CORNER], d.clockCorner),
            clockSize = enumOr(this[Keys.CLOCK_SIZE], d.clockSize),
            captionEnabled = this[Keys.CAPTION_ENABLED] ?: d.captionEnabled,
            dashboardMode = enumOr(this[Keys.DASHBOARD_MODE], d.dashboardMode),
            dashboardUrl = this[Keys.DASHBOARD_URL]?.takeIf(::isValidDashboardUrl) ?: d.dashboardUrl,
            dashboardDimPercent = (this[Keys.DASHBOARD_DIM] ?: d.dashboardDimPercent)
                .coerceIn(DisplaySettings.DASHBOARD_DIM_PERCENT_RANGE),
            playbackLogEnabled = this[Keys.PLAYBACK_LOG] ?: d.playbackLogEnabled,
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
        val FAVORITE_SHARE = intPreferencesKey("favorite_share_percent")
        val CLOCK_ENABLED = booleanPreferencesKey("clock_enabled")
        val CLOCK_CORNER = stringPreferencesKey("clock_corner")
        val CLOCK_SIZE = stringPreferencesKey("clock_size")
        val CAPTION_ENABLED = booleanPreferencesKey("caption_enabled")
        val DASHBOARD_MODE = stringPreferencesKey("dashboard_mode")
        val DASHBOARD_URL = stringPreferencesKey("dashboard_url")
        val DASHBOARD_DIM = intPreferencesKey("dashboard_dim_percent")
        val PLAYBACK_LOG = booleanPreferencesKey("playback_log_enabled")
    }
}
