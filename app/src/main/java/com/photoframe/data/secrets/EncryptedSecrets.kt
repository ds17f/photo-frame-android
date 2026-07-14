package com.photoframe.data.secrets

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted key/value store for source secrets — Immich API keys, OAuth refresh tokens, and
 * shared-album URLs (DESIGN §12). Backed by AES-256 EncryptedSharedPreferences with a keystore
 * master key. Values are never logged; callers key by [immichApiKey] etc. so the naming stays
 * consistent across the app.
 */
class EncryptedSecrets(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val appContext = context.applicationContext
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun getImmichApiKey(sourceId: String): String? = prefs.getString(immichApiKeyName(sourceId), null)

    fun setImmichApiKey(sourceId: String, apiKey: String) {
        prefs.edit().putString(immichApiKeyName(sourceId), apiKey).apply()
    }

    /** Remove every secret owned by a source when it is deleted. */
    fun clearForSource(sourceId: String) {
        prefs.edit()
            .remove(immichApiKeyName(sourceId))
            .apply()
    }

    private fun immichApiKeyName(sourceId: String) = "immich_api_key.$sourceId"

    private companion object {
        const val FILE_NAME = "photoframe_secrets"
    }
}
