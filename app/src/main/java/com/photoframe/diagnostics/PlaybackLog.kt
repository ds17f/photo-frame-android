package com.photoframe.diagnostics

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Appends one timestamped row per shown photo to a persistent TSV file, so the playback history can
 * be pulled off the tablet and inspected for repeats.
 *
 * Append-only and reboot-safe: it's a plain file in the app's external files dir, so it survives app
 * restarts and device reboots and simply keeps growing (no ring-buffer trim — the whole point is a
 * complete multi-day record). Gated by the "Log shown photos" Display setting (default on).
 *
 * File: `<externalFilesDir>/playback-log.tsv` — pull with
 *   `adb pull /sdcard/Android/data/com.photoframe.debug/files/playback-log.tsv`
 * Row:  `<ISO-8601 timestamp>\t<url>` (header row written once, on file creation).
 */
class PlaybackLog(context: Context) {

    private val appContext = context.applicationContext
    private val mutex = Mutex()

    /** The log file. External files dir when available (adb-pullable), else app-private files dir. */
    val file: File
        get() = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, FILE_NAME)

    /** Append one `timestamp\turl` row. Serialized on the IO dispatcher; never throws to the caller. */
    suspend fun append(url: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val f = file
            val header = !f.exists()
            val ts = TIME_FORMAT.format(OffsetDateTime.now())
            runCatching {
                f.parentFile?.mkdirs()
                f.appendText(buildString {
                    if (header) append(HEADER).append('\n')
                    append(ts).append('\t').append(url).append('\n')
                })
            }.onFailure { Log.w(TAG, "playback log append failed", it) }
        }
    }

    private companion object {
        const val TAG = "PlaybackLog"
        const val FILE_NAME = "playback-log.tsv"
        const val HEADER = "timestamp\turl"
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    }
}
