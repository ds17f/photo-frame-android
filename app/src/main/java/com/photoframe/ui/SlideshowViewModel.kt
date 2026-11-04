package com.photoframe.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photoframe.PhotoFrameApp
import com.photoframe.data.PhotoKey
import com.photoframe.data.PhotoRepository
import com.photoframe.data.PhotoStatus
import com.photoframe.data.db.PhotoEntity
import com.photoframe.data.db.PhotoRef
import com.photoframe.data.fixture.FixtureImporter
import com.photoframe.data.sync.PhotoPrefetcher
import com.photoframe.data.toPlaylistPhoto
import com.photoframe.playlist.PlaylistEngine
import com.photoframe.playlist.model.Orientation
import com.photoframe.playlist.model.Slide
import com.photoframe.settings.DisplaySettings
import com.photoframe.settings.DisplaySettingsRepository
import com.photoframe.ui.model.UiPhoto
import com.photoframe.ui.model.UiSlide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration

data class SlideshowUiState(
    val slide: UiSlide? = null,
    val settings: DisplaySettings = DisplaySettings(),
    /** Photos the frame can see — the full index (INDEXED + READY), not just cached ones. */
    val photoCount: Int = 0,
    val isPaused: Boolean = false,
    val controlsVisible: Boolean = false,
    val loading: Boolean = true,
)

/**
 * Drives the slideshow (streaming-cache model, docs/STORAGE_PLAN.md): seeds fixtures, feeds the
 * **full index** into the [PlaylistEngine] via keyset-paged reads (never one observed full-table
 * query — that overflows the CursorWindow), keeps the [PhotoPrefetcher] pointed at the upcoming
 * horizon, and keeps uncached slides queued while ready photos play. The engine reads only from the local
 * cache, so playback never blocks on the network (DESIGN §6).
 */
class SlideshowViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as PhotoFrameApp).container
    private val photoRepository: PhotoRepository = container.photoRepository
    private val settingsRepository: DisplaySettingsRepository = container.displaySettingsRepository
    private val fixtureImporter: FixtureImporter = container.fixtureImporter
    private val sourceRepository = container.sourceRepository
    private val photoSourceFactory = container.photoSourceFactory
    private val prefetcher: PhotoPrefetcher = container.photoPrefetcher
    private val playbackLog = com.photoframe.diagnostics.PlaybackLog(app)

    /** One-shot user messages (e.g. delete result) for the UI to surface as a toast. */
    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val engine = PlaylistEngine()

    /** id (sourceId/remoteId) -> lightweight ref for the whole index, for projecting engine slides.
     *  The heavy fields (localPath, caption) are fetched per on-screen photo via the repository. */
    private var photoIndex: Map<String, PhotoRef> = emptyMap()

    private val _uiState = MutableStateFlow(SlideshowUiState())
    val uiState: StateFlow<SlideshowUiState> = _uiState.asStateFlow()

    /** Conflated: any index change (sync add/remove, or a prefetch that cached a file) → re-feed. */
    private val reloadSignal = Channel<Unit>(Channel.CONFLATED)

    private var advanceJob: Job? = null
    private var slideJob: Job? = null
    private var controlsHideJob: Job? = null

    init {
        // Seed fixtures only in debug builds — a release "fresh install" must start empty so the
        // first-run wizard (Phase 2) drives the user to add a real Immich source (PLAN accept).
        viewModelScope.launch {
            observeSettings()
            startIndexFeed()
            prefetcher.start()
            restartAdvanceLoop()
            advance(forward = true) // local cached image first, even before the full index loads
            if (com.photoframe.BuildConfig.DEBUG) runCatching { fixtureImporter.importAll() }
        }
    }

    private fun observeSettings() {
        settingsRepository.settings.onEach { settings ->
            engine.updateConfig(
                orderMode = settings.orderMode,
                portraitMode = settings.portraitMode,
                recencyWindow = Duration.ofDays(settings.recencyWindowDays.toLong()),
                favoriteShare = settings.favoriteSharePercent / 100.0,
            )
            val prevInterval = _uiState.value.settings.intervalSeconds
            _uiState.value = _uiState.value.copy(settings = settings)
            // If the engine started a fresh cycle (order changed), refresh the shown slide.
            if (_uiState.value.slide == null) advance(forward = true)
            if (settings.intervalSeconds != prevInterval) restartAdvanceLoop()
        }.launchIn(viewModelScope)
    }

    /**
     * Feed the engine the full index, reloading (debounced/coalesced) whenever the index changes.
     * The reload reads refs via keyset paging off the main thread, then applies on the main thread
     * where the engine lives (it's single-threaded by contract).
     */
    private fun startIndexFeed() {
        // Reload only on a real structural change. Room invalidates queries table-scoped, so every
        // markShown/markCached/markEvicted write re-emits the row count with the SAME value —
        // distinctUntilChanged collapses those (a full 60k re-feed on each prefetch would thrash GC).
        photoRepository.observeRowCount()
            .distinctUntilChanged()
            .onEach { reloadSignal.trySend(Unit) }
            .launchIn(viewModelScope)
        // A completed prefetch doesn't change the index, so it must NOT trigger a full re-feed. It
        // only needs to nudge the very first slide up promptly once the pool has something to show.
        // Same-size metadata refreshes (favorites, orientation, replacements) also need a feed.
        container.syncManager.completed.onEach { reloadSignal.trySend(Unit) }.launchIn(viewModelScope)
        prefetcher.cached
            .onEach { if (_uiState.value.slide == null) advance(forward = true) }
            .launchIn(viewModelScope)
        viewModelScope.launch {
            while (isActive) {
                reloadSignal.receive()
                delay(RELOAD_DEBOUNCE_MS) // let a burst of changes collapse into one re-feed
                val refs = withContext(Dispatchers.IO) { photoRepository.loadFullIndex() }
                photoIndex = refs.associateBy { it.key }
                val firstFeed = engine.isEmpty
                engine.setPhotos(refs.map { it.toPlaylistPhoto() })
                if (firstFeed) {
                    // The instant startup photo must participate in the same no-repeat cycle.
                    _uiState.value.slide?.photos?.forEach { photo ->
                        photoIndex[photo.id]?.let { engine.recordCached(Slide.Single(it.toPlaylistPhoto())) }
                    }
                }
                Log.i(TAG, "feed: index=${refs.size} favorites=${refs.count { it.isFavorite }}")
                val hadSlide = _uiState.value.slide != null
                _uiState.value = _uiState.value.copy(photoCount = refs.size, loading = false)
                if (!hadSlide && refs.isNotEmpty()) advance(forward = true) else updateHorizon()
            }
        }
    }

    /**
     * Tell the engine which orientation is eligible for pairing based on the live screen
     * orientation: portraits pair only on a landscape frame; on a portrait frame landscapes
     * pair instead, so portraits are shown full-screen (never side-by-side) — DESIGN §8.
     */
    fun setScreenLandscape(isLandscape: Boolean) {
        val pairOrientation = if (isLandscape) Orientation.PORTRAIT else Orientation.LANDSCAPE
        if (pairOrientation == engine.pairOrientation) return
        engine.updateConfig(pairOrientation = pairOrientation)
    }

    // ---- Playback controls ---------------------------------------------------------------

    fun next() { advance(forward = true); restartAdvanceLoop() }

    fun previous() { advance(forward = false); restartAdvanceLoop() }

    /** Pause advancing while the frame is faked-off (quiet hours / unplugged); resume on wake. */
    fun setAsleep(asleep: Boolean) {
        if (asleep) advanceJob?.cancel() else restartAdvanceLoop()
    }

    fun togglePause() {
        val paused = !_uiState.value.isPaused
        _uiState.value = _uiState.value.copy(isPaused = paused)
        if (paused) advanceJob?.cancel() else restartAdvanceLoop()
    }

    /**
     * Delete a photo (single or one side of a pair) from its source — Immich moves it to trash —
     * then drop it from the local cache and advance off it. Surfaces the result via [events].
     */
    fun deletePhoto(photo: UiPhoto) {
        val ref = photoIndex[photo.id] ?: return
        viewModelScope.launch {
            val configured = sourceRepository.byId(ref.sourceId)
            val source = configured?.let { photoSourceFactory.create(it) }
            if (source == null || !source.canDelete) {
                _events.send("This source can't delete photos")
                return@launch
            }
            val result = runCatching { source.deleteRemote(ref.remoteId) }
            if (result.isSuccess) {
                // Grab the cache path before dropping the row, then delete the file off the DB row.
                val localPath = photoRepository.photoByKey(ref.sourceId, ref.remoteId)?.localPath
                if (localPath != null) {
                    withContext(Dispatchers.IO) { runCatching { File(localPath).delete() } }
                }
                photoRepository.deleteFromSource(ref.sourceId, listOf(ref.remoteId))
                if (currentSlideContains(photo.id)) advance(forward = true)
                _events.send("Moved to Immich trash")
            } else {
                _events.send(result.exceptionOrNull()?.message ?: "Delete failed")
            }
        }
    }

    /**
     * Hide a photo from the frame without deleting it — Immich adds it to the shared "hidden from
     * frame" album (excluded from future syncs). Drops it from the local index and advances off it
     * so it disappears immediately. Surfaces the result via [events].
     */
    fun hidePhoto(photo: UiPhoto) {
        val ref = photoIndex[photo.id] ?: return
        viewModelScope.launch {
            val configured = sourceRepository.byId(ref.sourceId)
            val source = configured?.let { photoSourceFactory.create(it) }
            if (source == null || !source.canHide) {
                _events.send("This source can't hide photos")
                return@launch
            }
            val result = runCatching { source.hideRemote(ref.remoteId) }
            if (result.isSuccess) {
                // Grab the cache path before dropping the row, then delete the file off the DB row.
                val localPath = photoRepository.photoByKey(ref.sourceId, ref.remoteId)?.localPath
                if (localPath != null) {
                    withContext(Dispatchers.IO) { runCatching { File(localPath).delete() } }
                }
                photoRepository.deleteFromSource(ref.sourceId, listOf(ref.remoteId))
                if (currentSlideContains(photo.id)) advance(forward = true)
                _events.send("Hidden from frame")
            } else {
                _events.send(result.exceptionOrNull()?.message ?: "Hide failed")
            }
        }
    }

    /** Mark a photo as a favorite at its source (Immich). Surfaces the result via [events]. */
    fun favoritePhoto(photo: UiPhoto) {
        val ref = photoIndex[photo.id] ?: return
        viewModelScope.launch {
            val configured = sourceRepository.byId(ref.sourceId)
            val source = configured?.let { photoSourceFactory.create(it) }
            if (source == null || !source.canFavorite) {
                _events.send("This source can't favorite photos")
                return@launch
            }
            val result = runCatching { source.favoriteRemote(ref.remoteId) }
            if (result.isSuccess) {
                // Reflect it locally right away, then re-feed so the favorites bias picks it up
                // without waiting for the next metadata sync.
                photoRepository.markFavorite(ref.sourceId, ref.remoteId, true)
                reloadSignal.trySend(Unit)
            }
            _events.send(
                if (result.isSuccess) "Added to favorites"
                else result.exceptionOrNull()?.message ?: "Favorite failed",
            )
        }
    }

    /**
     * The source's web-viewer URL for a photo (for the long-press QR code), or null if the source
     * has no durable per-photo URL. Only Immich does today — its web viewer opens in the Immich app
     * if installed; Picker baseURLs expire, so those photos have no link (the QR is simply omitted).
     */
    suspend fun photoWebUrl(photo: UiPhoto): String? {
        val ref = photoIndex[photo.id] ?: return null
        val base = sourceRepository.byId(ref.sourceId)?.immichConfig?.baseUrl ?: return null
        return "${base.trimEnd('/')}/photos/${ref.remoteId}"
    }

    private fun currentSlideContains(id: String): Boolean = when (val s = _uiState.value.slide) {
        is UiSlide.Single -> s.photo.id == id
        is UiSlide.Pair -> s.left.id == id || s.right.id == id
        null -> false
    }

    fun showControls() {
        _uiState.value = _uiState.value.copy(controlsVisible = true)
        controlsHideJob?.cancel()
        controlsHideJob = viewModelScope.launch {
            delay(CONTROLS_TIMEOUT_MS)
            _uiState.value = _uiState.value.copy(controlsVisible = false)
        }
    }

    fun hideControls() {
        controlsHideJob?.cancel()
        _uiState.value = _uiState.value.copy(controlsVisible = false)
    }

    /** Advance within the scheduled library window; backward replays actual shown history. */
    private fun advance(forward: Boolean) {
        slideJob?.cancel()
        slideJob = viewModelScope.launch {
            if (forward) advanceForward() else advanceBackward()
            updateHorizon()
        }
    }

    /**
     * Play a ready slide from the scheduled download window. Slow/broken targets stay queued
     * and protected for retry; they cannot hold the screen or disappear from this cycle.
     * During an outage, rotate the retained pool by persisted last-shown time.
     */
    private suspend fun advanceForward() {
        if (_uiState.value.slide == null && showStartupPhoto()) return
        engine.deferFailed(prefetcher.failedKeys())
        val next = engine.peekPending() ?: return
        updateHorizon()
        val candidates = listOf(next) + engine.scheduledSlides().filter { it != next }
        for (slide in candidates) {
            val ui = toDisplayableSlide(slide) ?: continue
            // A settings/index change during the database reads can invalidate this selection.
            if (slide !in engine.scheduledSlides() && slide != engine.peekPending()) continue
            if (slide == engine.peekPending()) engine.commitPending() else engine.commitScheduled(slide)
            showSlide(ui, "scheduled")
            return
        }
        val currentIds = _uiState.value.slide?.photos.orEmpty().map { it.id }
        val fallback = photoRepository.cachedFallback(currentIds) ?: return
        val ui = UiSlide.Single(fallback.toUiPhoto())
        engine.recordCached(Slide.Single(fallback.toPlaylistPhoto()))
        showSlide(ui, "cached fallback")
    }

    private suspend fun showSlide(ui: UiSlide, reason: String) {
        _uiState.value = _uiState.value.copy(slide = ui)
        markShown(ui)
        Log.i(TAG, "show ${ui.photos.joinToString { it.id.substringAfterLast('/').take(8) }} ($reason)")
        if (_uiState.value.settings.playbackLogEnabled) {
            for (photo in ui.photos) playbackLog.append(photoWebUrl(photo) ?: photo.id)
        }
    }

    private suspend fun showStartupPhoto(): Boolean {
        val photo = photoRepository.startupPhoto() ?: return false
        if (_uiState.value.slide != null) return true
        val ui = UiSlide.Single(photo.toUiPhoto())
        _uiState.value = _uiState.value.copy(
            slide = ui,
            photoCount = maxOf(1, _uiState.value.photoCount),
            loading = false,
        )
        markShown(ui)
        restartAdvanceLoop()
        Log.i(TAG, "startup: showing a cached photo while preparing the full playlist")
        if (_uiState.value.settings.playbackLogEnabled) playbackLog.append(photo.key)
        return true
    }

    /** Short photo ids for logs (drops the sourceId prefix). */
    private fun Slide.logId(): String = photos.joinToString("+") { it.id.substringAfterLast('/').take(8) }

    /** Whether any photo in the slide is a favorite (per the current index), for playback logs. */
    private fun Slide.anyFavorite(): Boolean = photos.any { photoIndex[it.id]?.isFavorite == true }

    private suspend fun advanceBackward() {
        val slide = engine.previous() ?: return
        val ui = toDisplayableSlide(slide) ?: return // an evicted back-history slide: stay put
        _uiState.value = _uiState.value.copy(slide = ui)
        markShown(ui)
    }

    /** Push the prefetcher's download horizon = the next [PhotoPrefetcher.HORIZON] upcoming photos. */
    private fun updateHorizon() {
        // A startup image can precede the engine's first draw. Prime its pending slide so its
        // full-library horizon starts downloading now, rather than after the first interval.
        engine.peekPending()
        val keys = engine.peek(PhotoPrefetcher.HORIZON).map { PhotoKey.parse(it.id) }
        val visible = _uiState.value.slide?.photos.orEmpty().map { PhotoKey.parse(it.id) }.toSet()
        viewModelScope.launch { prefetcher.setHorizon(keys, visible) }
    }

    private fun restartAdvanceLoop() {
        advanceJob?.cancel()
        if (_uiState.value.isPaused) return
        advanceJob = viewModelScope.launch {
            while (true) {
                val interval = _uiState.value.settings.intervalSeconds.coerceAtLeast(1)
                delay(interval * 1000L)
                if (!_uiState.value.isPaused && slideJob?.isActive != true) advance(forward = true)
            }
        }
    }

    private fun markShown(slide: UiSlide) {
        val now = System.currentTimeMillis()
        val ids = when (slide) {
            is UiSlide.Single -> listOf(slide.photo.id)
            is UiSlide.Pair -> listOf(slide.left.id, slide.right.id)
        }
        viewModelScope.launch {
            ids.forEach { id ->
                val key = PhotoKey.parse(id)
                photoRepository.markShown(key.sourceId, key.remoteId, now)
            }
        }
    }

    /**
     * Resolve an engine slide into a displayable UI slide, or null if it can't be shown (photos not
     * cached / files missing). Pairs commit only when both halves are ready, preserving coverage.
     */
    private suspend fun toDisplayableSlide(slide: Slide): UiSlide? = when (slide) {
        is Slide.Single -> readyEntity(slide.photo.id)?.let { UiSlide.Single(it.toUiPhoto()) }
        is Slide.Pair -> {
            val l = readyEntity(slide.left.id)
            val r = readyEntity(slide.right.id)
            when {
                l != null && r != null -> UiSlide.Pair(l.toUiPhoto(), r.toUiPhoto())
                else -> null
            }
        }
    }

    /**
     * The full row for [id] if it's cached and its file still exists, else null. A row that claims
     * READY but whose file is gone is repaired to INDEXED so the prefetcher re-fetches it.
     */
    private suspend fun readyEntity(id: String): PhotoEntity? {
        val ref = photoIndex[id] ?: return null
        val entity = photoRepository.photoByKey(ref.sourceId, ref.remoteId) ?: return null
        if (entity.status != PhotoStatus.READY.name) return null
        val path = entity.localPath ?: return null
        val exists = withContext(Dispatchers.IO) { File(path).let { it.isFile && it.length() > 0 } }
        if (!exists) {
            photoRepository.repairMissing(ref.sourceId, ref.remoteId)
            return null
        }
        return entity
    }

    private fun PhotoEntity.toUiPhoto(): UiPhoto {
        val pp = toPlaylistPhoto()
        return UiPhoto(
            id = pp.id,
            path = localPath.orEmpty(),
            orientation = pp.orientation,
            takenAt = pp.takenAt,
            locationLabel = locationLabel,
        )
    }

    override fun onCleared() {
        prefetcher.stop()
        super.onCleared()
    }

    companion object {
        private const val TAG = "Slideshow"
        private const val CONTROLS_TIMEOUT_MS = 5_000L

        /** Debounce window collapsing a burst of index changes (e.g. streamed sync pages that each
         *  insert rows) into a single re-feed, so first-run population doesn't thrash the engine. */
        private const val RELOAD_DEBOUNCE_MS = 1_500L
    }
}
