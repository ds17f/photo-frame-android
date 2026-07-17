package com.photoframe.data

import com.photoframe.data.db.PhotoEntity
import com.photoframe.data.db.PhotoRef
import com.photoframe.playlist.model.Orientation
import com.photoframe.playlist.model.PlaylistPhoto
import java.time.Instant

/** Projects a persisted [PhotoEntity] into the engine's Android-free [PlaylistPhoto]. */
fun PhotoEntity.toPlaylistPhoto(): PlaylistPhoto =
    PlaylistPhoto(
        id = key,
        orientation = Orientation.of(width, height),
        takenAt = takenAt?.let(Instant::ofEpochMilli),
        addedAt = Instant.ofEpochMilli(addedAt),
        isFavorite = isFavorite,
    )

/** Same projection from the lightweight [PhotoRef] the playlist observes. */
fun PhotoRef.toPlaylistPhoto(): PlaylistPhoto =
    PlaylistPhoto(
        id = key,
        orientation = Orientation.of(width, height),
        takenAt = takenAt?.let(Instant::ofEpochMilli),
        addedAt = Instant.ofEpochMilli(addedAt),
        isFavorite = isFavorite,
    )
