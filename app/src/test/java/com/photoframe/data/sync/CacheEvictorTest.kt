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

    @Test fun neverShownPhotos_areProtected() {
        // All never shown → nothing may be evicted even though over cap (one-full-cycle rule).
        val photos = listOf(ref("a", 100, null), ref("b", 100, null))
        val victims = CacheEvictor.selectVictims(photos, totalBytes = 200, capBytes = 50)
        assertTrue(victims.isEmpty())
    }

    @Test fun evictsShownButNotNeverShown() {
        val photos = listOf(
            ref("shown", 100, lastShownAt = 1),
            ref("fresh", 100, lastShownAt = null),
        )
        val victims = CacheEvictor.selectVictims(photos, totalBytes = 200, capBytes = 50)
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
}
