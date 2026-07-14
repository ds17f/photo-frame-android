package com.photoframe

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.util.Log
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
