package com.photoframe.ui.overlay

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
 * Photo caption (DESIGN §9): the photo's month/year and location, fading in for the first
 * ~5 s of each slide, placed in the corner opposite the clock. For a portrait pair the caption
 * shows only when both photos share a month/year, otherwise it is omitted.
 */
@Composable
fun CaptionOverlay(slide: UiSlide, corner: Corner, modifier: Modifier = Modifier) {
    val text = remember(slide.key) { captionFor(slide) } ?: return

    var visible by remember(slide.key) { mutableStateOf(true) }
    LaunchedEffect(slide.key) {
        visible = true
        delay(CAPTION_HOLD_MS)
        visible = false
    }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 500),
        label = "captionAlpha",
    )

    Box(modifier.fillMaxSize().padding(28.dp)) {
        Text(
            text = text,
            modifier = Modifier.align(corner.toAlignment()).alpha(alpha),
            style = TextStyle(
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 20.sp,
                fontWeight = FontWeight.Normal,
                shadow = Shadow(Color.Black.copy(alpha = 0.75f), Offset(0f, 2f), 8f),
            ),
        )
    }
}

private fun captionFor(slide: UiSlide): String? = when (slide) {
    is UiSlide.Single -> singleCaption(slide.photo)
    is UiSlide.Pair -> {
        val lm = slide.left.monthYear()
        val rm = slide.right.monthYear()
        if (lm != null && lm == rm) lm else null
    }
}

private fun singleCaption(photo: UiPhoto): String? {
    val parts = listOfNotNull(photo.monthYear(), photo.locationLabel?.takeIf { it.isNotBlank() })
    return parts.joinToString("  ·  ").ifBlank { null }
}

private fun UiPhoto.monthYear(): String? =
    takenAt?.atZone(ZoneId.systemDefault())?.format(MONTH_YEAR)
