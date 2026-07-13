package com.photoframe.ui.render

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.photoframe.render.StackBlurTransformation
import java.io.File

/**
 * A blurred, screen-filling copy of the photo used behind fit-inside content so letterbox
 * areas read as a soft extension of the image rather than black bars (DESIGN §7/§8).
 *
 * API 31+: GPU [androidx.compose.ui.draw.blur] (RenderEffect). API 28–30 (Fire OS 7/8):
 * software [StackBlurTransformation] applied by Coil off the UI thread.
 */
@Composable
fun BlurFillBackground(path: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(modifier.fillMaxSize().background(Color.Black)) {
        val useGpuBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val request = ImageRequest.Builder(context)
            .data(File(path))
            .apply { if (!useGpuBlur) transformations(StackBlurTransformation()) }
            .crossfade(false)
            .build()
        val imageModifier = if (useGpuBlur) {
            Modifier.fillMaxSize().blur(48.dp)
        } else {
            Modifier.fillMaxSize()
        }
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = imageModifier,
        )
        // Slight scrim keeps the fitted photo and overlays legible over busy backgrounds.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)))
    }
}
