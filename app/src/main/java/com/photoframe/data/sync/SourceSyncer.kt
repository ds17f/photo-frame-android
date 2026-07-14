package com.photoframe.data.sync

import android.graphics.BitmapFactory
import android.util.Log
import com.photoframe.data.PhotoStatus
import com.photoframe.data.cache.PhotoCache
import com.photoframe.data.db.PhotoDao
import com.photoframe.data.db.PhotoEntity
import com.photoframe.data.source.DeletionPolicy
import com.photoframe.data.source.PhotoSource
import com.photoframe.data.source.RemotePhoto
import com.photoframe.data.source.SourceHealth
import com.photoframe.data.source.SourceHealthStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/** Outcome of syncing one source, for health recording + the diagnostics log (DESIGN §6/§11). */
data class SyncOutcome(
    val downloaded: Int,
    val deleted: Int,
    val failed: Int,
    /** Candidates left un-downloaded because the cache cap was reached (large libraries). */
    val skippedForBudget: Int,
    val health: SourceHealth,
    val photoCount: Int,
)

/**
 * Syncs one live [PhotoSource] into the cache (DESIGN §6): `listPhotos()` → [SyncDiff] against the
 * Room index → preview downloads → apply the deletion policy. Downloads are the only network work;
 * the resulting cache files are what the slideshow renders. A failure to reach the source is
 * reported as OFFLINE and leaves the existing cache untouched (offline resilience).
 *
 * Two properties matter for a huge library (the owner's is ~60k photos): downloads go
 * **newest-first** and stop once the cache **cap** is reached (never over-fetch what can't fit),
 * and each batch is **upserted immediately** so the slideshow lights up within seconds instead of
 * waiting for the whole sync (§7 "new photo appears soon").
 */
class SourceSyncer(
    private val photoDao: PhotoDao,
    private val cache: PhotoCache,
) {

    suspend fun sync(
        source: PhotoSource,
        deletionPolicy: DeletionPolicy,
        capBytes: Long,
    ): SyncOutcome = withContext(Dispatchers.IO) {
        val sourceId = source.id

        // 1. Desired set from the remote. Any failure here means the source is unreachable —
        //    the cache is left exactly as-is so the frame keeps playing (§6).
        val remote = try {
            source.listPhotos()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "listPhotos failed for $sourceId", e)
            return@withContext SyncOutcome(
                downloaded = 0, deleted = 0, failed = 0, skippedForBudget = 0,
                health = SourceHealth.offline(e.message ?: "Could not reach source"),
                photoCount = photoDao.countForSource(sourceId),
            )
        }

        // 2. Diff against the local index.
        val existing = photoDao.listBySource(sourceId)
        val existingByRemote = existing.associateBy { it.remoteId }
        val plan = SyncDiff.plan(
            remote = remote,
            existing = existing.map { ExistingPhoto(it.remoteId, it.contentHash) },
            deletionPolicy = deletionPolicy,
        )

        // 3. Deletions first (mirror policy) — frees cache budget before we download.
        if (plan.toDelete.isNotEmpty()) {
            plan.toDelete.forEach { remoteId ->
                existingByRemote[remoteId]?.let { runCatching { File(it.localPath).delete() } }
            }
            photoDao.deleteFromSource(sourceId, plan.toDelete)
        }

        // 4. Downloads — newest-first, small parallel batches, upserted per batch so the frame
        //    shows photos immediately, and stopped once the cache cap is reached.
        val candidates = plan.toDownload.sortedByDescending { it.takenAt ?: Instant.EPOCH }
        var usedBytes = photoDao.totalBytes()
        var downloaded = 0
        var failed = 0
        var processed = 0
        for (batch in candidates.chunked(DOWNLOAD_PARALLELISM)) {
            if (usedBytes >= capBytes) break // cache full — leave the rest for a future window/eviction
            val ok = coroutineScope {
                batch.map { photo ->
                    async {
                        runCatching { download(source, photo, existingByRemote[photo.remoteId]) }
                            .onFailure { if (it is CancellationException) throw it }
                            .getOrNull()
                    }
                }.awaitAll()
            }.filterNotNull()
            processed += batch.size
            failed += batch.size - ok.size
            if (ok.isNotEmpty()) {
                photoDao.upsertAll(ok) // incremental: visible to the slideshow right away
                downloaded += ok.size
                usedBytes += ok.sumOf { it.sizeBytes }
            }
        }
        val skippedForBudget = candidates.size - processed

        val resultingCount = photoDao.countForSource(sourceId)
        val health = when {
            failed > 0 && downloaded == 0 && resultingCount == 0 ->
                SourceHealth.degraded("$failed of ${candidates.size} downloads failed")
            skippedForBudget > 0 ->
                SourceHealth(SourceHealthStatus.OK, "Cache full — showing newest ${resultingCount} photos")
            failed > 0 -> SourceHealth(SourceHealthStatus.OK, "$failed downloads deferred to next sync")
            else -> SourceHealth.OK
        }
        SyncOutcome(
            downloaded = downloaded,
            deleted = plan.toDelete.size,
            failed = failed,
            skippedForBudget = skippedForBudget,
            health = health,
            photoCount = resultingCount,
        )
    }

    /** Download one preview into the cache and build its [PhotoEntity]. Returns null on empty write. */
    private suspend fun download(
        source: PhotoSource,
        photo: RemotePhoto,
        existing: PhotoEntity?,
    ): PhotoEntity? {
        val sink = CacheSink(cache, source.id, photo.remoteId)
        source.fetch(photo, sink)
        val result = sink.result ?: return null
        if (result.sizeBytes == 0L) {
            result.file.delete()
            return null
        }

        // Preview files come already EXIF-oriented, so decoded pixel bounds are the true display
        // dimensions — more reliable for portrait pairing than the source's reported dims.
        val (w, h) = decodeBounds(result.file)
        val now = System.currentTimeMillis()
        return PhotoEntity(
            sourceId = source.id,
            remoteId = photo.remoteId,
            localPath = result.file.absolutePath,
            takenAt = photo.takenAt?.toEpochMilli(),
            width = w ?: photo.width,
            height = h ?: photo.height,
            locationLabel = photo.locationLabel,
            addedAt = existing?.addedAt ?: now, // preserve original add time on re-download
            lastShownAt = existing?.lastShownAt,
            sizeBytes = result.sizeBytes,
            status = PhotoStatus.READY.name,
            contentHash = photo.contentHash,
        )
    }

    private fun decodeBounds(file: File): Pair<Int?, Int?> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return opts.outWidth.takeIf { it > 0 } to opts.outHeight.takeIf { it > 0 }
    }

    companion object {
        private const val TAG = "SourceSyncer"
        private const val DOWNLOAD_PARALLELISM = 3
    }
}
