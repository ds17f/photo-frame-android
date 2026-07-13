package com.photoframe.ui.model

import com.photoframe.playlist.model.Orientation
import java.time.Instant

/** A displayable photo with everything the renderer/overlays need (path, orientation, caption). */
data class UiPhoto(
    val id: String,
    val path: String,
    val orientation: Orientation,
    val takenAt: Instant?,
    val locationLabel: String?,
)

/** UI projection of a playlist [com.photoframe.playlist.model.Slide], carrying file paths. */
sealed interface UiSlide {
    val photos: List<UiPhoto>
    /** Stable key for Compose AnimatedContent so transitions fire on slide change. */
    val key: String

    data class Single(val photo: UiPhoto) : UiSlide {
        override val photos get() = listOf(photo)
        override val key get() = photo.id
    }

    data class Pair(val left: UiPhoto, val right: UiPhoto) : UiSlide {
        override val photos get() = listOf(left, right)
        override val key get() = "${left.id}|${right.id}"
    }
}
