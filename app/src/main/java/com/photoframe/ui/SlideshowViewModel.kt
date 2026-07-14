package com.photoframe.ui

import android.app.Application
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
 * horizon, and skips slides whose photos aren't cached yet. The engine reads only from the local
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
            if (com.photoframe.BuildConfig.DEBUG) runCatching { fixtureImporter.importAll() }
            observeSettings()
            startIndexFeed()
            prefetcher.start()
            restartAdvanceLoop()
        }
    }

    private fun observeSettings() {
        settingsRepository.settings.onEach { settings ->
            engine.updateConfig(
                orderMode = settings.orderMode,
                portraitMode = settings.portraitMode,
                recencyWindow = Duration.ofDays(settings.recencyWindowDays.toLong()),
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
        photoRepository.observeRowCount().onEach { reloadSignal.trySend(Unit) }.launchIn(viewModelScope)
        prefetcher.cached.onEach { reloadSignal.trySend(Unit) }.launchIn(viewModelScope)
        viewModelScope.launch {
            while (isActive) {
                reloadSignal.receive()
                delay(RELOAD_DEBOUNCE_MS) // let a burst of changes collapse into one re-feed
                val refs = withContext(Dispatchers.IO) { photoRepository.loadFullIndex() }
                photoIndex = refs.associateBy { it.key }
                engine.setPhotos(refs.map { it.toPlaylistPhoto() })
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
            _events.send(
                if (result.isSuccess) "Added to favorites"
                else result.exceptionOrNull()?.message ?: "Favorite failed",
            )
        }
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

    /**
     * Advance to the next displayable slide. Slides whose photos aren't `READY` (not yet prefetched)
     * or whose file is missing (SD card pulled) are skipped, bounded by [MAX_SKIP] so an empty pool
     * can't spin forever; missing-file rows are repaired to `INDEXED` for the prefetcher to re-fetch.
     * With the prefetcher holding the horizon, skips are rare in steady state.
     */
    private fun advance(forward: Boolean) {
        slideJob?.cancel()
        slideJob = viewModelScope.launch {
            var attempts = 0
            while (attempts < MAX_SKIP) {
                attempts++
                val slide = (if (forward) engine.next() else engine.previous()) ?: break
                val ui = toDisplayableSlide(slide) ?: continue
                _uiState.value = _uiState.value.copy(slide = ui)
                markShown(ui)
                break
            }
            updateHorizon()
        }
    }

    /** Push the prefetcher's download horizon = the next [PhotoPrefetcher.HORIZON] upcoming photos. */
    private fun updateHorizon() {
        val keys = engine.peek(PhotoPrefetcher.HORIZON).map { PhotoKey.parse(it.id) }
        viewModelScope.launch { prefetcher.setHorizon(keys) }
    }

    private fun restartAdvanceLoop() {
        advanceJob?.cancel()
        if (_uiState.value.isPaused) return
        advanceJob = viewModelScope.launch {
            while (true) {
                val interval = _uiState.value.settings.intervalSeconds.coerceAtLeast(1)
                delay(interval * 1000L)
                if (!_uiState.value.isPaused) advance(forward = true)
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
                photoIndex[id]?.let { photoRepository.markShown(it.sourceId, it.remoteId, now) }
            }
        }
    }

    /**
     * Resolve an engine slide into a displayable UI slide, or null if it can't be shown (photos not
     * cached / files missing). For a pair with only one cached half, fall back to showing that half.
     */
    private suspend fun toDisplayableSlide(slide: Slide): UiSlide? = when (slide) {
        is Slide.Single -> readyEntity(slide.photo.id)?.let { UiSlide.Single(it.toUiPhoto()) }
        is Slide.Pair -> {
            val l = readyEntity(slide.left.id)
            val r = readyEntity(slide.right.id)
            when {
                l != null && r != null -> UiSlide.Pair(l.toUiPhoto(), r.toUiPhoto())
                l != null -> UiSlide.Single(l.toUiPhoto())
                r != null -> UiSlide.Single(r.toUiPhoto())
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
        val exists = withContext(Dispatchers.IO) { File(path).exists() }
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
        private const val CONTROLS_TIMEOUT_MS = 5_000L

        /** Bound on how many un-cached slides advance() may skip before giving up this tick. */
        private const val MAX_SKIP = 50

        /** Debounce window collapsing a burst of index changes into a single re-feed. */
        private const val RELOAD_DEBOUNCE_MS = 500L
    }
}
