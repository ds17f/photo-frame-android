package com.photoframe.render

import android.graphics.Bitmap
import androidx.core.graphics.scale
import coil.size.Size
import coil.transform.Transformation
import kotlin.math.max

/**
 * RenderScript-free blur for the API 28–30 blur-fill fallback (DESIGN §7). Downscales the
 * bitmap hard, then applies a separable box blur (three passes ≈ Gaussian). Runs on Coil's
 * background dispatcher, so it never touches the UI thread. On API 31+ we use a GPU
 * [android.graphics.RenderEffect] instead (see BlurFillBackground).
 *
 * The heavy downscale does most of the perceptual blurring (the tiny bitmap is bilinearly
 * upscaled to fill the screen); the box passes smooth out the remaining blockiness.
 */
class StackBlurTransformation(
    private val radius: Int = 6,
    private val passes: Int = 3,
    private val downscaleTo: Int = 96,
) : Transformation {

    override val cacheKey: String = "${javaClass.name}-$radius-$passes-$downscaleTo"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val longest = max(input.width, input.height).coerceAtLeast(1)
        val scale = (downscaleTo.toFloat() / longest).coerceAtMost(1f)
        val w = max(1, (input.width * scale).toInt())
        val h = max(1, (input.height * scale).toInt())
        val small = if (scale < 1f) input.scale(w, h) else input.copy(Bitmap.Config.ARGB_8888, true)

        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        val r = radius.coerceIn(1, max(1, max(w, h) / 2))
        repeat(passes) {
            boxBlurHorizontal(pixels, w, h, r)
            boxBlurVertical(pixels, w, h, r)
        }
        small.setPixels(pixels, 0, w, 0, 0, w, h)
        return small
    }

    private fun boxBlurHorizontal(px: IntArray, w: Int, h: Int, radius: Int) {
        val div = radius * 2 + 1
        val line = IntArray(w)
        for (y in 0 until h) {
            val base = y * w
            var aSum = 0; var rSum = 0; var gSum = 0; var bSum = 0
            for (i in -radius..radius) {
                val c = px[base + i.coerceIn(0, w - 1)]
                aSum += (c ushr 24) and 0xff; rSum += (c shr 16) and 0xff
                gSum += (c shr 8) and 0xff; bSum += c and 0xff
            }
            for (x in 0 until w) {
                line[x] = ((aSum / div) shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
                val outC = px[base + (x - radius).coerceIn(0, w - 1)]
                val inC = px[base + (x + radius + 1).coerceIn(0, w - 1)]
                aSum += ((inC ushr 24) and 0xff) - ((outC ushr 24) and 0xff)
                rSum += ((inC shr 16) and 0xff) - ((outC shr 16) and 0xff)
                gSum += ((inC shr 8) and 0xff) - ((outC shr 8) and 0xff)
                bSum += (inC and 0xff) - (outC and 0xff)
            }
            System.arraycopy(line, 0, px, base, w)
        }
    }

    private fun boxBlurVertical(px: IntArray, w: Int, h: Int, radius: Int) {
        val div = radius * 2 + 1
        val col = IntArray(h)
        for (x in 0 until w) {
            var aSum = 0; var rSum = 0; var gSum = 0; var bSum = 0
            for (i in -radius..radius) {
                val c = px[i.coerceIn(0, h - 1) * w + x]
                aSum += (c ushr 24) and 0xff; rSum += (c shr 16) and 0xff
                gSum += (c shr 8) and 0xff; bSum += c and 0xff
            }
            for (y in 0 until h) {
                col[y] = ((aSum / div) shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
                val outC = px[(y - radius).coerceIn(0, h - 1) * w + x]
                val inC = px[(y + radius + 1).coerceIn(0, h - 1) * w + x]
                aSum += ((inC ushr 24) and 0xff) - ((outC ushr 24) and 0xff)
                rSum += ((inC shr 16) and 0xff) - ((outC shr 16) and 0xff)
                gSum += ((inC shr 8) and 0xff) - ((outC shr 8) and 0xff)
                bSum += (inC and 0xff) - (outC and 0xff)
            }
            for (y in 0 until h) px[y * w + x] = col[y]
        }
    }
}
