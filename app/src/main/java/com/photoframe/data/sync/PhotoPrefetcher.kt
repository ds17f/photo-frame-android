package com.photoframe.data.sync

import android.graphics.BitmapFactory
import android.util.Log
import com.photoframe.data.PhotoKey
import com.photoframe.data.PhotoStatus
import com.photoframe.data.cache.PhotoCache
import com.photoframe.data.db.PhotoDao
import com.photoframe.data.source.PhotoSource
import com.photoframe.data.source.RemotePhoto
import com.photoframe.data.source.SourceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Instant

/**
 * Keeps the next [HORIZON] upcoming playlist photos downloaded ahead of playback (streaming-cache
 * model, docs/STORAGE_PLAN.md). A playback-side coroutine (started/stopped with the slideshow), NOT
 * WorkManager — it has to track the playlist head with second-level latency.
 *
 * The [SlideshowViewModel] pushes the current horizon via [setHorizon] on every advance / feed
 * change; this fetches any horizon photo that is still `INDEXED` (up to [PARALLELISM] at once),
 * reusing the source's `fetch` + [CacheSink] path and the decode-bounds dim correction before
 * flipping the row to `READY`. **Failures skip with exponential backoff and never touch playback** —
 * a network outage just pauses pool turnover; the slideshow plays on from what's cached.
 *
 * Only [PrefetchPolicy] (scheduling/backoff) is unit-tested; this class is the thin I/O shell.
 */
class PhotoPrefetcher(
    private val photoDao: PhotoDao,
    private val cache: PhotoCache,
    private val sourceRepository: SourceRepository,
    private val factory: PhotoSourceFactory,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    /** Conflated wake signal: horizon changed, a download finished, or start() was called. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val stateMutex = Mutex()
    private var horizon: List<PhotoKey> = emptyList()
    private val inFlight = HashSet<PhotoKey>()
    private val failCount = HashMap<PhotoKey, Int>()
    private val readyAt = HashMap<PhotoKey, Long>()
    private val liveSources = HashMap<String, PhotoSource?>()

    /** Keeps the retained pool under its cap in steady state (set by the container to run the
     *  evictor). Invoked, throttled, after prefetch batches so the cap holds without waiting for
     *  the 30-min sync. Null until wired. */
    private var poolMaintainer: (suspend () -> Unit)? = null
    private var lastMaintainAt = 0L

    /** Emitted after a photo becomes READY, so the slideshow re-feeds and picks up corrected dims. */
    private val _cached = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val cached: SharedFlow<Unit> = _cached

    /** Wire the steady-state pool maintainer (the container runs the evictor here). */
    fun setPoolMaintainer(block: suspend () -> Unit) { poolMaintainer = block }

    fun start() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch { runLoop() }
        wake.trySend(Unit)
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    /** Update the upcoming-photo horizon (front = soonest) and wake the loop. */
    suspend fun setHorizon(keys: List<PhotoKey>) {
        stateMutex.withLock { horizon = keys }
        wake.trySend(Unit)
    }

    /** The set the evictor must not reclaim: horizon photos + in-flight downloads (as string keys). */
    suspend fun protectedKeys(): Set<String> = stateMutex.withLock {
        HashSet<String>(horizon.size + inFlight.size).apply {
            horizon.forEach { add(it.key) }
            inFlight.forEach { add(it.key) }
        }
    }

    private suspend fun runLoop() {
        while (scope.isActive) {
            val plan = planTick()
            plan.toFetch.forEach { launchFetch(it) }
            withTimeoutOrNull(plan.waitMs) { wake.receive() }
        }
    }

    private data class Tick(val toFetch: List<PhotoKey>, val waitMs: Long)

    private suspend fun planTick(): Tick {
        val snapshot = stateMutex.withLock { horizon.toList() }
        // Only INDEXED photos still need a file; re-check the DB each tick (sync/eviction move rows).
        val pending = snapshot.filter { needsFetch(it) }
        return stateMutex.withLock {
            val now = clock()
            val free = PARALLELISM - inFlight.size
            val toFetch = PrefetchPolicy.selectToFetch(pending, inFlight, readyAt, now, free)
            toFetch.forEach { inFlight.add(it) }
            val waitMs = when {
                toFetch.isNotEmpty() || inFlight.isNotEmpty() -> IDLE_WAIT_MS // completion sends a wake
                else -> PrefetchPolicy.nextWakeAt(pending, inFlight, readyAt, now)
                    ?.let { (it - now).coerceIn(MIN_WAIT_MS, IDLE_WAIT_MS) }
                    ?: IDLE_WAIT_MS
            }
            Tick(toFetch, waitMs)
        }
    }

    private suspend fun needsFetch(key: PhotoKey): Boolean {
        val row = photoDao.getByKey(key.sourceId, key.remoteId) ?: return false
        return row.status == PhotoStatus.INDEXED.name
    }

    private fun launchFetch(key: PhotoKey) {
        scope.launch {
            val ok = try {
                fetchOne(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "prefetch failed for ${key.key}", e)
                false
            }
            stateMutex.withLock {
                inFlight.remove(key)
                if (ok) {
                    failCount.remove(key)
                    readyAt.remove(key)
                } else {
                    val fc = (failCount[key] ?: 0) + 1
                    failCount[key] = fc
                    readyAt[key] = clock() + PrefetchPolicy.backoffMillis(fc)
                }
            }
            if (ok) {
                _cached.tryEmit(Unit)
                maybeMaintainPool()
            }
            wake.trySend(Unit)
        }
    }

    /**
     * Run the pool maintainer (eviction) after a cache, throttled — the pool grows one photo at a
     * time, so checking the cap every few seconds is enough to hold it. The evictor early-returns
     * cheaply when under the cap, and excludes this prefetcher's protected set.
     */
    private suspend fun maybeMaintainPool() {
        val maintain = poolMaintainer ?: return
        val due = stateMutex.withLock {
            val now = clock()
            if (now - lastMaintainAt >= MAINTAIN_INTERVAL_MS) {
                lastMaintainAt = now
                true
            } else {
                false
            }
        }
        if (due) runCatching { maintain() }
    }

    /** Download one photo's file into the cache and flip its row to READY. Returns success. */
    private suspend fun fetchOne(key: PhotoKey): Boolean {
        val row = photoDao.getByKey(key.sourceId, key.remoteId) ?: return false
        if (row.status == PhotoStatus.READY.name) return true // someone beat us to it
        val source = liveSource(key.sourceId) ?: return false

        val remotePhoto = RemotePhoto(
            remoteId = row.remoteId,
            takenAt = row.takenAt?.let(Instant::ofEpochMilli),
            width = row.width,
            height = row.height,
            locationLabel = row.locationLabel,
            contentHash = row.contentHash,
        )
        val sink = CacheSink(cache, key.sourceId, key.remoteId)
        source.fetch(remotePhoto, sink)
        val result = sink.result ?: return false
        if (result.sizeBytes == 0L) {
            result.file.delete()
            return false
        }
        // Preview files are EXIF-oriented, so decoded bounds are the true display dims — more
        // reliable for portrait pairing than the source's reported dims (gotcha: orientation).
        val (w, h) = decodeBounds(result.file)
        photoDao.markCached(
            sourceId = key.sourceId,
            remoteId = key.remoteId,
            localPath = result.file.absolutePath,
            sizeBytes = result.sizeBytes,
            width = w ?: row.width,
            height = h ?: row.height,
        )
        return true
    }

    private suspend fun liveSource(sourceId: String): PhotoSource? = stateMutex.withLock {
        if (liveSources.containsKey(sourceId)) return@withLock liveSources[sourceId]
        val configured = sourceRepository.byId(sourceId)
        val live = configured?.let { factory.create(it) }
        liveSources[sourceId] = live
        live
    }

    /** Forget cached live-source instances so a config/key change is picked up on next fetch. */
    suspend fun invalidateSources() = stateMutex.withLock { liveSources.clear() }

    private fun decodeBounds(file: File): Pair<Int?, Int?> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return opts.outWidth.takeIf { it > 0 } to opts.outHeight.takeIf { it > 0 }
    }

    companion object {
        private const val TAG = "PhotoPrefetcher"

        /** Keep this many upcoming photos READY or in-flight (covers portrait pairs comfortably). */
        const val HORIZON = 20

        /** Concurrent downloads (same as the old bulk-sync parallelism). */
        const val PARALLELISM = 3

        private const val IDLE_WAIT_MS = 30_000L
        private const val MIN_WAIT_MS = 200L

        /** Minimum spacing between steady-state cap-enforcement passes triggered by prefetch. */
        private const val MAINTAIN_INTERVAL_MS = 5_000L
    }
}
