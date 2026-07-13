package com.photoframe.playlist

import com.photoframe.playlist.TestPhotos.landscape
import com.photoframe.playlist.TestPhotos.landscapes
import com.photoframe.playlist.model.OrderMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.time.Duration
import java.time.Instant
import kotlin.random.Random
import org.junit.Test

class PhotoOrderingTest {

    private val now: Instant = Instant.parse("2024-06-01T00:00:00Z")
    private val window: Duration = Duration.ofDays(7)

    private fun order(photos: List<com.photoframe.playlist.model.PlaylistPhoto>, mode: OrderMode, seed: Long = 1) =
        PhotoOrdering.order(photos, mode, Random(seed), now, window)

    @Test fun shuffle_isPermutation_ofAllPhotos() {
        val photos = landscapes(50)
        val result = order(photos, OrderMode.SHUFFLE)
        assertEquals(photos.size, result.size)
        assertEquals(photos.map { it.id }.toSet(), result.map { it.id }.toSet())
    }

    @Test fun shuffle_isDeterministic_forSameSeed() {
        val photos = landscapes(20)
        assertEquals(
            order(photos, OrderMode.SHUFFLE, seed = 42).map { it.id },
            order(photos, OrderMode.SHUFFLE, seed = 42).map { it.id },
        )
    }

    @Test fun chronological_sortsByTakenAtAscending_nullsLast() {
        val a = landscape("a").copy(takenAt = Instant.parse("2022-01-01T00:00:00Z"))
        val b = landscape("b").copy(takenAt = Instant.parse("2021-01-01T00:00:00Z"))
        val c = landscape("c").copy(takenAt = null)
        val d = landscape("d").copy(takenAt = Instant.parse("2023-01-01T00:00:00Z"))
        val result = order(listOf(a, b, c, d), OrderMode.CHRONOLOGICAL).map { it.id }
        assertEquals(listOf("b", "a", "d", "c"), result)
    }

    @Test fun newestFirst_sortsByTakenAtDescending_nullsLast() {
        val a = landscape("a").copy(takenAt = Instant.parse("2022-01-01T00:00:00Z"))
        val b = landscape("b").copy(takenAt = Instant.parse("2021-01-01T00:00:00Z"))
        val c = landscape("c").copy(takenAt = null)
        val d = landscape("d").copy(takenAt = Instant.parse("2023-01-01T00:00:00Z"))
        val result = order(listOf(a, b, c, d), OrderMode.NEWEST_FIRST).map { it.id }
        assertEquals(listOf("d", "a", "b", "c"), result)
    }

    @Test fun recencyBoosted_placesRecentPhotosEarlierOnAverage() {
        // 10 recent (added today) + 10 old (added long ago). Over many shuffles, recent
        // photos should average a lower (earlier) position than old ones.
        val recent = (1..10).map { landscape("R$it", addedAt = now) }
        val old = (1..10).map { landscape("O$it", addedAt = now.minus(Duration.ofDays(60))) }
        val all = recent + old

        var recentPos = 0.0
        var oldPos = 0.0
        val runs = 400
        repeat(runs) { seed ->
            val ordered = PhotoOrdering.order(all, OrderMode.RECENCY_BOOSTED_SHUFFLE, Random(seed.toLong()), now, window)
            val idx = ordered.withIndex().associate { it.value.id to it.index }
            recentPos += recent.sumOf { idx.getValue(it.id) }.toDouble() / recent.size
            oldPos += old.sumOf { idx.getValue(it.id) }.toDouble() / old.size
        }
        recentPos /= runs
        oldPos /= runs
        assertTrue("recent avg pos ($recentPos) should be earlier than old ($oldPos)", recentPos < oldPos - 1.0)
    }

    @Test fun singleOrEmpty_returnedAsIs() {
        assertTrue(order(emptyList(), OrderMode.SHUFFLE).isEmpty())
        assertEquals(1, order(landscapes(1), OrderMode.SHUFFLE).size)
    }
}
