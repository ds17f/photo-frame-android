package com.photoframe.ui.controls

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Transient playback controls shown on tap / D-pad select (DESIGN §11): prev/next, play-pause,
 * a photo-count glance, per-photo actions, and the gear to open settings. Auto-hides after a
 * few seconds (driven by the caller).
 *
 * On a TV remote there is no touch: when [visible] flips true the first control grabs focus so
 * the D-pad can move Left/Right across the row and select with the centre button. [onPhotoActions]
 * is null when there is no current photo to act on (the button is then omitted).
 */
@Composable
fun SlideshowControls(
    visible: Boolean,
    isPaused: Boolean,
    photoCount: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onTogglePause: () -> Unit,
    onOpenSettings: () -> Unit,
    onPhotoActions: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val firstFocus = remember { FocusRequester() }
    // When the controls appear, pull D-pad focus to the first button so the remote can drive them.
    LaunchedEffect(visible) {
        if (visible) {
            // requestFocus can throw if the node isn't attached yet on the very first frame;
            // AnimatedVisibility composes the content as it enters, so guard defensively.
            runCatching { firstFocus.requestFocus() }
        }
    }
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.BottomCenter) {
            Row(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(32.dp))
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ControlIcon(Icons.AutoMirrored.Filled.NavigateBefore, "Previous", onPrevious, firstFocus)
                ControlIcon(
                    if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    if (isPaused) "Play" else "Pause",
                    onTogglePause,
                )
                ControlIcon(Icons.AutoMirrored.Filled.NavigateNext, "Next", onNext)
                Text(
                    text = "$photoCount photos",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                if (onPhotoActions != null) {
                    ControlIcon(Icons.Filled.MoreHoriz, "Photo actions", onPhotoActions)
                }
                ControlIcon(Icons.Filled.Settings, "Settings", onOpenSettings)
            }
        }
    }
}

@Composable
private fun ControlIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    // A visible focus ring is essential across the room on a TV — Material's default indication
    // is too subtle. Draw a bright ring around whichever control the D-pad is on.
    var focused by remember { mutableStateOf(false) }
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(56.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(CircleShape)
            .then(
                if (focused) {
                    Modifier
                        .background(Color.White.copy(alpha = 0.20f), CircleShape)
                        .border(2.dp, Color.White, CircleShape)
                } else {
                    Modifier
                }
            ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(32.dp),
        )
    }
}
