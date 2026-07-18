package com.photoframe.settings

import com.photoframe.playlist.model.OrderMode
import com.photoframe.playlist.model.PortraitMode

/** Transition styles between slides (DESIGN §8). All are GPU-cheap alpha/scale/translate. */
enum class Transition { CROSSFADE, SLIDE, ZOOM_CROSSFADE, NONE }

/** Screen corners for overlay placement (DESIGN §9). */
enum class Corner {
    TOP_START, TOP_END, BOTTOM_START, BOTTOM_END;

    fun opposite(): Corner = when (this) {
        TOP_START -> BOTTOM_END
        TOP_END -> BOTTOM_START
        BOTTOM_START -> TOP_END
        BOTTOM_END -> TOP_START
    }

    val isTop: Boolean get() = this == TOP_START || this == TOP_END
    val isStart: Boolean get() = this == TOP_START || this == BOTTOM_START
}

/** Overlay font sizing — kitchen vs. bedside viewing distance (DESIGN §9). */
enum class FontSize { SMALL, MEDIUM, LARGE }

/**
 * All Display settings that shape the running slideshow (DESIGN §11). Immutable snapshot;
 * the settings repository emits a new instance on every change and the slideshow applies it
 * live without restarting.
 */
data class DisplaySettings(
    val intervalSeconds: Int = 30,
    val transition: Transition = Transition.CROSSFADE,
    val transitionSeconds: Float = 1.0f,
    val kenBurnsEnabled: Boolean = true,
    val portraitMode: PortraitMode = PortraitMode.AUTO,
    val orderMode: OrderMode = OrderMode.SHUFFLE,
    val recencyWindowDays: Int = 7,
    /** Roughly what share (%) of shown slides should be favorites (shuffle modes). 0 = no bias,
     *  100 = favorites only. Achieved by drawing each slide from favorites with this probability. */
    val favoriteSharePercent: Int = 20,
    val clockEnabled: Boolean = true,
    val clockCorner: Corner = Corner.TOP_START,
    val clockSize: FontSize = FontSize.MEDIUM,
    val captionEnabled: Boolean = true,
    /** When on, append one timestamped row per shown photo to a persistent playback log file
     *  (diagnostics — inspect for repeats). Default on for now. See [com.photoframe.diagnostics.PlaybackLog]. */
    val playbackLogEnabled: Boolean = true,
) {
    companion object {
        val INTERVAL_RANGE = 5..3600           // 5 s – 1 h (DESIGN §7)
        val TRANSITION_SECONDS_RANGE = 0.5f..3.0f
        val RECENCY_WINDOW_DAYS_RANGE = 1..90
        val FAVORITE_SHARE_PERCENT_RANGE = 0..100
    }
}
