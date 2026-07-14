package com.photoframe.data.sync

import com.photoframe.data.PhotoKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefetchPolicyTest {

    private fun key(id: String) = PhotoKey("s", id)

    // ---- backoff ---------------------------------------------------------------------------

    @Test fun backoff_zeroForNoFailures() {
        assertEquals(0L, PrefetchPolicy.backoffMillis(0))
        assertEquals(0L, PrefetchPolicy.backoffMillis(-1))
    }

    @Test fun backoff_doublesThenCaps() {
        assertEquals(2_000L, PrefetchPolicy.backoffMillis(1))
        assertEquals(4_000L, PrefetchPolicy.backoffMillis(2))
        assertEquals(8_000L, PrefetchPolicy.backoffMillis(3))
        // caps at 5 min and never overflows for large counts
        assertEquals(PrefetchPolicy.MAX_BACKOFF_MS, PrefetchPolicy.backoffMillis(100))
        assertTrue(PrefetchPolicy.backoffMillis(60) <= PrefetchPolicy.MAX_BACKOFF_MS)
    }

    // ---- selection -------------------------------------------------------------------------

    @Test fun select_takesFrontFirstUpToFreeSlots() {
        val horizon = listOf(key("a"), key("b"), key("c"))
        val chosen = PrefetchPolicy.selectToFetch(
            horizon, inFlight = emptySet(), readyAt = emptyMap(), now = 0, freeSlots = 2,
        )
        assertEquals(listOf(key("a"), key("b")), chosen)
    }

    @Test fun select_skipsInFlightAndDedupes() {
        val horizon = listOf(key("a"), key("a"), key("b"))
        val chosen = PrefetchPolicy.selectToFetch(
            horizon, inFlight = setOf(key("b")), readyAt = emptyMap(), now = 0, freeSlots = 5,
        )
        assertEquals(listOf(key("a")), chosen)
    }

    @Test fun select_skipsKeysStillInBackoff() {
        val horizon = listOf(key("a"), key("b"))
        val chosen = PrefetchPolicy.selectToFetch(
            horizon,
            inFlight = emptySet(),
            readyAt = mapOf(key("a") to 1_000L), // not ready until t=1000
            now = 500,
            freeSlots = 5,
        )
        assertEquals(listOf(key("b")), chosen)
    }

    @Test fun select_readyOnceBackoffElapsed() {
        val horizon = listOf(key("a"))
        val chosen = PrefetchPolicy.selectToFetch(
            horizon, inFlight = emptySet(), readyAt = mapOf(key("a") to 1_000L), now = 1_000, freeSlots = 1,
        )
        assertEquals(listOf(key("a")), chosen)
    }

    @Test fun select_noFreeSlots_returnsEmpty() {
        val chosen = PrefetchPolicy.selectToFetch(
            listOf(key("a")), inFlight = emptySet(), readyAt = emptyMap(), now = 0, freeSlots = 0,
        )
        assertTrue(chosen.isEmpty())
    }

    // ---- idle wake -------------------------------------------------------------------------

    @Test fun nextWakeAt_earliestFutureBackoff() {
        val horizon = listOf(key("a"), key("b"))
        val at = PrefetchPolicy.nextWakeAt(
            horizon,
            inFlight = emptySet(),
            readyAt = mapOf(key("a") to 5_000L, key("b") to 3_000L),
            now = 1_000,
        )
        assertEquals(3_000L, at)
    }

    @Test fun nextWakeAt_nullWhenNothingWaiting() {
        assertNull(
            PrefetchPolicy.nextWakeAt(
                listOf(key("a")), inFlight = emptySet(), readyAt = emptyMap(), now = 0,
            ),
        )
    }
}
