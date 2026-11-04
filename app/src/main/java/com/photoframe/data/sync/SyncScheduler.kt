package com.photoframe.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.photoframe.settings.SyncSettings
import java.util.concurrent.TimeUnit

/**
 * Schedules background sync (DESIGN §6): a unique periodic job on the configured interval and
 * network constraint, plus an expedited one-shot for "Sync now" and first-run. Call
 * [reschedule] whenever the interval or network setting changes.
 */
object SyncScheduler {

    private const val PERIODIC_WORK = "photo-sync-periodic"
    private const val ONESHOT_WORK = "photo-sync-now"

    fun reschedule(context: Context, settings: SyncSettings) {
        val interval = settings.syncIntervalMinutes
            .coerceIn(SyncSettings.INTERVAL_RANGE)
            .toLong()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(interval, TimeUnit.MINUTES)
            .setConstraints(constraints(settings))
            .setInputData(workDataOf(SyncWorker.KEY_RESPECT_QUIET to true)) // periodic pauses at night
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            // Keep the existing schedule but adopt new constraints/interval.
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** Kick an immediate sync (settings screen "Sync now", first-run wizard). */
    fun syncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.NONE) // user-initiated: run regardless of network metering
            .setInputData(workDataOf(SyncWorker.KEY_RESPECT_QUIET to false)) // manual: always run
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ONESHOT_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
    }

    private fun constraints(settings: SyncSettings): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(
                if (settings.requireUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED,
            )
            .build()
}
