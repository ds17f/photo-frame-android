package com.photoframe.ui.overlay

import com.photoframe.settings.Corner

/**
 * Resolves overlay placement so the clock and caption never share a corner (DESIGN §9).
 * The clock's corner is user-chosen; the caption is placed in the opposite corner.
 */
object OverlayLayout {

    /** Corner for the caption given the (user-chosen) clock corner. */
    fun captionCorner(clockCorner: Corner): Corner = clockCorner.opposite()

    /** True if the two overlays would collide in the same corner (should never happen once
     *  [captionCorner] is used, but guards against future placement changes). */
    fun collides(clockCorner: Corner, captionCorner: Corner): Boolean =
        clockCorner == captionCorner
}
