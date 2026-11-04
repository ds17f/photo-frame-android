package com.photoframe.data

import com.photoframe.data.db.PhotoEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CachedPhotoStartupTest {

    @Test fun findsFirstUsableReadyPhotoAndIgnoresIndexedRows() = runBlocking {
        val indexed = photo("a", "1", PhotoStatus.INDEXED.name, "/indexed")
        val cached = photo("a", "2", PhotoStatus.READY.name, "/cached")
        var repairs = 0

        val found = findStartupPhoto(
            readPage = { _, _, _ -> listOf(indexed, cached) },
            repairMissing = { repairs++ },
            isUsableFile = { it == "/cached" },
        )

        assertSame(cached, found)
        assertEquals(0, repairs)
    }

    @Test fun repairsMissingEmptyAndUnusablePathsThenFindsNextPhoto() = runBlocking {
        val noPath = photo("a", "1", PhotoStatus.READY.name, null)
        val emptyPath = photo("a", "2", PhotoStatus.READY.name, "")
        val missing = photo("a", "3", PhotoStatus.READY.name, "/missing")
        val valid = photo("a", "4", PhotoStatus.READY.name, "/valid")
        val repaired = mutableListOf<String>()

        val found = findStartupPhoto(
            readPage = { _, _, _ -> listOf(noPath, emptyPath, missing, valid) },
            repairMissing = { repaired += it.remoteId },
            isUsableFile = { it == "/valid" },
        )

        assertSame(valid, found)
        assertEquals(listOf("1", "2", "3"), repaired)
    }

    @Test fun continuesAcrossKeysetPages() = runBlocking {
        val firstPage = (1..32).map { n ->
            photo("s", n.toString().padStart(2, '0'), PhotoStatus.READY.name, "/missing-$n")
        }
        val ready = photo("s", "33", PhotoStatus.READY.name, "/ready")
        val cursors = mutableListOf<Pair<String, String>>()

        val found = findStartupPhoto(
            readPage = { source, remote, limit ->
                assertEquals(32, limit)
                cursors += source to remote
                if (cursors.size == 1) firstPage else listOf(ready)
            },
            repairMissing = {},
            isUsableFile = { it == "/ready" },
        )

        assertSame(ready, found)
        assertEquals(listOf("" to "", "s" to "32"), cursors)
    }

    @Test fun returnsNullWhenThereAreNoUsableCachedPhotos() = runBlocking {
        val indexed = photo("s", "1", PhotoStatus.INDEXED.name, "/ignored")
        val missing = photo("s", "2", PhotoStatus.READY.name, "/missing")
        val repaired = mutableListOf<String>()

        val found = findStartupPhoto(
            readPage = { _, _, _ -> listOf(indexed, missing) },
            repairMissing = { repaired += it.remoteId },
            isUsableFile = { false },
        )

        assertNull(found)
        assertEquals(listOf("2"), repaired)
    }

    @Test fun cancellationFromPageReadPropagates() = runBlocking {
        var propagated = false
        try {
            findStartupPhoto(
                readPage = { _, _, _ -> throw CancellationException("cancel startup") },
                repairMissing = {},
                isUsableFile = { true },
            )
        } catch (_: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
    }

    private fun photo(source: String, remote: String, status: String, path: String?) = PhotoEntity(
        sourceId = source,
        remoteId = remote,
        localPath = path,
        takenAt = null,
        width = null,
        height = null,
        locationLabel = null,
        addedAt = 0,
        lastShownAt = null,
        sizeBytes = 0,
        status = status,
    )
}
