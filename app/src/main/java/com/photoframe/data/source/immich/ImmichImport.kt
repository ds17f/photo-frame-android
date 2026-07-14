package com.photoframe.data.source.immich

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One-shot Immich config hand-off over adb (dev convenience) — typing a long API key on a Fire
 * tablet keyboard is painful. Push a small JSON to the app's external files dir:
 *
 *   adb push immich_import.json /sdcard/Android/data/<applicationId>/files/immich_import.json
 *
 * On the next launch [consume] reads it (pre-filling the Immich setup flow) and **deletes it
 * immediately** — the key then lives only in EncryptedSharedPreferences (§12). The transient
 * file sits in app-private external storage and requires physical adb access to write.
 * See the `make immich-config` target, which generates this from `.env`.
 */
object ImmichImport {

    private const val FILE_NAME = "immich_import.json"
    private const val TAG = "ImmichImport"

    @Serializable
    data class Payload(
        val name: String = "Immich",
        val baseUrl: String,
        val apiKey: String,
    )

    /** Read + delete the import file if present. Returns null when absent or malformed. */
    fun consume(context: Context): Payload? {
        val file = File(context.getExternalFilesDir(null), FILE_NAME)
        if (!file.exists()) return null
        return try {
            Json { ignoreUnknownKeys = true }.decodeFromString<Payload>(file.readText())
                .takeIf { it.baseUrl.isNotBlank() && it.apiKey.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Malformed $FILE_NAME", e)
            null
        } finally {
            runCatching { file.delete() }
        }
    }
}
