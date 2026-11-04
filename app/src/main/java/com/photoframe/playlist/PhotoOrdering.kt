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

    /** Recent photos get ~this much extra draw weight in RECENCY_BOOSTED_SHUFFLE. */
    const val RECENCY_BOOST = 3.0

    fun order(
        photos: List<PlaylistPhoto>,
        mode: OrderMode,
        random: Random,
        now: Instant,
        recencyWindow: Duration,
    ): List<PlaylistPhoto> {
        if (photos.size <= 1) return photos.toList()
        return when (mode) {
            // Shuffle, then sink recently-shown photos to the back: never-shown come first (in
            // random order — the stable sort preserves the shuffle among equal/null keys), then the
            // rest least-recently-shown first. Because lastShownAt is persisted, a restart rebuilds
            // the cycle roaming the un-shown remainder instead of replaying what was just on screen —
            // no repeats until the whole library has cycled.
            OrderMode.SHUFFLE ->
                photos.shuffled(random).sortedWith(compareBy(nullsFirst(naturalOrder())) { it.lastShownAt })
            OrderMode.CHRONOLOGICAL -> photos.sortedWith(byTakenAt(ascending = true))
            OrderMode.NEWEST_FIRST -> photos.sortedWith(byTakenAt(ascending = false))
            OrderMode.RECENCY_BOOSTED_SHUFFLE ->
                weightedShuffle(photos, random) { p ->
                    if (isRecent(p, now, recencyWindow)) RECENCY_BOOST else 1.0
                }.sortedBy { it.lastShownAt != null }
        }
    }

    fun isRecent(photo: PlaylistPhoto, now: Instant, window: Duration): Boolean =
        !photo.addedAt.isBefore(now.minus(window))

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

    /**
     * Weighted random permutation via the Efraimidis–Spirakis method: each item gets a key
     * `u^(1/w)` and we sort by descending key. Higher weight ⇒ tends to sort earlier, so a
     * 3× weighted photo appears ~3× as often near the front across reshuffles — exactly the
     * "new photos show up promptly without dominating" behaviour (DESIGN §7).
     */
    private fun weightedShuffle(
        photos: List<PlaylistPhoto>,
        random: Random,
        weight: (PlaylistPhoto) -> Double,
    ): List<PlaylistPhoto> =
        photos.map { p ->
            val w = weight(p).coerceAtLeast(1e-9)
            // key = u^(1/w); compute in log space to stay numerically stable.
            val u = random.nextDouble().coerceIn(1e-12, 1.0)
            val key = ln(u) / w // larger (closer to 0) key = earlier; sort descending
            p to key
        }.sortedByDescending { it.second }.map { it.first }
}
