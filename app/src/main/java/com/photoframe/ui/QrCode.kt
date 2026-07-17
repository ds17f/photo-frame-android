package com.photoframe.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Pure, GMS-free QR generation via `com.google.zxing:core` (no Play Services). Encodes a URL into a
 * square black-on-white [ImageBitmap] the slideshow can display for the user to scan on their phone.
 */
object QrCode {

    /** Encode [content] as a [sizePx]-square QR [ImageBitmap]. Call off the main thread for large sizes. */
    fun generate(content: String, sizePx: Int): ImageBitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1) // tight quiet zone; we pad in the layout
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            val row = y * sizePx
            for (x in 0 until sizePx) {
                pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            .apply { setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx) }
            .asImageBitmap()
    }
}
