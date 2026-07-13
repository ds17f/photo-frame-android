package com.photoframe.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photoframe.ui.controls.SlideshowControls
import com.photoframe.ui.overlay.CaptionOverlay
import com.photoframe.ui.overlay.ClockOverlay
import com.photoframe.ui.overlay.OverlayLayout
import com.photoframe.ui.render.SlideRenderer
import com.photoframe.ui.settings.DisplaySettingsScreen
import com.photoframe.ui.settings.SettingsViewModel

private enum class Screen { SLIDESHOW, SETTINGS }

/** Swipe distance (px) past which a horizontal drag counts as prev/next. */
private const val SWIPE_THRESHOLD = 120f

/**
 * Top-level UI: the slideshow plus in-app navigation to the Display settings screen. Owns
 * gestures (tap → controls, swipe → prev/next) and back behavior (DESIGN §11).
 *
 * @param onExit invoked when the user confirms leaving the frame (second back press).
 */
@Composable
fun SlideshowRoute(onExit: () -> Unit) {
    val slideshowVm: SlideshowViewModel = viewModel()
    val settingsVm: SettingsViewModel = viewModel()
    val state by slideshowVm.uiState.collectAsState()
    val settings by settingsVm.settings.collectAsState()

    var screen by remember { mutableStateOf(Screen.SLIDESHOW) }
    val context = LocalContext.current

    when (screen) {
        Screen.SETTINGS -> {
            BackHandler { screen = Screen.SLIDESHOW }
            DisplaySettingsScreen(
                settings = settings,
                onUpdate = { transform -> settingsVm.update(transform) },
                onBack = { screen = Screen.SLIDESHOW },
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
                onOpenSettings = { slideshowVm.hideControls(); screen = Screen.SETTINGS },
            )
        }
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
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
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
            Text(
                text = if (state.loading) "Loading photos…" else "No photos yet",
                color = MaterialTheme.colorScheme.onBackground,
            )
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
