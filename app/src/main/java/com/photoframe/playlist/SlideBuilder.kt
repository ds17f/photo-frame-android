package com.photoframe.playlist

import com.photoframe.playlist.model.PlaylistPhoto
import com.photoframe.playlist.model.PortraitMode
import com.photoframe.playlist.model.Slide

/**
 * Consumes photos from the head of the current cycle and groups them into [Slide]s,
 * applying portrait pairing (DESIGN §7).
 *
 * The frame is landscape; two portraits shown side-by-side use the screen far better than
 * one blur-filled portrait. When the next photo is a portrait we look ahead for another
 * portrait to pair it with; if none is close enough, the portrait is shown blur-filled.
 */
object SlideBuilder {

    /** `auto` mode looks at most this many items ahead for a pairing partner (DESIGN §7). */
    const val AUTO_LOOKAHEAD = 10

    /**
     * Removes the photo(s) forming the next slide from the front of [remaining] and returns
     * the slide, or null if [remaining] is empty. Mutates [remaining] so paired photos are
     * not shown again later in the same cycle.
     */
    fun nextSlide(remaining: MutableList<PlaylistPhoto>, mode: PortraitMode): Slide? {
        if (remaining.isEmpty()) return null
        val first = remaining.removeAt(0)

        if (!first.isPortrait || mode == PortraitMode.BLUR_FILL) {
            return Slide.Single(first)
        }

        // auto: only pair if a partner is within the lookahead window.
        // pair: prefer pairing — scan the whole remaining bag for a partner.
        val lookahead = when (mode) {
            PortraitMode.PAIR -> remaining.size
            else -> minOf(AUTO_LOOKAHEAD, remaining.size)
        }
        for (i in 0 until lookahead) {
            if (remaining[i].isPortrait) {
                val partner = remaining.removeAt(i)
                return Slide.Pair(first, partner)
            }
        }
        return Slide.Single(first) // no nearby partner → blur-fill this portrait
    }
}
