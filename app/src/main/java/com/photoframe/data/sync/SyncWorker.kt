package com.photoframe.data.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.photoframe.PhotoFrameApp

/**
 * WorkManager entry point for background sync (DESIGN §6). Delegates to [SyncManager]; a failure
 * to reach a source is handled inside the manager (health/offline), so the worker only returns
 * retry for unexpected errors. The slideshow never depends on this running.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as PhotoFrameApp).container
        return try {
            val summary = container.syncManager.syncAll()
            Log.i(TAG, "Sync complete: $summary")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Sync pass failed", e)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val MAX_ATTEMPTS = 3
    }
}
