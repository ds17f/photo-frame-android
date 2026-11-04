package com.photoframe.playlist

import com.photoframe.playlist.model.OrderMode
import com.photoframe.playlist.model.Orientation
import com.photoframe.playlist.model.PlaylistPhoto
import com.photoframe.playlist.model.PortraitMode
import com.photoframe.playlist.model.Slide
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/**
 * Stateful slideshow playlist over the merged photo set (DESIGN §7).
 *
 * - Produces [Slide]s (single photos or portrait pairs) on demand via [next]/[previous].
 * - Shuffle modes draw random-without-replacement from a "bag"; when the bag empties a new
 *   cycle is drawn. Recency-boosted shuffle weights recently-added photos ~3×.
 * - Newly-synced photos are spliced near the front of the current bag so they appear soon.
 * - Keeps a bounded history so back-swipes revisit the actual previously-shown slides.
 *
 * All randomness and time flow through injected [random]/[clock] so the engine is fully
 * deterministic under test. Not thread-safe; drive it from a single coroutine/dispatcher.
 */
class PlaylistEngine(
    private val random: Random = Random.Default,
    private val clock: () -> Instant = Instant::now,
) {
    private val all = LinkedHashMap<String, PlaylistPhoto>()
    private val remaining = ArrayDeque<PlaylistPhoto>()
    private val history = ArrayList<Slide>()
    private var historyIndex = -1

    /**
     * Actual scheduled slides, removed from the main bag but not yet shown. Slow downloads
     * remain here while other ready slides play. Only displayed slides enter history.
     */
    private val upcoming = ArrayDeque<Slide>()

    /** Separate without-replacement bag of favorites, drawn from independently of [remaining] so a
     *  favorite can recur more often than once per full cycle (favorites bias). Refilled when empty. */
    private val favoritesBag = ArrayDeque<PlaylistPhoto>()

    var orderMode: OrderMode = OrderMode.SHUFFLE
        private set

    /** Probability (0..1) that a given slide is drawn from the favorites bag instead of the main
     *  cycle. 0 disables the bias; 1 shows favorites only. Applies only in shuffle order modes. */
    var favoriteShare: Double = 0.0
        private set
    var portraitMode: PortraitMode = PortraitMode.AUTO
        private set
    var recencyWindow: Duration = Duration.ofDays(7)
        private set

    /** Orientation eligible for side-by-side/stacked pairing — perpendicular to the screen's
     *  long axis. PORTRAIT on a landscape frame, LANDSCAPE on a portrait frame (DESIGN §8). */
    var pairOrientation: Orientation = Orientation.PORTRAIT
        private set

    val size: Int get() = all.size
    val isEmpty: Boolean get() = all.isEmpty()

    /** Replace display settings live. Changing the order mode starts a fresh bag; changing
     *  only the portrait mode affects future slides without disturbing the current bag. */
    fun updateConfig(
        orderMode: OrderMode = this.orderMode,
        portraitMode: PortraitMode = this.portraitMode,
        recencyWindow: Duration = this.recencyWindow,
        pairOrientation: Orientation = this.pairOrientation,
        favoriteShare: Double = this.favoriteShare,
    ) {
        val orderChanged = orderMode != this.orderMode || recencyWindow != this.recencyWindow
        this.orderMode = orderMode
        this.portraitMode = portraitMode
        this.recencyWindow = recencyWindow
        // Pairing only affects how upcoming photos are grouped, not the draw order, so a
        // screen-rotation change here must not disturb the current bag.
        this.pairOrientation = pairOrientation
        // Favorites bias tunes only which bag the next slide is drawn from — no cycle restart.
        this.favoriteShare = favoriteShare.coerceIn(0.0, 1.0)
        if (orderChanged) startNewCycle()
    }

    /**
     * Set the full desired photo set. New photos are spliced near the front of the current
     * bag (shuffle) or inserted in sorted position (ordered modes); vanished photos are
     * dropped from the bag. Existing bag order is otherwise preserved.
     */
    fun setPhotos(photos: List<PlaylistPhoto>) {
        val incoming = LinkedHashMap<String, PlaylistPhoto>(photos.size)
        for (p in photos) {
            val shown = all[p.id]?.lastShownAt
            incoming[p.id] = if (shown != null && (p.lastShownAt == null || shown > p.lastShownAt))
                p.copy(lastShownAt = shown) else p
        }

        val addedIds = incoming.keys - all.keys
        val removedIds = all.keys - incoming.keys

        all.clear()
        all.putAll(incoming)

        // Drop vanished photos from the pending bag.
        if (removedIds.isNotEmpty()) remaining.removeAll { it.id in removedIds }

        // Drop the held pending slide if one of its photos vanished; it'll be rebuilt from the
        // fresh bag on the next peekPending().
        if (removedIds.isNotEmpty()) {
            val survivors = upcoming.flatMap { it.photos }.filter { it.id !in removedIds }
            upcoming.clear()
            survivors.forEach { upcoming.addLast(Slide.Single(incoming.getValue(it.id))) }
        }

        // Drop favorites-bag entries that vanished or are no longer favorites; newly-favorited
        // photos are picked up when the bag next refills.
        if (favoritesBag.isNotEmpty()) favoritesBag.removeAll { incoming[it.id]?.isFavorite != true }

        // Refresh dims/metadata on entries still in the bag: a photo's orientation may have been
        // corrected from decoded bounds after download (its remote-reported dims were wrong), and
        // the bag holds a stale snapshot — without this it could be mis-paired on its first show.
        if (remaining.isNotEmpty()) {
            val it = remaining.listIterator()
            while (it.hasNext()) {
                val fresh = incoming[it.next().id]
                if (fresh != null) it.set(fresh)
            }
        }

        if (addedIds.isEmpty()) return
        val added = addedIds.map { incoming.getValue(it) }

        if (remaining.isEmpty() && upcoming.isEmpty() && all.size == addedIds.size) {
            // Nothing playing yet; the first next() will build the initial cycle.
            return
        }
        spliceIn(added)
    }

    private fun spliceIn(added: List<PlaylistPhoto>) {
        when (orderMode) {
            OrderMode.CHRONOLOGICAL, OrderMode.NEWEST_FIRST -> {
                remaining.addAll(added)
                val sorted = PhotoOrdering.order(
                    remaining.toList(), orderMode, random, clock(), recencyWindow,
                )
                remaining.clear()
                remaining.addAll(sorted)
            }
            OrderMode.SHUFFLE, OrderMode.RECENCY_BOOSTED_SHUFFLE -> {
                // Batch insertion avoids shifting a 72k bag once per arriving sync row.
                val prefixSize = minOf(SPLICE_FRONT_WINDOW, remaining.size)
                val prefix = remaining.take(prefixSize)
                repeat(prefixSize) { remaining.removeFirst() }
                remaining.addAll(0, (prefix + added).shuffled(random))
            }
        }
    }

    private fun startNewCycle() {
        // A held pending slide was built from the old bag/order; a fresh cycle invalidates it.
        upcoming.clear()
        remaining.clear()
        remaining.addAll(
            PhotoOrdering.order(all.values.toList(), orderMode, random, clock(), recencyWindow),
        )
    }

    /**
     * Schedule and return the next [n] photos without recording them as shown — the prefetcher's
     * download horizon. Forward history (slides the user swiped back past, which `next()` replays
     * first) comes ahead of the upcoming bag. Near a cycle boundary this may return fewer than [n]
     * (the next cycle isn't drawn yet); the prefetcher simply fetches what it can see.
     */
    fun peek(n: Int): List<PlaylistPhoto> {
        if (n <= 0) return emptyList()
        fillUpcoming(n)
        return (history.drop(historyIndex + 1).flatMap { it.photos } +
            upcoming.flatMap { it.photos }).take(n)
    }

    /** Build the actual future slides, including favorite draws, so prefetch matches playback. */
    private fun fillUpcoming(photoCount: Int) {
        if (all.isEmpty()) return
        if (remaining.isEmpty() && upcoming.isEmpty()) startNewCycle()
        var count = upcoming.sumOf { it.photos.size }
        while (count < photoCount) {
            // Leave the next cycle until all scheduled slides from this one have been shown.
            if (remaining.isEmpty() && upcoming.isNotEmpty()) break
            val slide = if (shouldDrawFavorite()) buildFavoriteSlide() ?: buildMainSlide()
                else buildMainSlide()
            if (slide == null) break
            upcoming.addLast(slide)
            count += slide.photos.size
        }
    }

    /** The held window can be consumed out of order when a download is slow or broken. */
    fun scheduledSlides(): List<Slide> = upcoming.toList()

    /** Confirmed download failures return to the unshown bag, letting the window reach new photos. */
    fun deferFailed(failedIds: Set<String>) {
        if (failedIds.isEmpty()) return
        val failed = upcoming.filter { slide -> slide.photos.any { it.id in failedIds } }
        if (failed.isEmpty()) return
        val photos = failed.flatMap { it.photos }.distinctBy { it.id }
        val ids = photos.map { it.id }.toSet()
        upcoming.removeAll { it in failed }
        remaining.removeAll { it.id in ids }
        remaining.addAll(photos)
    }

    fun commitScheduled(slide: Slide) {
        if (!upcoming.remove(slide)) return
        recordShown(slide)
    }

    /** Cached startup/offline playback shares history and removes those photos from this cycle. */
    fun recordCached(slide: Slide) {
        val ids = slide.photos.map { it.id }.toSet()
        remaining.removeAll { it.id in ids }
        val survivors = upcoming.flatMap { it.photos }.filter { it.id !in ids }
        upcoming.clear()
        survivors.forEach { upcoming.addLast(Slide.Single(it)) }
        recordShown(slide)
    }

    private fun recordShown(slide: Slide) {
        val now = clock()
        for (photo in slide.photos) {
            all[photo.id]?.let { all[photo.id] = it.copy(lastShownAt = now) }
        }
        if (historyIndex < history.lastIndex) history.subList(historyIndex + 1, history.size).clear()
        history.add(slide)
        historyIndex = history.lastIndex
        trimHistory()
    }

    /** Current slide, or null if nothing has been shown yet. */
    fun current(): Slide? = history.getOrNull(historyIndex)

    /**
     * The next slide to display, WITHOUT recording it as shown. Replays forward history first (if
     * the user swiped back), otherwise builds the next slide from the bag and holds it, so repeated
     * calls return the same slide until [commitPending]. This lets the caller wait for the slide's
     * photos to be cached before committing — an uncached slide is never discarded from the cycle.
     * Returns null only when there are no photos.
     */
    fun peekPending(): Slide? {
        if (historyIndex < history.lastIndex) return history[historyIndex + 1]
        fillUpcoming(1)
        return upcoming.firstOrNull()
    }

    /** Draw the next slide from the favorites bag with probability [favoriteShare] (shuffle modes
     *  only, and only when favorites exist). Idempotent per pending slide: the coin is flipped once,
     *  when the slide is built, so repeated peeks while waiting don't re-roll. */
    private fun shouldDrawFavorite(): Boolean {
        val shuffle = orderMode == OrderMode.SHUFFLE || orderMode == OrderMode.RECENCY_BOOSTED_SHUFFLE
        if (!shuffle || favoriteShare <= 0.0) return false
        if (favoritesBag.isEmpty()) refillFavoritesBag()
        return favoritesBag.isNotEmpty() && random.nextDouble() < favoriteShare
    }

    private fun buildFavoriteSlide(): Slide? {
        if (favoritesBag.isEmpty()) refillFavoritesBag()
        if (favoritesBag.isEmpty()) return null
        return SlideBuilder.nextSlide(favoritesBag, portraitMode, pairOrientation)
    }

    private fun buildMainSlide(): Slide? {
        if (remaining.isEmpty()) startNewCycle()
        return SlideBuilder.nextSlide(remaining, portraitMode, pairOrientation)
    }

    private fun refillFavoritesBag() {
        favoritesBag.clear()
        favoritesBag.addAll(all.values.filter { it.isFavorite }.shuffled(random))
    }

    /**
     * Record the currently-peeked slide as shown: commit the held [upcoming] into history, or (when
     * replaying) advance into the existing forward history. Call once the peeked slide is actually
     * displayed. No-op if nothing is pending and there is no forward history to advance into.
     */
    fun commitPending() {
        // Replay branch first: while there is forward history to advance through (the user swiped
        // back), a slide held in `pending` belongs *after* that history, so it must not be committed
        // yet — just step forward through the recorded slides.
        if (historyIndex < history.lastIndex) {
            historyIndex++
            return
        }
        val p = upcoming.removeFirstOrNull() ?: return
        recordShown(p)
    }

    /** Advance to the next slide and record it as shown (peek + commit in one step). */
    fun next(): Slide? {
        val slide = peekPending() ?: return null
        commitPending()
        return slide
    }

    /** Go back to the previously-shown slide (bounded by retained history). */
    fun previous(): Slide? {
        if (historyIndex > 0) historyIndex--
        return current()
    }

    /** Discard all playback state (bag + history + pending); keeps the photo set and settings. */
    fun reset() {
        remaining.clear()
        favoritesBag.clear()
        history.clear()
        historyIndex = -1
        upcoming.clear()
    }

    private fun trimHistory() {
        val overflow = history.size - MAX_HISTORY
        if (overflow > 0) {
            repeat(overflow) { history.removeAt(0) }
            historyIndex -= overflow
            if (historyIndex < 0) historyIndex = 0
        }
    }

    companion object {
        /** New photos are inserted within the first N bag positions (shuffle modes). */
        const val SPLICE_FRONT_WINDOW = 3

        /** Upper bound on retained back-navigation history. */
        const val MAX_HISTORY = 100
    }
}
