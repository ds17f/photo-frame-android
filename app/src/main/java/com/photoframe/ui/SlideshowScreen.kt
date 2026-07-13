package com.photoframe.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import kotlinx.coroutines.delay

/** Per-photo dwell time for the Phase 0 placeholder (real pacing arrives in Phase 1). */
private const val DWELL_MILLIS = 4_000L

/**
 * Phase 0 placeholder slideshow: cycles the bundled sample photos full-screen with a
 * hard-cut "transition". Decoding runs off the UI thread (Coil), fit-inside on black so
 * both portrait and landscape samples render correctly, and EXIF-rotated samples are
 * auto-oriented by the decoder.
 */
@Composable
fun SlideshowScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uris = remember { SamplePhotos.uris(context) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (uris.isEmpty()) {
            Text(
                text = "No sample photos bundled",
                color = MaterialTheme.colorScheme.onBackground,
            )
            return@Box
        }

        var index by remember { mutableIntStateOf(0) }

        LaunchedEffect(uris) {
            while (true) {
                delay(DWELL_MILLIS)
                index = (index + 1) % uris.size
            }
        }

        val painter = rememberAsyncImagePainter(
            model = ImageRequest.Builder(context)
                .data(uris[index])
                .crossfade(false) // hard cut in Phase 0
                .build(),
        )
        Image(
            painter = painter,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    }
}
