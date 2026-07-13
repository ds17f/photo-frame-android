package com.photoframe.ui.overlay

import androidx.compose.ui.Alignment
import com.photoframe.settings.Corner

/** Maps a [Corner] to a Compose [Alignment] for placing an overlay in a full-screen Box. */
fun Corner.toAlignment(): Alignment = when (this) {
    Corner.TOP_START -> Alignment.TopStart
    Corner.TOP_END -> Alignment.TopEnd
    Corner.BOTTOM_START -> Alignment.BottomStart
    Corner.BOTTOM_END -> Alignment.BottomEnd
}
