package com.photoframe

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import com.photoframe.data.db.PhotoFrameDatabase
import kotlin.system.exitProcess

/**
 * A frame on a shelf must self-heal (DESIGN §10). This default uncaught-exception handler
 * schedules an app relaunch via AlarmManager ~5 s out, then delegates to the previous handler so
 * the crash is still logged/reported before the process dies.
 */
class CrashRestartHandler(
    private val context: Context,
    private val delegate: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // The photo cache DB is disposable (it re-syncs from the source). If a crash looks like
        // on-disk SQLite corruption — e.g. a process kill landed mid-write during sync — drop the
        // DB before relaunching so the restart comes up clean instead of hitting the same bad
        // page forever. Without this, one corrupt page turns the self-heal restart into an
        // infinite crash loop.
        if (looksLikeDbCorruption(throwable)) {
            runCatching { dropCorruptDatabase() }
                .onFailure { Log.e(TAG, "Failed to drop corrupt database", it) }
        }
        runCatching { scheduleRestart() }
            .onFailure { Log.e(TAG, "Failed to schedule restart", it) }
        if (delegate != null) {
            delegate.uncaughtException(thread, throwable)
        } else {
            exitProcess(2)
        }
    }

    private fun scheduleRestart() {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return
        val pending = PendingIntent.getActivity(
            context,
            RESTART_REQUEST_CODE,
            launch,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.set(AlarmManager.RTC, System.currentTimeMillis() + RESTART_DELAY_MS, pending)
        Log.w(TAG, "Scheduled frame restart in ${RESTART_DELAY_MS}ms")
    }

    /**
     * True when the crash chain smells like SQLite/on-disk corruption: an explicit
     * [SQLiteException], or a CursorWindow read failure (`Couldn't read row N, col …`) which ART
     * surfaces as a plain [IllegalStateException] rather than a corruption exception.
     */
    private fun looksLikeDbCorruption(throwable: Throwable): Boolean {
        val seen = HashSet<Throwable>()
        var cause: Throwable? = throwable
        while (cause != null && seen.add(cause)) {
            if (cause is SQLiteException) return true
            val message = cause.message.orEmpty()
            if (message.contains("CursorWindow") ||
                message.contains("Couldn't read row") ||
                message.contains("database disk image is malformed") ||
                message.contains("file is not a database")
            ) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    /** Delete the cache DB and its -wal/-shm/-journal sidecars so Room rebuilds it on restart. */
    private fun dropCorruptDatabase() {
        val dbFile = context.getDatabasePath(PhotoFrameDatabase.NAME)
        val deleted = SQLiteDatabase.deleteDatabase(dbFile)
        Log.w(TAG, "Dropped corrupt cache DB (deleted=$deleted); restart will re-sync from source")
    }

    companion object {
        private const val TAG = "CrashRestartHandler"
        private const val RESTART_REQUEST_CODE = 1001
        private const val RESTART_DELAY_MS = 5_000L

        /** Install as the process-wide handler, chaining the existing one. */
        fun install(context: Context) {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(
                CrashRestartHandler(context.applicationContext, current),
            )
        }
    }
}
