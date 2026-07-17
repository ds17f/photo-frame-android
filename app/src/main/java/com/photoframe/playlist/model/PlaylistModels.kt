package com.photoframe.playlist.model

import java.time.Instant

/** Photo orientation. UNKNOWN (e.g. shared-album items before download) is treated as
 *  landscape for layout/pairing purposes until real dimensions are known (DESIGN §5). */
enum class Orientation {
    LANDSCAPE,
    PORTRAIT,
    UNKNOWN;

    val isPortrait: Boolean get() = this == PORTRAIT

    companion object {
        fun of(width: Int?, height: Int?): Orientation = when {
            width == null || height == null || width <= 0 || height <= 0 -> UNKNOWN
            height > width -> PORTRAIT
            else -> LANDSCAPE
        }
    }
}

/** Playlist order modes (DESIGN §7). */
enum class OrderMode {
    SHUFFLE,
    CHRONOLOGICAL,
    NEWEST_FIRST,
    RECENCY_BOOSTED_SHUFFLE,
}

/** How portrait photos are shown on the landscape frame (DESIGN §7). */
enum class PortraitMode {
    /** Pair with a nearby portrait if one exists within the lookahead, else blur-fill. */
    AUTO,

    /** Always try to pair with a nearby portrait (same lookahead); blur-fill only if none. */
    PAIR,

    /** Never pair; every portrait is blur-filled. */
    BLUR_FILL,
}

/**
 * Lightweight, Android-free projection of a cached photo used by the playlist engine.
 * Kept deliberately small so the engine is pure Kotlin and unit-testable on the JVM.
 */
data class PlaylistPhoto(
    val id: String,
    val orientation: Orientation,
    val takenAt: Instant?,
    val addedAt: Instant,
    val isFavorite: Boolean = false,
) {
    val isPortrait: Boolean get() = orientation.isPortrait
}

/**
 * One slideshow step. Either a single photo (landscape, or a blur-filled portrait) or a
 * pair of portraits shown side-by-side. A pair counts as one step; both photos' lastShownAt
 * is updated by the caller (DESIGN §7).
 */
sealed interface Slide {
    val photos: List<PlaylistPhoto>

    data class Single(val photo: PlaylistPhoto) : Slide {
        override val photos: List<PlaylistPhoto> get() = listOf(photo)
    }

    data class Pair(val left: PlaylistPhoto, val right: PlaylistPhoto) : Slide {
        override val photos: List<PlaylistPhoto> get() = listOf(left, right)
    }
}
