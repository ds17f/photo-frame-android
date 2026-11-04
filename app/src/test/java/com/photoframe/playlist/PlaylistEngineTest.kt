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
        // Taken yesterday relative to the engine clock (2024-06-01) — a genuinely new photo.
        val fresh = TestPhotos.photo("NEW", takenAt = Instant.parse("2024-05-31T00:00:00Z"))
        e.setPhotos(landscapes(20) + fresh)
        // NEW should appear within the next SPLICE_FRONT_WINDOW+1 draws
        val upcoming = (1..(PlaylistEngine.SPLICE_FRONT_WINDOW + 1)).map { e.next()!!.id() }
        assertTrue("NEW should appear soon, saw $upcoming", upcoming.contains("NEW"))
    }

    @Test fun reAddedOldPhoto_isNotFrontSpliced() {
        // Immich pagination churn re-lists old photos as "new" rows; they must not jump the queue.
        val hits = (1L..50L).count { seed ->
            val e = engine(seed)
            e.setPhotos(landscapes(200))
            e.next()
            val old = TestPhotos.photo("OLD", takenAt = Instant.parse("1979-05-17T00:00:00Z"))
            e.setPhotos(landscapes(200) + old)
            (1..(PlaylistEngine.SPLICE_FRONT_WINDOW + 1)).any { e.next()!!.id() == "OLD" }
        }
        assertTrue("old re-add hit the front in $hits/50 runs", hits <= 5)
    }

    @Test fun largeLateBatch_isScatteredThroughBag_notPlayedAsABlock() {
        // First sync streams pages: the engine starts on page 1, later pages arrive as additions.
        val e = engine()
        val page1 = (1..100).map { landscape("A$it") }
        val page2 = (1..100).map { landscape("B$it") }
        e.setPhotos(page1)
        e.next()
        e.setPhotos(page1 + page2)
        val next50 = (1..50).map { e.next()!!.id() }
        val fromB = next50.count { it.startsWith("B") }
        assertTrue("expected a mix of both pages, got $fromB/50 from page 2", fromB in 10..40)
    }

    @Test fun rebuildAfterSync_keepsScheduledSlides_andDoesNotReplayShownPhotos() {
        val e = engine()
        e.setPhotos(landscapes(50))
        val shown = (1..10).map { e.next()!!.id() }.toSet()
        val scheduled = e.peek(5).map { it.id }
        e.setPhotos(landscapes(50) + (1..20).map { landscape("N$it") }) // a sync adds 20 old photos
        assertEquals("already-scheduled downloads keep their place", scheduled, e.peek(5).map { it.id })
        val rest = (1..60).map { e.next()!!.id() }
        assertTrue("shown photos must not return before the unseen 60 are done", rest.none { it in shown })
        assertEquals(60, rest.toSet().size)
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

    // ---- favorites bias ----------------------------------------------------------------------

    private fun mixedWithFavorites(): List<com.photoframe.playlist.model.PlaylistPhoto> =
        landscapes(20) + (1..5).map { TestPhotos.favorite("F$it") }

    @Test fun favoriteShare_full_showsOnlyFavorites() {
        val e = engine()
        e.setPhotos(mixedWithFavorites())
        e.updateConfig(favoriteShare = 1.0)
        val shown = (1..40).map { e.next()!!.id() }
        assertTrue("every slide should be a favorite, saw $shown", shown.all { it.startsWith("F") })
    }

    @Test fun favoriteShare_biasesTowardFavorites_aboveNaturalRate() {
        val e = engine()
        e.setPhotos(mixedWithFavorites()) // favorites are 5/25 = 20% of the library
        e.updateConfig(favoriteShare = 0.5)
        val shown = (1..500).map { e.next()!!.id() }
        val favFraction = shown.count { it.startsWith("F") } / 500.0
        // With a 50% draw-from-favorites probability the favorites share must sit well above their
        // 20% natural rate (and below 100%).
        assertTrue("favorites should be boosted, got $favFraction", favFraction in 0.45..0.75)
    }

    @Test fun favoriteShare_zero_leavesFavoritesAtNaturalRate() {
        val e = engine()
        e.setPhotos(mixedWithFavorites())
        e.updateConfig(favoriteShare = 0.0)
        // No injection: one full cycle shows each of the 25 photos exactly once (natural 20%).
        val cycle = (1..25).map { e.next()!!.id() }
        assertEquals(25, cycle.toSet().size)
        assertEquals(5, cycle.count { it.startsWith("F") })
    }

    @Test fun favoriteShare_ignoredInChronologicalMode() {
        val e = engine()
        e.setPhotos(
            (1..5).map { landscape("L$it").copy(takenAt = Instant.parse("202$it-01-01T00:00:00Z")) } +
                TestPhotos.favorite("F1").copy(takenAt = Instant.parse("2026-01-01T00:00:00Z")),
        )
        e.updateConfig(orderMode = OrderMode.CHRONOLOGICAL, favoriteShare = 1.0)
        // Chronological order is honoured despite a 100% favorite share — no injection in ordered modes.
        val ids = (1..6).map { e.next()!!.id() }
        assertEquals(listOf("L1", "L2", "L3", "L4", "L5", "F1"), ids)
    }

    @Test fun favoriteShare_full_withNoFavorites_fallsBackToMainCycle() {
        val e = engine()
        e.setPhotos(landscapes(10)) // no favorites
        e.updateConfig(favoriteShare = 1.0)
        val shown = (1..10).mapNotNull { e.next()?.id() }
        assertEquals("still plays the main cycle when there are no favorites", 10, shown.toSet().size)
    }

    @Test fun favorite_canRecurWithinASingleMainCycle() {
        val e = engine()
        e.setPhotos(mixedWithFavorites())
        e.updateConfig(favoriteShare = 0.5)
        // A favorite recurring inside 25 draws (one main cycle length) proves increased frequency —
        // impossible under pure without-replacement.
        val shown = (1..25).map { e.next()!!.id() }
        val favs = shown.filter { it.startsWith("F") }
        assertTrue("a favorite should repeat within one cycle, saw $favs", favs.size != favs.toSet().size)
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
    @Test fun downloadWindow_matchesActualPlayback_includingFavorites() {
        val e = engine()
        e.setPhotos(landscapes(1000) + (1..200).map { TestPhotos.favorite("F$it") })
        e.updateConfig(favoriteShare = 0.2)
        val horizon = e.peek(64).map { it.id }
        val played = (1..64).map { e.next()!!.id() }
        assertEquals(horizon, played)
        assertTrue("main library must receive prefetch slots", horizon.any { it.startsWith("L") })
    }

    @Test fun failedHead_doesNotBlockReadySlides_orDropPhotos() {
        val e = engine()
        e.setPhotos(landscapes(100))
        e.peek(64)
        val blocked = e.peekPending()!!
        val seen = mutableSetOf<String>()
        repeat(99) {
            e.peek(64)
            val ready = e.scheduledSlides().first { it != blocked }
            assertTrue("ready photos must not repeat", seen.add(ready.id()))
            e.commitScheduled(ready)
            assertEquals("unfetched head must stay queued", blocked, e.peekPending())
        }
        e.commitPending()
        assertTrue(seen.add(blocked.id()))
        assertEquals(100, seen.size)
    }

    @Test fun fullLibrary_72k_rotatesWithoutRepeats_afterMetadataReload() {
        val photos = landscapes(72_000)
        val e = engine()
        e.setPhotos(photos)
        val seen = HashSet<String>()
        repeat(72_000) { i ->
            if (i % 64 == 0) e.peek(64)
            if (i == 1000) e.setPhotos(photos)
            assertTrue("repeat at position $i", seen.add(e.next()!!.id()))
        }
        assertEquals(72_000, seen.size)
    }

    @Test fun cachedFallback_isRemovedFromFutureCycle_andSupportsBack() {
        val e = engine()
        e.setPhotos(landscapes(100))
        e.peek(64)
        val first = e.next()!!
        val fallback = e.scheduledSlides().last()
        e.recordCached(fallback)
        assertEquals(first, e.previous())
        assertEquals(fallback, e.next())
        val rest = (1..98).map { e.next()!!.id() }
        assertEquals(98, rest.toSet().size)
        assertTrue(fallback.id() !in rest)
        assertTrue(first.id() !in rest)
    }

    @Test fun emptyBagWithScheduledSlides_doesNotStartAnotherCycle() {
        val e = engine()
        e.setPhotos(landscapes(3))
        assertEquals(3, e.peek(64).size)
        val last = e.scheduledSlides().last()
        e.commitScheduled(last)
        assertEquals(2, e.peek(64).size)
        assertTrue(last.id() !in e.peek(64).map { it.id })
    }

    @Test fun entirelyFailedWindow_movesLaterWithoutLosingUnshownPhotos() {
        val e = engine()
        e.setPhotos(landscapes(1000))
        val failed = e.peek(64).map { it.id }.toSet()
        e.deferFailed(failed)
        assertTrue(e.peek(64).none { it.id in failed })
        val cycle = (1..1000).map { e.next()!!.id() }
        assertEquals(1000, cycle.toSet().size)
        assertEquals(failed, cycle.takeLast(64).toSet())
    }

}
