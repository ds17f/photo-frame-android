package com.photoframe.ui.render

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.photoframe.render.KenBurnsSpec
import com.photoframe.settings.DisplaySettings
import com.photoframe.settings.Transition
import com.photoframe.ui.model.UiPhoto
import com.photoframe.ui.model.UiSlide
import kotlin.random.Random

/**
 * Renders the current [UiSlide] with the configured transition and Ken Burns effect
 * (DESIGN §8). All decoding is done by Coil off the UI thread. Transitions use only
 * alpha/scale/translate transforms to stay GPU-cheap on Fire hardware.
 */
@Composable
fun SlideRenderer(
    slide: UiSlide,
    settings: DisplaySettings,
    modifier: Modifier = Modifier,
) {
    val durationMs = (settings.transitionSeconds * 1000).toInt().coerceAtLeast(0)
    AnimatedContent(
        targetState = slide,
        modifier = modifier.fillMaxSize().background(Color.Black),
        contentKey = { it.key },
        transitionSpec = { transitionFor(settings.transition, durationMs) },
        label = "slide",
    ) { current ->
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (current) {
                is UiSlide.Single -> SinglePhoto(current.photo, settings)
                is UiSlide.Pair -> PairSlide(current.left, current.right)
            }
        }
    }
}

private fun transitionFor(transition: Transition, durationMs: Int) = when (transition) {
    Transition.NONE ->
        EnterTransition.None togetherWith ExitTransition.None
    Transition.CROSSFADE ->
        fadeIn(tween(durationMs)) togetherWith fadeOut(tween(durationMs))
    Transition.SLIDE ->
        (slideInHorizontally(tween(durationMs)) { it } + fadeIn(tween(durationMs))) togetherWith
            (slideOutHorizontally(tween(durationMs)) { -it } + fadeOut(tween(durationMs)))
    Transition.ZOOM_CROSSFADE ->
        (scaleIn(tween(durationMs), initialScale = 0.92f) + fadeIn(tween(durationMs))) togetherWith
            fadeOut(tween(durationMs))
}

@Composable
private fun SinglePhoto(photo: UiPhoto, settings: DisplaySettings) {
    val context = LocalContext.current
    BlurFillBackground(photo.path)

    // A fresh Ken Burns path per photo, driven by an Animatable so it interpolates on the frame
    // clock (~60fps). The old version stepped `progress` in 60 discrete `delay()` chunks — on a
    // 30s interval that's one update every 500ms (~2fps), which read as choppy.
    val spec = remember(photo.id) { KenBurnsSpec.generate(Random(photo.id.hashCode())) }
    val progress = remember(photo.id) { Animatable(0f) }
    LaunchedEffect(photo.id, settings.kenBurnsEnabled, settings.intervalSeconds) {
        progress.snapTo(0f)
        if (!settings.kenBurnsEnabled) return@LaunchedEffect
        val totalMs = settings.intervalSeconds.coerceAtLeast(1) * 1000
        progress.animateTo(1f, animationSpec = tween(durationMillis = totalMs, easing = LinearEasing))
    }

    val painter = rememberAsyncImagePainter(
        ImageRequest.Builder(context).data(java.io.File(photo.path)).crossfade(false).build(),
    )
    Image(
        painter = painter,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                if (settings.kenBurnsEnabled) {
                    val p = progress.value
                    val s = spec.scaleAt(p)
                    scaleX = s
                    scaleY = s
                    translationX = spec.transXAt(p) * size.width
                    translationY = spec.transYAt(p) * size.height
                }
            },
    )
}

/**
 * Two photos filling the screen's long axis: side-by-side (each fit to full height) when the
 * frame is landscape, stacked (each fit to full width) when portrait — DESIGN §7/§8. The
 * engine only produces a pair for the axis-appropriate orientation, so this just lays it out.
 */
@Composable
private fun PairSlide(first: UiPhoto, second: UiPhoto) {
    val context = LocalContext.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    BlurFillBackground(first.path)

    @Composable
    fun photo(p: UiPhoto, itemModifier: Modifier) {
        Image(
            painter = rememberAsyncImagePainter(
                ImageRequest.Builder(context).data(java.io.File(p.path)).crossfade(false).build(),
            ),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = itemModifier,
        )
    }

    if (isLandscape) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            photo(first, Modifier.fillMaxHeight())
            photo(second, Modifier.fillMaxHeight())
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            photo(first, Modifier.fillMaxWidth())
            photo(second, Modifier.fillMaxWidth())
        }
    }
}
