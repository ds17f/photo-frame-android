package com.photoframe.data.sync

import com.photoframe.data.db.EvictionRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheEvictorTest {

    private fun ref(id: String, sizeBytes: Long, lastShownAt: Long?) = EvictionRef(
        sourceId = "s",
        remoteId = id,
        localPath = "/cache/$id.jpg",
        sizeBytes = sizeBytes,
        lastShownAt = lastShownAt,
    )

    @Test fun underCap_evictsNothing() {
        val photos = listOf(ref("a", 100, 1), ref("b", 100, 2))
        val victims = CacheEvictor.selectVictims(photos, totalBytes = 200, capBytes = 500)
        assertTrue(victims.isEmpty())
    }

    @Test fun overCap_evictsLeastRecentlyShownFirst() {
        val photos = listOf(
            ref("old", 100, lastShownAt = 1),
            ref("mid", 100, lastShownAt = 5),
            ref("new", 100, lastShownAt = 9),
        )
        // total 300, cap 150 → must free 150+, i.e. evict 2 oldest-shown.
        val victims = CacheEvictor.selectVictims(photos, totalBytes = 300, capBytes = 150)
        assertEquals(listOf("old", "mid"), victims.map { it.remoteId })
    }

    @Test fun neverShown_isEvictedFirst() {
        // Never-shown (NULL, DB-ordered first) is a speculative orphan → the first to go, so the
        // cap can actually be held. (An earlier design protected all never-shown rows; they then
        // accumulated past the cap and it couldn't be enforced.)
        val photos = listOf(ref("fresh", 100, lastShownAt = null), ref("shown", 100, lastShownAt = 5))
        val victims = CacheEvictor.selectVictims(photos, totalBytes = 200, capBytes = 150)
        assertEquals(listOf("fresh"), victims.map { it.remoteId })
    }

    @Test fun protectedNeverShown_isKept() {
        // The one never-shown case that IS protected: it's in the prefetch horizon (awaiting its
        // turn). Then eviction falls back to the shown photo.
        val photos = listOf(ref("horizon-fresh", 100, lastShownAt = null), ref("shown", 100, lastShownAt = 5))
        val victims = CacheEvictor.selectVictims(
            photos,
            totalBytes = 200,
            capBytes = 150,
            protectedKeys = setOf("s/horizon-fresh"),
        )
        assertEquals(listOf("shown"), victims.map { it.remoteId })
    }

    @Test fun protectedKeys_areNotEvicted() {
        // The prefetcher's horizon photo is over the cap and least-recently-shown, but protected —
        // eviction must skip it and take the next candidate instead.
        val photos = listOf(
            ref("horizon", 100, lastShownAt = 1),
            ref("other", 100, lastShownAt = 2),
        )
        val victims = CacheEvictor.selectVictims(
            photos,
            totalBytes = 200,
            capBytes = 150,
            protectedKeys = setOf("s/horizon"),
        )
        assertEquals(listOf("other"), victims.map { it.remoteId })
    }

    @Test fun steadyState_evictsLeastValuableDownToCap_keepingHorizon() {
        // Realistic steady state: DB-ordered NULLs-first. The protected horizon photo is kept; the
        // rest are reclaimed least-valuable-first (speculative never-shown, then oldest-shown) down
        // to the cap.
        val photos = listOf(
            ref("fresh", 100, lastShownAt = null),       // just prefetched, not in horizon → speculative
            ref("shown-old", 100, lastShownAt = 1),
            ref("shown-mid", 100, lastShownAt = 2),
            ref("horizon", 100, lastShownAt = 3),        // in the prefetch horizon → protected
        )
        val victims = CacheEvictor.selectVictims(
            photos,
            totalBytes = 400,
            capBytes = 250, // must free 150 → evict 2, NULLs-first then oldest-shown
            protectedKeys = setOf("s/horizon"),
        )
        assertEquals(listOf("fresh", "shown-old"), victims.map { it.remoteId })
    }
}
