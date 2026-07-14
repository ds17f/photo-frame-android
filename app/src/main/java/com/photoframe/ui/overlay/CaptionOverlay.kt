package com.photoframe.ui.overlay

import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoframe.settings.Corner
import com.photoframe.ui.model.UiPhoto
import com.photoframe.ui.model.UiSlide
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val MONTH_YEAR = DateTimeFormatter.ofPattern("MMMM yyyy")
private const val CAPTION_HOLD_MS = 5_000L

/**
 * Photo caption (DESIGN §9): each photo's month/year and location, fading in for the first ~5 s of
 * a slide. A single photo shows one caption in the corner opposite the clock; a **pair shows a
 * caption under each photo** (so both dates/locations are visible — not just when they happen to
 * match), laid out to mirror the side-by-side (landscape) or stacked (portrait) pairing.
 */
@Composable
fun CaptionOverlay(slide: UiSlide, corner: Corner, modifier: Modifier = Modifier) {
    when (slide) {
        is UiSlide.Single -> {
            val text = singleCaption(slide.photo) ?: return
            Box(modifier.fillMaxSize().padding(28.dp)) {
                FadingCaption(text, slide.key, Modifier.align(corner.toAlignment()))
            }
        }

        is UiSlide.Pair -> {
            val isLandscape =
                LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            val left = singleCaption(slide.left)
            val right = singleCaption(slide.right)
            if (isLandscape) {
                Row(modifier.fillMaxSize().padding(bottom = 20.dp)) {
                    CaptionCell(left, slide.key, Modifier.weight(1f))
                    CaptionCell(right, slide.key, Modifier.weight(1f))
                }
            } else {
                Column(modifier.fillMaxSize().padding(bottom = 8.dp)) {
                    CaptionCell(left, slide.key, Modifier.weight(1f))
                    CaptionCell(right, slide.key, Modifier.weight(1f))
                }
            }
        }
    }
}

/** One photo's caption within a pair, pinned to the bottom-center of its half. */
@Composable
private fun CaptionCell(text: String?, slideKey: String, modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        if (text != null) FadingCaption(text, slideKey, Modifier.padding(12.dp))
    }
}

@Composable
private fun FadingCaption(text: String, slideKey: String, modifier: Modifier) {
    var visible by remember(slideKey) { mutableStateOf(true) }
    LaunchedEffect(slideKey) {
        visible = true
        delay(CAPTION_HOLD_MS)
        visible = false
    }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 500),
        label = "captionAlpha",
    )
    Text(
        text = text,
        textAlign = TextAlign.Center,
        modifier = modifier.alpha(alpha),
        style = TextStyle(
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 20.sp,
            fontWeight = FontWeight.Normal,
            shadow = Shadow(Color.Black.copy(alpha = 0.75f), Offset(0f, 2f), 8f),
        ),
    )
}

private fun singleCaption(photo: UiPhoto): String? {
    val parts = listOfNotNull(photo.monthYear(), photo.locationLabel?.takeIf { it.isNotBlank() })
    return parts.joinToString("  ·  ").ifBlank { null }
}

private fun UiPhoto.monthYear(): String? =
    takenAt?.atZone(ZoneId.systemDefault())?.format(MONTH_YEAR)
