package com.photoframe.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photoframe.PhotoFrameApp
import com.photoframe.data.PhotoRepository
import com.photoframe.data.db.PhotoEntity
import com.photoframe.data.fixture.FixtureImporter
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration

data class SlideshowUiState(
    val slide: UiSlide? = null,
    val settings: DisplaySettings = DisplaySettings(),
    val photoCount: Int = 0,
    val isPaused: Boolean = false,
    val controlsVisible: Boolean = false,
    val loading: Boolean = true,
)

/**
 * Drives the slideshow: seeds fixtures, feeds the cache index into the [PlaylistEngine],
 * applies Display settings live, and advances slides on a timer. The engine reads only from
 * the local cache index, so this never blocks on the network (DESIGN §6).
 */
class SlideshowViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as PhotoFrameApp).container
    private val photoRepository: PhotoRepository = container.photoRepository
    private val settingsRepository: DisplaySettingsRepository = container.displaySettingsRepository
    private val fixtureImporter: FixtureImporter = container.fixtureImporter
    private val sourceRepository = container.sourceRepository
    private val photoSourceFactory = container.photoSourceFactory

    /** One-shot user messages (e.g. delete result) for the UI to surface as a toast. */
    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val engine = PlaylistEngine()

    /** id (sourceId/remoteId) -> entity, for projecting engine slides into UI slides. */
    private var photoIndex: Map<String, PhotoEntity> = emptyMap()

    private val _uiState = MutableStateFlow(SlideshowUiState())
    val uiState: StateFlow<SlideshowUiState> = _uiState.asStateFlow()

    private var advanceJob: Job? = null
    private var controlsHideJob: Job? = null

    init {
        // Seed fixtures only in debug builds — a release "fresh install" must start empty so the
        // first-run wizard (Phase 2) drives the user to add a real Immich source (PLAN accept).
        viewModelScope.launch {
            if (com.photoframe.BuildConfig.DEBUG) runCatching { fixtureImporter.importAll() }
            observeSettings()
            observePhotos()
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

    private fun observePhotos() {
        photoRepository.observeReadyPhotos().onEach { entities ->
            photoIndex = entities.associateBy { it.key }
            engine.setPhotos(entities.map { it.toPlaylistPhoto() })
            val hadSlide = _uiState.value.slide != null
            _uiState.value = _uiState.value.copy(photoCount = entities.size, loading = false)
            if (!hadSlide && entities.isNotEmpty()) advance(forward = true)
        }.launchIn(viewModelScope)
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
        val entity = photoIndex[photo.id] ?: return
        viewModelScope.launch {
            val configured = sourceRepository.byId(entity.sourceId)
            val source = configured?.let { photoSourceFactory.create(it) }
            if (source == null || !source.canDelete) {
                _events.send("This source can't delete photos")
                return@launch
            }
            val result = runCatching { source.deleteRemote(entity.remoteId) }
            if (result.isSuccess) {
                withContext(Dispatchers.IO) { runCatching { File(entity.localPath).delete() } }
                photoRepository.deleteFromSource(entity.sourceId, listOf(entity.remoteId))
                if (currentSlideContains(photo.id)) advance(forward = true)
                _events.send("Moved to Immich trash")
            } else {
                _events.send(result.exceptionOrNull()?.message ?: "Delete failed")
            }
        }
    }

    /** Mark a photo as a favorite at its source (Immich). Surfaces the result via [events]. */
    fun favoritePhoto(photo: UiPhoto) {
        val entity = photoIndex[photo.id] ?: return
        viewModelScope.launch {
            val configured = sourceRepository.byId(entity.sourceId)
            val source = configured?.let { photoSourceFactory.create(it) }
            if (source == null || !source.canFavorite) {
                _events.send("This source can't favorite photos")
                return@launch
            }
            val result = runCatching { source.favoriteRemote(entity.remoteId) }
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

    private fun advance(forward: Boolean) {
        val slide = if (forward) engine.next() else engine.previous()
        val ui = slide?.let(::toUiSlide) ?: return
        _uiState.value = _uiState.value.copy(slide = ui)
        markShown(slide)
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

    private fun markShown(slide: Slide) {
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            slide.photos.forEach { p ->
                photoIndex[p.id]?.let { photoRepository.markShown(it, now) }
            }
        }
    }

    private fun toUiSlide(slide: Slide): UiSlide? = when (slide) {
        is Slide.Single -> photoIndex[slide.photo.id]?.let { UiSlide.Single(it.toUiPhoto()) }
        is Slide.Pair -> {
            val l = photoIndex[slide.left.id]?.toUiPhoto()
            val r = photoIndex[slide.right.id]?.toUiPhoto()
            if (l != null && r != null) UiSlide.Pair(l, r) else l?.let { UiSlide.Single(it) }
        }
    }

    private fun PhotoEntity.toUiPhoto(): UiPhoto {
        val pp = toPlaylistPhoto()
        return UiPhoto(
            id = pp.id,
            path = localPath,
            orientation = pp.orientation,
            takenAt = pp.takenAt,
            locationLabel = locationLabel,
        )
    }

    companion object {
        private const val CONTROLS_TIMEOUT_MS = 5_000L
    }
}
