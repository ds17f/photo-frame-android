package com.photoframe

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import com.photoframe.data.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point. Provides a conservatively-sized Coil [ImageLoader] — Fire HD
 * devices have only 2–3 GB RAM (DESIGN.md §3), so the in-memory bitmap cache is capped
 * well below Coil's default 25% of app memory. Also installs the crash-restart self-heal
 * (§10) and ensures periodic background sync is scheduled (§6).
 */
class PhotoFrameApp : Application(), ImageLoaderFactory {

    /** App-wide dependency container; reached from ViewModels via the application context. */
    val container: AppContainer by lazy { AppContainer(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        CrashRestartHandler.install(this)
        // On every launch (boot, crash-restart, cold start): ensure the periodic job exists, and
        // kick an immediate catch-up sync ONLY if a source is stale — never synced, or last synced
        // longer ago than the sync interval (i.e. the frame was off and the periodic job missed a
        // run). A full metadata listing over a large library is expensive (§6), so re-running it on
        // every warm launch would jank startup for no benefit; the periodic job keeps it fresh.
        appScope.launch {
            runCatching {
                val settings = container.syncSettingsRepository.current()
                SyncScheduler.reschedule(this@PhotoFrameApp, settings)
                val sources = container.sourceRepository.listEnabled()
                val staleBefore = System.currentTimeMillis() - settings.syncIntervalMinutes * 60_000L
                val needsCatchUp = sources.any { s ->
                    val last = s.lastSyncAt?.toEpochMilli()
                    last == null || last < staleBefore
                }
                if (needsCatchUp) SyncScheduler.syncNow(this@PhotoFrameApp)
            }
        }
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.15)
                    .build()
            }
            // Downsampling to the target view size happens per-request; we never hold
            // full-resolution originals in memory.
            .allowRgb565(true)
            .build()
}
