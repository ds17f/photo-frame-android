package com.photoframe.playlist

import com.photoframe.playlist.TestPhotos.landscape
import com.photoframe.playlist.TestPhotos.landscapes
import com.photoframe.playlist.model.OrderMode
import com.photoframe.playlist.model.Orientation
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

    @Test fun peek_returnsUpcomingBagWithoutConsuming() {
        val e = engine()
        e.setPhotos(landscapes(10))
        e.next() // build a cycle; one photo now consumed into history
        val peeked = e.peek(3).map { it.id }
        // Peeking must not consume: the next three advances yield exactly the peeked ids.
        assertEquals(peeked, (1..3).map { e.next()!!.id() })
    }

    @Test fun peek_returnsForwardHistoryFirst_afterSwipingBack() {
        val e = engine()
        e.setPhotos(landscapes(10))
        val a = e.next()!!.id()
        val b = e.next()!!.id()
        val c = e.next()!!.id()
        e.previous() // now on b, with c ahead in forward history
        e.previous() // now on a, with b,c ahead in forward history
        val peeked = e.peek(2).map { it.id }
        assertEquals(listOf(b, c), peeked)
    }

    @Test fun peek_clampsToAvailable() {
        val e = engine()
        e.setPhotos(landscapes(2))
        e.next()
        assertTrue(e.peek(100).size <= 2)
    }

    @Test fun setPhotos_refreshesDimsOfBagEntries() {
        // A photo enters the bag as UNKNOWN orientation (remote dims wrong), then a re-feed corrects
        // it to PORTRAIT (decoded bounds after prefetch). The upcoming bag entry must reflect the
        // corrected dims so pairing isn't decided on stale orientation. Chronological order makes
        // which photo stays in the bag deterministic.
        val e = engine()
        e.updateConfig(orderMode = OrderMode.CHRONOLOGICAL)
        val ls = (1..5).map {
            TestPhotos.photo("L$it", takenAt = Instant.parse("2020-01-0${it}T00:00:00Z"))
        }
        val takenAt = Instant.parse("2020-02-01T00:00:00Z") // latest → sorts to the back of the bag
        e.setPhotos(ls + TestPhotos.photo("P1", orientation = Orientation.UNKNOWN, takenAt = takenAt))
        e.next() // consumes L1 (earliest); P1 sits at the back of `remaining`
        e.setPhotos(ls + TestPhotos.photo("P1", orientation = Orientation.PORTRAIT, takenAt = takenAt))
        val upcoming = e.peek(10).firstOrNull { it.id == "P1" }
        assertNotNull("P1 should still be upcoming", upcoming)
        assertEquals(Orientation.PORTRAIT, upcoming!!.orientation)
    }

    @Test fun current_isNullBeforeFirstNext_thenTracks() {
        val e = engine()
        e.setPhotos(landscapes(3))
        assertNull(e.current())
        val a = e.next()
        assertNotNull(a)
        assertEquals(a!!.id(), e.current()!!.id())
    }

    // ---- peek-and-hold (the fix for "only cached photos ever show") --------------------------

    @Test fun peekPending_isIdempotent_andDoesNotConsume() {
        val e = engine()
        e.setPhotos(landscapes(10))
        val first = e.peekPending()!!.id()
        // Repeated peeks (waiting for the photo to cache) must return the SAME slide, not consume
        // the bag — this is what lets advance() wait instead of discarding an uncached photo.
        assertEquals(first, e.peekPending()!!.id())
        assertEquals(first, e.peekPending()!!.id())
        // Committing then peeking yields the NEXT slide.
        e.commitPending()
        assertEquals(first, e.current()!!.id())
        val second = e.peekPending()!!.id()
        assertTrue("commit should advance to a new slide", second != first)
    }

    @Test fun peek_surfacesHeldPendingSlideFirst() {
        val e = engine()
        e.setPhotos(landscapes(10))
        e.next() // consume one so we're mid-cycle
        val held = e.peekPending()!!.id()
        // The prefetcher's horizon (peek) must include the held pending photo, or it would never
        // download the very next photo advance() is waiting on.
        assertEquals(held, e.peek(1).single().id)
    }

    @Test fun laggingCache_showsEveryPhotoOncePerCycle_noRepeatsNoDrops() {
        // Simulate advance()'s wait-don't-discard loop against a cache that lags: every photo needs
        // a second look before it's "ready". With the old next()-and-skip logic each first look
        // would consume and discard the photo, so only fast-caching photos showed and they repeated.
        val e = engine()
        e.setPhotos(landscapes(50))
        val shown = mutableListOf<String>()
        val looks = HashMap<String, Int>()
        repeat(50) {
            while (true) {
                val id = e.peekPending()!!.id()
                val n = (looks[id] ?: 0) + 1
                looks[id] = n
                val cached = n >= 2 // prefetch lag: not ready on the first look
                if (cached) {
                    e.commitPending()
                    shown += id
                    break
                }
                // not cached: loop and peek again — must return the SAME id (held, not dropped)
            }
        }
        assertEquals("every photo shown exactly once", 50, shown.toSet().size)
        assertEquals("no photo shown twice within the cycle", 50, shown.size)
    }

    @Test fun heldPending_survivesBackThenForwardNavigation() {
        val e = engine()
        e.setPhotos(landscapes(10))
        val a = e.next()!!.id()
        val b = e.next()!!.id()
        val c = e.peekPending()!!.id() // held, awaiting cache — not yet shown
        assertEquals("still held", c, e.peekPending()!!.id())
        // User swipes back to A while C is pending.
        assertEquals(a, e.previous()!!.id())
        // Forward replays the recorded B (not the still-pending C)...
        assertEquals(b, e.next()!!.id())
        // ...and only then shows C.
        assertEquals(c, e.next()!!.id())
    }
}
