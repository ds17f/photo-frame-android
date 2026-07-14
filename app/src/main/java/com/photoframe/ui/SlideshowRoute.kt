package com.photoframe.ui

import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photoframe.ui.controls.SlideshowControls
import com.photoframe.ui.diagnostics.DiagnosticsScreen
import com.photoframe.ui.overlay.CaptionOverlay
import com.photoframe.ui.overlay.ClockOverlay
import com.photoframe.ui.overlay.OverlayLayout
import com.photoframe.ui.model.UiPhoto
import com.photoframe.ui.model.UiSlide
import com.photoframe.ui.render.SlideRenderer
import com.photoframe.ui.settings.DisplaySettingsScreen
import com.photoframe.ui.settings.SettingsHomeScreen
import com.photoframe.ui.settings.SettingsViewModel
import com.photoframe.ui.settings.SyncSettingsScreen
import com.photoframe.ui.sources.FirstRunWelcome
import com.photoframe.ui.sources.ImmichSetupScreen
import com.photoframe.ui.sources.SourcesScreen
import com.photoframe.ui.sources.SourcesViewModel

private enum class Screen { SLIDESHOW, SETTINGS_HOME, DISPLAY, SOURCES, CACHE, DIAGNOSTICS }

/** Swipe distance (px) past which a horizontal drag counts as prev/next. */
private const val SWIPE_THRESHOLD = 120f

/**
 * Which photo sits under a long-press: the single photo, or the correct half of a split (left/
 * right on a landscape frame, top/bottom on a portrait frame — matching PairSlide's layout).
 */
private fun pickPhotoAt(
    slide: UiSlide?,
    offset: androidx.compose.ui.geometry.Offset,
    size: androidx.compose.ui.unit.IntSize,
    isLandscape: Boolean,
): com.photoframe.ui.model.UiPhoto? = when (slide) {
    is UiSlide.Single -> slide.photo
    is UiSlide.Pair -> if (isLandscape) {
        if (offset.x < size.width / 2f) slide.left else slide.right
    } else {
        if (offset.y < size.height / 2f) slide.left else slide.right
    }
    null -> null
}

/**
 * Top-level UI: the slideshow, the first-run wizard, and in-app navigation to the settings
 * section screens (DESIGN §11). The Immich add/edit flow overlays whichever screen is active,
 * driven by [SourcesViewModel.setup].
 *
 * @param onExit invoked when the user confirms leaving the frame (second back press).
 */
@Composable
fun SlideshowRoute(onExit: () -> Unit) {
    val slideshowVm: SlideshowViewModel = viewModel()
    val settingsVm: SettingsViewModel = viewModel()
    val sourcesVm: SourcesViewModel = viewModel()

    val state by slideshowVm.uiState.collectAsState()
    val settings by settingsVm.settings.collectAsState()
    val sources by sourcesVm.sources.collectAsState()
    val syncSettings by sourcesVm.syncSettings.collectAsState()
    val syncLog by sourcesVm.syncLog.collectAsState()
    val cacheUsage by sourcesVm.cacheUsageBytes.collectAsState()
    val firstRunNeeded by sourcesVm.firstRunNeeded.collectAsState()
    val setup by sourcesVm.setup.collectAsState()

    var screen by remember { mutableStateOf(Screen.SLIDESHOW) }
    var pendingDelete by remember { mutableStateOf<UiPhoto?>(null) }
    val context = LocalContext.current

    // Surface one-shot messages (delete results) as toasts.
    LaunchedEffect(Unit) {
        slideshowVm.events.collect { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // Keep the engine's pairing axis in sync with the live screen orientation (DESIGN §8).
    val orientation = LocalConfiguration.current.orientation
    LaunchedEffect(orientation) {
        slideshowVm.setScreenLandscape(orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    // The Immich add/edit flow takes over the screen whenever it's active.
    val activeSetup = setup
    if (activeSetup != null) {
        BackHandler { sourcesVm.cancelSetup() }
        ImmichSetupScreen(
            state = activeSetup,
            onBack = { sourcesVm.cancelSetup() },
            onName = sourcesVm::editName,
            onUrl = sourcesVm::editBaseUrl,
            onKey = sourcesVm::editApiKey,
            onTest = sourcesVm::testAndLoad,
            onToggleAlbum = sourcesVm::toggleAlbum,
            onTogglePerson = sourcesVm::togglePerson,
            onFavorites = sourcesVm::setFavorites,
            onWholeLibrary = sourcesVm::setWholeLibrary,
            onRollingWindow = sourcesVm::setRollingWindow,
            onSave = sourcesVm::save,
        )
        return
    }

    // First-run wizard: no sources configured yet AND nothing cached to show. (In debug builds
    // fixtures seed the cache, so the wizard yields to the sample slideshow.)
    if (firstRunNeeded == true && state.photoCount == 0 && !state.loading && screen == Screen.SLIDESHOW) {
        BackHandler { onExit() }
        FirstRunWelcome(onGetStarted = { sourcesVm.beginAddImmich() })
        return
    }

    when (screen) {
        Screen.SETTINGS_HOME -> {
            BackHandler { screen = Screen.SLIDESHOW }
            SettingsHomeScreen(
                onBack = { screen = Screen.SLIDESHOW },
                onSources = { screen = Screen.SOURCES },
                onDisplay = { screen = Screen.DISPLAY },
                onCache = { screen = Screen.CACHE },
                onDiagnostics = { screen = Screen.DIAGNOSTICS },
            )
        }

        Screen.DISPLAY -> {
            BackHandler { screen = Screen.SETTINGS_HOME }
            DisplaySettingsScreen(
                settings = settings,
                onUpdate = { transform -> settingsVm.update(transform) },
                onBack = { screen = Screen.SETTINGS_HOME },
            )
        }

        Screen.SOURCES -> {
            BackHandler { screen = Screen.SETTINGS_HOME }
            SourcesScreen(
                sources = sources,
                onBack = { screen = Screen.SETTINGS_HOME },
                onAddImmich = { sourcesVm.beginAddImmich() },
                onEdit = { sourcesVm.beginEditImmich(it) },
                onToggle = { id, enabled -> sourcesVm.setEnabled(id, enabled) },
                onDelete = { sourcesVm.delete(it) },
                onSyncNow = { sourcesVm.syncNow() },
            )
        }

        Screen.CACHE -> {
            BackHandler { screen = Screen.SETTINGS_HOME }
            SyncSettingsScreen(
                settings = syncSettings,
                cacheUsageBytes = cacheUsage,
                onBack = { screen = Screen.SETTINGS_HOME },
                onUpdate = { transform -> sourcesVm.updateSyncSettings(transform) },
            )
        }

        Screen.DIAGNOSTICS -> {
            BackHandler { screen = Screen.SETTINGS_HOME }
            DiagnosticsScreen(
                log = syncLog,
                cacheUsageBytes = cacheUsage,
                photoCount = state.photoCount,
                onBack = { screen = Screen.SETTINGS_HOME },
            )
        }

        Screen.SLIDESHOW -> {
            // First back shows controls; a second back while they're visible exits (§11).
            BackHandler {
                if (state.controlsVisible) {
                    Toast.makeText(context, "The frame will stop", Toast.LENGTH_SHORT).show()
                    onExit()
                } else {
                    slideshowVm.showControls()
                }
            }
            SlideshowContent(
                state = state,
                onTap = { slideshowVm.showControls() },
                onSwipeNext = { slideshowVm.next() },
                onSwipePrevious = { slideshowVm.previous() },
                onNext = { slideshowVm.next() },
                onPrevious = { slideshowVm.previous() },
                onTogglePause = { slideshowVm.togglePause() },
                onOpenSettings = { slideshowVm.hideControls(); screen = Screen.SETTINGS_HOME },
                onRequestDelete = { pendingDelete = it },
            )
            pendingDelete?.let { photo ->
                AlertDialog(
                    onDismissRequest = { pendingDelete = null },
                    title = { Text("Delete this photo?") },
                    text = { Text("It will be moved to the Immich trash — you can restore it there.") },
                    confirmButton = {
                        TextButton(onClick = { slideshowVm.deletePhoto(photo); pendingDelete = null }) {
                            Text("Delete")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
                    },
                )
            }
        }
    }
}

/**
 * Shown while the cache is still empty on first sync (DESIGN §6 first-run). A spinner + live count
 * give clear signs of life; as soon as the first photos land (incremental upsert), the real
 * slideshow replaces this.
 */
@Composable
private fun SyncingPlaceholder(loading: Boolean, photoCount: Int) {
    androidx.compose.foundation.layout.Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
    ) {
        androidx.compose.material3.CircularProgressIndicator(
            color = MaterialTheme.colorScheme.onBackground,
        )
        androidx.compose.material3.Text(
            text = when {
                loading -> "Loading…"
                photoCount > 0 -> "Preparing $photoCount photos…"
                else -> "Fetching your photos from Immich…\nThe first ones will appear shortly."
            },
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun SlideshowContent(
    state: SlideshowUiState,
    onTap: () -> Unit,
    onSwipeNext: () -> Unit,
    onSwipePrevious: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePause: () -> Unit,
    onOpenSettings: () -> Unit,
    onRequestDelete: (com.photoframe.ui.model.UiPhoto) -> Unit,
) {
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(state.slide, isLandscape) {
                detectTapGestures(
                    onTap = { onTap() },
                    // Long-press a photo (or one side of a split) to delete it (§11 extension).
                    onLongPress = { offset ->
                        pickPhotoAt(state.slide, offset, size, isLandscape)?.let(onRequestDelete)
                    },
                )
            }
            .pointerInput(Unit) {
                var dragTotal = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragTotal = 0f },
                    onHorizontalDrag = { _, delta -> dragTotal += delta },
                    onDragEnd = {
                        if (dragTotal <= -SWIPE_THRESHOLD) onSwipeNext()
                        else if (dragTotal >= SWIPE_THRESHOLD) onSwipePrevious()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val slide = state.slide
        if (slide == null) {
            SyncingPlaceholder(loading = state.loading, photoCount = state.photoCount)
        } else {
            SlideRenderer(slide = slide, settings = state.settings)

            if (state.settings.clockEnabled) {
                ClockOverlay(corner = state.settings.clockCorner, size = state.settings.clockSize)
            }
            if (state.settings.captionEnabled) {
                CaptionOverlay(
                    slide = slide,
                    corner = OverlayLayout.captionCorner(state.settings.clockCorner),
                )
            }
        }

        SlideshowControls(
            visible = state.controlsVisible,
            isPaused = state.isPaused,
            photoCount = state.photoCount,
            onPrevious = onPrevious,
            onNext = onNext,
            onTogglePause = onTogglePause,
            onOpenSettings = onOpenSettings,
        )
    }
}
