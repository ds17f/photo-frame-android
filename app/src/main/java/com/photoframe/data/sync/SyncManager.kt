package com.photoframe.data.sync

import android.util.Log
import com.photoframe.data.cache.PhotoCache
import com.photoframe.data.db.PhotoDao
import com.photoframe.data.db.SyncLogDao
import com.photoframe.data.db.SyncLogEntity
import com.photoframe.data.source.ConfiguredSource
import com.photoframe.data.source.SourceRepository
import com.photoframe.settings.QuietHours
import com.photoframe.settings.ScheduleSettingsRepository
import com.photoframe.settings.SyncSettingsRepository
import kotlinx.coroutines.CancellationException
import java.time.LocalTime

/** Roll-up of a full metadata-sync pass, for the caller (worker / sync-now button). */
data class SyncSummary(
    val sourcesSynced: Int,
    val indexed: Int,
    val reindexed: Int,
    val removed: Int,
)

/**
 * Orchestrates a sync pass across all enabled sources (DESIGN §6): build each live [com.photoframe
 * .data.source.PhotoSource] from its stored config, sync it, record health + the diagnostics log,
 * then enforce the cache cap once at the end. Reads only local config; the network work lives in
 * the sources. Safe to run from a WorkManager worker or a manual "Sync now".
 */
class SyncManager(
    private val sourceRepository: SourceRepository,
    private val photoDao: PhotoDao,
    private val syncLogDao: SyncLogDao,
    private val cache: PhotoCache,
    private val syncSettingsRepository: SyncSettingsRepository,
    private val scheduleSettingsRepository: ScheduleSettingsRepository,
    private val prefetcher: PhotoPrefetcher,
) {
    private val factory = PhotoSourceFactory(sourceRepository)
    private val syncer = MetadataSyncer(photoDao, cache)
    private val evictor = CacheEvictor(photoDao)

    /**
     * @param respectQuietHours when true (the periodic job), skip syncing during quiet hours
     *   (§10). A manual "Sync now" passes false so it always runs.
     */
    suspend fun syncAll(respectQuietHours: Boolean = true): SyncSummary {
        if (respectQuietHours && QuietHours.isQuiet(LocalTime.now(), scheduleSettingsRepository.current())) {
            log(null, SyncLogEntity.Level.INFO, "Sync skipped — quiet hours")
            return SyncSummary(0, 0, 0, 0)
        }
        val settings = syncSettingsRepository.current()
        val sources = sourceRepository.listEnabled()
        var indexed = 0
        var reindexed = 0
        var removed = 0
        var synced = 0

        for (source in sources) {
            val outcome = syncOne(source, settings.deletionPolicy) ?: continue
            synced++
            indexed += outcome.indexed
            reindexed += outcome.reindexed
            removed += outcome.removed
        }

        // Keep the retained pool under the cap. Exclude the prefetcher's horizon/in-flight set so a
        // freshly-fetched photo awaiting its turn isn't reclaimed out from under playback.
        val freed = evictor.enforce(settings.cacheCapBytes, prefetcher.protectedKeys())
        if (freed > 0) log(null, SyncLogEntity.Level.INFO, "Evicted ${freed / (1024 * 1024)} MB to stay under cap")
        syncLogDao.trimTo(LOG_RETENTION)

        return SyncSummary(synced, indexed, reindexed, removed)
    }

    private suspend fun syncOne(
        source: ConfiguredSource,
        deletionPolicy: com.photoframe.data.source.DeletionPolicy,
    ): SyncOutcome? {
        val live = factory.create(source)
        if (live == null) {
            log(source.id, SyncLogEntity.Level.WARN, "${source.displayName}: source type not supported yet")
            return null
        }
        return try {
            val outcome = syncer.sync(live, deletionPolicy)
            sourceRepository.recordSync(
                id = source.id,
                health = outcome.health,
                lastSyncAtMillis = System.currentTimeMillis(),
                photoCount = outcome.indexedCount,
            )
            log(
                source.id,
                SyncLogEntity.Level.INFO,
                "${source.displayName}: +${outcome.indexed} indexed" +
                    (if (outcome.reindexed > 0) " ~${outcome.reindexed} changed" else "") +
                    (if (outcome.removed > 0) " −${outcome.removed} removed" else "") +
                    " → ${outcome.indexedCount} indexed, ${outcome.cachedCount} cached [${outcome.health.status}]",
            )
            outcome
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "sync failed for ${source.id}", e)
            sourceRepository.recordSync(
                id = source.id,
                health = com.photoframe.data.source.SourceHealth.offline(e.message),
                lastSyncAtMillis = System.currentTimeMillis(),
                photoCount = photoDao.countForSource(source.id),
            )
            log(source.id, SyncLogEntity.Level.ERROR, "${source.displayName}: ${e.message ?: "sync failed"}")
            null
        }
    }

    private suspend fun log(sourceId: String?, level: SyncLogEntity.Level, message: String) {
        syncLogDao.insert(
            SyncLogEntity(
                createdAt = System.currentTimeMillis(),
                sourceId = sourceId,
                level = level.name,
                message = message,
            ),
        )
    }

    companion object {
        private const val TAG = "SyncManager"
        private const val LOG_RETENTION = 200
    }
}
