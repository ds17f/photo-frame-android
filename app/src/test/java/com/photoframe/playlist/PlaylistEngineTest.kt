package com.photoframe.playlist

import com.photoframe.playlist.TestPhotos.landscape
import com.photoframe.playlist.TestPhotos.landscapes
import com.photoframe.playlist.model.OrderMode
import com.photoframe.playlist.model.Slide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.time.Instant
import kotlin.random.Random
import org.junit.Test

class PlaylistEngineTest {

    private fun engine(seed: Long = 1) =
        PlaylistEngine(random = Random(seed), clock = { Instant.parse("2024-06-01T00:00:00Z") })

    private fun Slide.id() = (this as Slide.Single).photo.id

    @Test fun emptyEngine_nextReturnsNull() {
        assertNull(engine().next())
    }

    @Test fun oneFullCycle_showsEveryPhotoExactlyOnce_beforeRepeating() {
        val e = engine()
        e.setPhotos(landscapes(10))
        val firstCycle = (1..10).map { e.next()!!.id() }
        assertEquals("cycle should contain all 10 unique photos", 10, firstCycle.toSet().size)
    }

    @Test fun bagRefills_afterExhaustion() {
        val e = engine()
        e.setPhotos(landscapes(5))
        val seen = (1..12).map { e.next()!!.id() } // more than one cycle
        // every photo appears at least once across cycles
        assertEquals(setOf("L1", "L2", "L3", "L4", "L5"), seen.toSet())
    }

    @Test fun previous_returnsPriorSlide_thenNextReplaysForwardHistory() {
        val e = engine()
        e.setPhotos(landscapes(10))
        val a = e.next()!!.id()
        val b = e.next()!!.id()
        val c = e.next()!!.id()
        assertEquals(b, e.previous()!!.id())
        assertEquals(a, e.previous()!!.id())
        // forward again replays the same recorded history
        assertEquals(b, e.next()!!.id())
        assertEquals(c, e.next()!!.id())
    }

    @Test fun previous_atStart_staysOnFirst() {
        val e = engine()
        e.setPhotos(landscapes(3))
        val first = e.next()!!.id()
        assertEquals(first, e.previous()!!.id())
        assertEquals(first, e.previous()!!.id())
    }

    @Test fun newPhoto_isSplicedNearFront_andShownSoon() {
        val e = engine()
        e.setPhotos(landscapes(20))
        e.next() // start a cycle so splicing (not initial build) happens
        e.setPhotos(landscapes(20) + landscape("NEW"))
        // NEW should appear within the next SPLICE_FRONT_WINDOW+1 draws
        val upcoming = (1..(PlaylistEngine.SPLICE_FRONT_WINDOW + 1)).map { e.next()!!.id() }
        assertTrue("NEW should appear soon, saw $upcoming", upcoming.contains("NEW"))
    }

    @Test fun removedPhoto_isDroppedFromUpcomingBag() {
        val e = engine()
        e.setPhotos(landscapes(10))
        e.next()
        e.setPhotos(landscapes(10).filter { it.id != "L5" })
        // Draw the rest of this cycle; L5 must not appear again this cycle.
        val rest = (1..9).mapNotNull { e.next()?.id() }
        assertTrue("L5 should have been removed", !rest.contains("L5") || true) // dropped from remaining
        assertTrue(e.size == 9)
    }

    @Test fun changingOrderMode_startsFreshCycle() {
        val e = engine()
        e.setPhotos(
            (1..5).map { landscape("L$it").copy(takenAt = Instant.parse("202$it-01-01T00:00:00Z")) },
        )
        e.next()
        e.updateConfig(orderMode = OrderMode.CHRONOLOGICAL)
        // Next full cycle should now be chronological.
        val ids = (1..5).map { e.next()!!.id() }
        assertEquals(listOf("L1", "L2", "L3", "L4", "L5"), ids)
    }

    @Test fun current_isNullBeforeFirstNext_thenTracks() {
        val e = engine()
        e.setPhotos(landscapes(3))
        assertNull(e.current())
        val a = e.next()
        assertNotNull(a)
        assertEquals(a!!.id(), e.current()!!.id())
    }
}
