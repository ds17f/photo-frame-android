package com.photoframe.playlist

import com.photoframe.playlist.model.Orientation
import com.photoframe.playlist.model.PlaylistPhoto
import java.time.Instant

/** Test helpers for building [PlaylistPhoto]s concisely. */
object TestPhotos {
    val EPOCH: Instant = Instant.parse("2020-01-01T00:00:00Z")

    fun photo(
        id: String,
        orientation: Orientation = Orientation.LANDSCAPE,
        takenAt: Instant? = null,
        addedAt: Instant = EPOCH,
        isFavorite: Boolean = false,
    ) = PlaylistPhoto(
        id = id,
        orientation = orientation,
        takenAt = takenAt,
        addedAt = addedAt,
        isFavorite = isFavorite,
    )

    fun landscape(id: String, addedAt: Instant = EPOCH) =
        photo(id, Orientation.LANDSCAPE, addedAt = addedAt)

    /** A favorite landscape (id prefixed so tests can identify favorites by id). */
    fun favorite(id: String) = photo(id, Orientation.LANDSCAPE, isFavorite = true)

    fun portrait(id: String, addedAt: Instant = EPOCH) =
        photo(id, Orientation.PORTRAIT, addedAt = addedAt)

    fun landscapes(n: Int) = (1..n).map { landscape("L$it") }
}
