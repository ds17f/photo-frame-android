package com.photoframe.ui.overlay

import com.photoframe.settings.Corner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OverlayLayoutTest {

    @Test fun captionCorner_isAlwaysOppositeOfClock_andNeverCollides() {
        for (clock in Corner.entries) {
            val caption = OverlayLayout.captionCorner(clock)
            assertEquals(clock.opposite(), caption)
            assertFalse("clock and caption must not share a corner", OverlayLayout.collides(clock, caption))
        }
    }

    @Test fun opposite_roundTrips() {
        for (c in Corner.entries) {
            assertEquals(c, c.opposite().opposite())
        }
    }

    @Test fun opposite_flipsBothAxes() {
        assertEquals(Corner.BOTTOM_END, Corner.TOP_START.opposite())
        assertEquals(Corner.BOTTOM_START, Corner.TOP_END.opposite())
        assertEquals(Corner.TOP_END, Corner.BOTTOM_START.opposite())
        assertEquals(Corner.TOP_START, Corner.BOTTOM_END.opposite())
    }
}
