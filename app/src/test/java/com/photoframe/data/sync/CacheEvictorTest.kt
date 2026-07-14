package com.photoframe.data.sync

import com.photoframe.data.PhotoStatus
import com.photoframe.data.db.PhotoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheEvictorTest {

    private fun photo(id: String, sizeBytes: Long, lastShownAt: Long?) = PhotoEntity(
        sourceId = "s",
        remoteId = id,
        localPath = "/cache/$id.jpg",
        takenAt = null,
        width = 100,
        height = 100,
        locationLabel = null,
        addedAt = 0,
        lastShownAt = lastShownAt,
        sizeBytes = sizeBytes,
        status = PhotoStatus.READY.name,
    )

    @Test fun underCap_evictsNothing() {
        val photos = listOf(photo("a", 100, 1), photo("b", 100, 2))
        val victims = CacheEvictor.selectVictims(photos, totalBytes = 200, capBytes = 500)
        assertTrue(victims.isEmpty())
    }

    @Test fun overCap_evictsLeastRecentlyShownFirst() {
        val photos = listOf(
            photo("old", 100, lastShownAt = 1),
            photo("mid", 100, lastShownAt = 5),
            photo("new", 100, lastShownAt = 9),
        )
        // total 300, cap 150 → must free 150+, i.e. evict 2 oldest-shown.
        val victims = CacheEvictor.selectVictims(photos, totalBytes = 300, capBytes = 150)
        assertEquals(listOf("old", "mid"), victims.map { it.remoteId })
    }

    @Test fun neverShownPhotos_areProtected() {
        // All never shown → nothing may be evicted even though over cap (one-full-cycle rule).
        val photos = listOf(photo("a", 100, null), photo("b", 100, null))
        val victims = CacheEvictor.selectVictims(photos, totalBytes = 200, capBytes = 50)
        assertTrue(victims.isEmpty())
    }

    @Test fun evictsShownButNotNeverShown() {
        val photos = listOf(
            photo("shown", 100, lastShownAt = 1),
            photo("fresh", 100, lastShownAt = null),
        )
        val victims = CacheEvictor.selectVictims(photos, totalBytes = 200, capBytes = 50)
        assertEquals(listOf("shown"), victims.map { it.remoteId })
    }
}
