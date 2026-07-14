package com.photoframe.data.sync

import com.photoframe.data.PhotoKey

/**
 * Pure scheduling decisions for the [PhotoPrefetcher] (kept Android-free so they're unit-testable,
 * like [SyncDiff]/[CacheEvictor]). The prefetcher owns the coroutine plumbing and DB/network I/O;
 * this decides *what* to fetch next and *how long* to back off after a failure.
 */
object PrefetchPolicy {

    /** First retry waits this long after a failed fetch; doubles each subsequent failure. */
    const val BASE_BACKOFF_MS = 2_000L

    /** Backoff ceiling — when the network is down the prefetcher idles here, not hammering. */
    const val MAX_BACKOFF_MS = 5 * 60_000L

    /**
     * Exponential backoff for the [failCount]-th consecutive failure (1-based): 2s, 4s, 8s, … capped
     * at [MAX_BACKOFF_MS]. Returns 0 for a non-positive count (no failures → fetch immediately).
     */
    fun backoffMillis(failCount: Int): Long {
        if (failCount <= 0) return 0L
        val shift = (failCount - 1).coerceIn(0, 40)
        val scaled = BASE_BACKOFF_MS shl shift
        return if (scaled <= 0L || scaled > MAX_BACKOFF_MS) MAX_BACKOFF_MS else scaled
    }

    /**
     * Choose which upcoming photos to start downloading now. [horizon] is the ordered set of
     * upcoming photos that still need a file (front = soonest); already-cached photos must be
     * pre-filtered out by the caller. Skips in-flight downloads and keys still in backoff
     * ([readyAt] > [now]), dedupes, and returns at most [freeSlots] keys, front-first.
     */
    fun selectToFetch(
        horizon: List<PhotoKey>,
        inFlight: Set<PhotoKey>,
        readyAt: Map<PhotoKey, Long>,
        now: Long,
        freeSlots: Int,
    ): List<PhotoKey> {
        if (freeSlots <= 0) return emptyList()
        val out = ArrayList<PhotoKey>(freeSlots)
        val seen = HashSet<PhotoKey>()
        for (key in horizon) {
            if (out.size >= freeSlots) break
            if (key in inFlight) continue
            if (!seen.add(key)) continue
            val at = readyAt[key]
            if (at != null && at > now) continue
            out += key
        }
        return out
    }

    /**
     * The soonest future time any horizon photo leaves backoff, or null if none are waiting — the
     * prefetcher sleeps until then instead of spinning when everything is backed off.
     */
    fun nextWakeAt(
        horizon: List<PhotoKey>,
        inFlight: Set<PhotoKey>,
        readyAt: Map<PhotoKey, Long>,
        now: Long,
    ): Long? = horizon.asSequence()
        .filter { it !in inFlight }
        .mapNotNull { readyAt[it] }
        .filter { it > now }
        .minOrNull()
}
