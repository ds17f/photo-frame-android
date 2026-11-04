package com.photoframe.playlist

import com.photoframe.playlist.model.OrderMode
import com.photoframe.playlist.model.PlaylistPhoto
import java.time.Duration
import java.time.Instant
import kotlin.math.ln
import kotlin.random.Random

/**
 * Turns the merged photo set into one ordered cycle for a given [OrderMode] (DESIGN §7).
 * Shuffle modes produce a random-without-replacement permutation; ordered modes sort by
 * capture time. All randomness flows through an injected [Random] so orderings are
 * reproducible in tests.
 */
object PhotoOrdering {

    /** A photo taken today gets this much draw weight in RECENCY_BOOSTED_SHUFFLE; an undated or
     *  very old photo gets 1. The boost halves every recency window (smooth, no cliff). */
    const val RECENCY_BOOST = 3.0

    private const val MILLIS_PER_DAY = 86_400_000L

    fun order(
        photos: List<PlaylistPhoto>,
        mode: OrderMode,
        random: Random,
        now: Instant,
        recencyWindow: Duration,
    ): List<PlaylistPhoto> {
        if (photos.size <= 1) return photos.toList()
        return when (mode) {
            OrderMode.SHUFFLE -> shuffleByShowHistory(photos, random) { 1.0 }
            OrderMode.RECENCY_BOOSTED_SHUFFLE ->
                shuffleByShowHistory(photos, random) { p -> recencyWeight(p, now, recencyWindow) }
            OrderMode.CHRONOLOGICAL -> photos.sortedWith(byTakenAt(ascending = true))
            OrderMode.NEWEST_FIRST -> photos.sortedWith(byTakenAt(ascending = false))
        }
    }

    /**
     * Draw weight by capture date: `1 + (BOOST-1) * 0.5^(age / halfLife)`. A photo taken today
     * weighs ~3, one taken a half-life ago ~2, an old or undated one ~1 — newer photos come up
     * more often without old ones going quiet.
     */
    fun recencyWeight(photo: PlaylistPhoto, now: Instant, halfLife: Duration): Double {
        val taken = photo.takenAt ?: return 1.0
        val halfLifeDays = (halfLife.toMillis().toDouble() / MILLIS_PER_DAY).coerceAtLeast(1.0)
        val ageDays = ((now.toEpochMilli() - taken.toEpochMilli()).toDouble() / MILLIS_PER_DAY).coerceAtLeast(0.0)
        return 1.0 + (RECENCY_BOOST - 1.0) * Math.pow(0.5, ageDays / halfLifeDays)
    }

    /**
     * The shuffle order, rebuilt from scratch whenever the photo set changes:
     * 1. Never-shown photos first, as a (weighted) random permutation.
     * 2. Then shown photos, oldest show day first; random within each day so cycle 2 isn't a
     *    replay of cycle 1's exact sequence.
     * Because lastShownAt is persisted (and survives a row being deleted and re-listed), this is
     * stateless: every rebuild — after a sync, a restart, or a reinstall that keeps the database —
     * keeps roaming what hasn't been shown yet.
     */
    private fun shuffleByShowHistory(
        photos: List<PlaylistPhoto>,
        random: Random,
        weight: (PlaylistPhoto) -> Double,
    ): List<PlaylistPhoto> {
        val keyed = photos.map { p -> Keyed(p, showDay(p), weightedKey(random, weight(p))) }
        return keyed.sortedWith(compareBy<Keyed> { it.day }.thenByDescending { it.key }).map { it.photo }
    }

    private class Keyed(val photo: PlaylistPhoto, val day: Long, val key: Double)

    private fun showDay(p: PlaylistPhoto): Long =
        p.lastShownAt?.let { Math.floorDiv(it.toEpochMilli(), MILLIS_PER_DAY) } ?: Long.MIN_VALUE

    /** Efraimidis–Spirakis key `u^(1/w)` in log space; sort descending. Higher weight ⇒ earlier. */
    private fun weightedKey(random: Random, weight: Double): Double {
        val u = random.nextDouble().coerceIn(1e-12, 1.0)
        return ln(u) / weight.coerceAtLeast(1e-9)
    }

    /** Undated photos sort to the end in both directions. */
    private fun byTakenAt(ascending: Boolean): Comparator<PlaylistPhoto> {
        val base = Comparator<PlaylistPhoto> { a, b ->
            val ta = a.takenAt
            val tb = b.takenAt
            when {
                ta == null && tb == null -> a.id.compareTo(b.id)
                ta == null -> 1
                tb == null -> -1
                else -> ta.compareTo(tb)
            }
        }
        // For descending, invert only the dated comparison but keep nulls last and keep a
        // stable tiebreak on id.
        return if (ascending) base else Comparator { a, b ->
            val ta = a.takenAt
            val tb = b.takenAt
            when {
                ta == null && tb == null -> a.id.compareTo(b.id)
                ta == null -> 1
                tb == null -> -1
                else -> tb.compareTo(ta)
            }
        }
    }
}
