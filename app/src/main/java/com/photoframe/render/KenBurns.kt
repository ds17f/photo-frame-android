package com.photoframe.render

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * A randomized Ken Burns pan/zoom for one photo (DESIGN §8). Scale and translation are
 * given as start→end values interpolated linearly over the photo's display duration.
 *
 * Translations are expressed as a fraction of the viewport size. Edge-safety invariant: at
 * scale `s` the image overflows the viewport by `(s − 1)`, so a translation fraction stays
 * within the image iff `|t| ≤ (s − 1) / 2`. Because both scale and translation animate
 * linearly, keeping each endpoint inside its own safe radius keeps every intermediate frame
 * safe too (a linear value bounded by a linear bound at both ends is bounded throughout) —
 * so the frame never reveals a letterbox edge.
 */
data class KenBurnsSpec(
    val startScale: Float,
    val endScale: Float,
    val startTransX: Float,
    val startTransY: Float,
    val endTransX: Float,
    val endTransY: Float,
) {
    fun scaleAt(t: Float): Float = lerp(startScale, endScale, t)
    fun transXAt(t: Float): Float = lerp(startTransX, endTransX, t)
    fun transYAt(t: Float): Float = lerp(startTransY, endTransY, t)

    /** Max |translation fraction| allowed at scale [s] without revealing an edge. */
    fun safeRadius(s: Float): Float = ((s - 1f) / 2f).coerceAtLeast(0f)

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)

    companion object {
        const val MIN_END_ZOOM = 1.06f
        const val MAX_END_ZOOM = 1.12f

        /** A gentle drift + slow zoom-in, edge-safe by construction. */
        fun generate(random: Random): KenBurnsSpec {
            val startScale = 1f + random.nextFloat() * 0.02f          // ~1.00–1.02
            val endScale = MIN_END_ZOOM + random.nextFloat() * (MAX_END_ZOOM - MIN_END_ZOOM)
            val angle = random.nextFloat() * (2f * Math.PI.toFloat())
            val dirX = cos(angle)
            val dirY = sin(angle)

            val rStart = ((startScale - 1f) / 2f).coerceAtLeast(0f)
            val rEnd = ((endScale - 1f) / 2f).coerceAtLeast(0f)

            // Drift from one side (at the smaller start radius) across toward the other,
            // ending at 90% of the larger end radius to leave a hair of margin.
            return KenBurnsSpec(
                startScale = startScale,
                endScale = endScale,
                startTransX = -dirX * rStart,
                startTransY = -dirY * rStart,
                endTransX = dirX * rEnd * 0.9f,
                endTransY = dirY * rEnd * 0.9f,
            )
        }
    }
}
