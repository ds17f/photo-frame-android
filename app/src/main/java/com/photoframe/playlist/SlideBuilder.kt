package com.photoframe.playlist

import com.photoframe.playlist.model.Orientation
import com.photoframe.playlist.model.PlaylistPhoto
import com.photoframe.playlist.model.PortraitMode
import com.photoframe.playlist.model.Slide

/**
 * Consumes photos from the head of the current cycle and groups them into [Slide]s,
 * applying orientation pairing (DESIGN §7/§8).
 *
 * Pairing fills the screen's long axis with two photos whose long axis is perpendicular to
 * it: on a **landscape** frame two portraits sit side-by-side; on a **portrait** frame two
 * landscapes stack. A photo already matching the screen's orientation fills it on its own, so
 * it is never paired — in particular, portraits are never paired when the frame is in portrait
 * orientation. [pairOrientation] is the orientation eligible for pairing (perpendicular to the
 * screen); the renderer decides row-vs-column from the live screen orientation.
 */
object SlideBuilder {

    /** `auto` mode looks at most this many items ahead for a pairing partner (DESIGN §7). */
    const val AUTO_LOOKAHEAD = 10

    /**
     * Removes the photo(s) forming the next slide from the front of [remaining] and returns
     * the slide, or null if [remaining] is empty. Mutates [remaining] so paired photos are
     * not shown again later in the same cycle.
     */
    fun nextSlide(
        remaining: MutableList<PlaylistPhoto>,
        mode: PortraitMode,
        pairOrientation: Orientation,
    ): Slide? {
        if (remaining.isEmpty()) return null
        val first = remaining.removeAt(0)

        val pairingEnabled = mode != PortraitMode.BLUR_FILL && pairOrientation != Orientation.UNKNOWN
        if (!pairingEnabled || first.orientation != pairOrientation) {
            return Slide.Single(first)
        }

        // auto: only pair if a partner is within the lookahead window.
        // pair: prefer pairing — scan the whole remaining bag for a partner.
        val lookahead = when (mode) {
            PortraitMode.PAIR -> remaining.size
            else -> minOf(AUTO_LOOKAHEAD, remaining.size)
        }
        for (i in 0 until lookahead) {
            if (remaining[i].orientation == pairOrientation) {
                val partner = remaining.removeAt(i)
                return Slide.Pair(first, partner)
            }
        }
        return Slide.Single(first) // no nearby partner → blur-fill this photo
    }
}
