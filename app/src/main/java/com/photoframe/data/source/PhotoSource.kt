package com.photoframe.data.source

import com.photoframe.data.SourceType
import java.io.InputStream
import java.time.Instant

/**
 * One configured photo source (DESIGN §5). All source types implement this single interface;
 * the sync engine treats them uniformly. Implementations do the network work; the slideshow
 * never calls these — it renders only from the local cache the sync engine populates (§6).
 */
interface PhotoSource {
    /** Stable id of this configured source instance (matches [com.photoframe.data.db.SourceEntity.id]). */
    val id: String

    val type: SourceType

    /** Cheap reachability/validity probe surfaced on the source status screen (§5.1). */
    suspend fun healthCheck(): SourceHealth

    /** The full desired photo set for the current selection config. */
    suspend fun listPhotos(): List<RemotePhoto>

    /** Download [photo] at display size and stream the bytes into [sink]. */
    suspend fun fetch(photo: RemotePhoto, sink: Sink)

    /** Whether this source supports deleting a remote asset (from the frame). */
    val canDelete: Boolean get() = false

    /**
     * Delete [remoteId] at the source (default: unsupported → throws). Immich moves it to trash;
     * picker/shared-album sources can't delete. Throws with a user-facing message on failure so
     * the caller can surface it (e.g. a missing `asset.delete` permission).
     */
    suspend fun deleteRemote(remoteId: String): Unit =
        throw UnsupportedOperationException("This source can't delete photos")
}

/**
 * A photo as the remote source describes it (DESIGN §5). Dimensions/timestamp may be unknown
 * until download for some sources (e.g. shared albums); [contentHash] enables change detection
 * when the source provides one.
 */
data class RemotePhoto(
    val remoteId: String,
    val takenAt: Instant? = null,
    val width: Int? = null,
    val height: Int? = null,
    val locationLabel: String? = null,
    val contentHash: String? = null,
)

/**
 * Destination for downloaded image bytes, supplied by the sync engine. The source calls
 * [write] with the response stream; the sink owns file placement and byte accounting so the
 * [PhotoSource] stays ignorant of the cache layout.
 */
interface Sink {
    /**
     * Persist display-sized image bytes. [contentType], when known (e.g. "image/jpeg"), selects
     * the file extension. Fully consumes [input]; callers close the underlying stream.
     */
    suspend fun write(input: InputStream, contentType: String?)
}

/** Health of a source (DESIGN §5/§6); [SourceHealthStatus.name] persists to the sources table. */
data class SourceHealth(
    val status: SourceHealthStatus,
    val message: String? = null,
) {
    companion object {
        val OK = SourceHealth(SourceHealthStatus.OK)
        fun offline(message: String?) = SourceHealth(SourceHealthStatus.OFFLINE, message)
        fun degraded(message: String?) = SourceHealth(SourceHealthStatus.DEGRADED, message)
    }
}

enum class SourceHealthStatus {
    /** Reachable and serving photos. */
    OK,

    /** Reachable but something is wrong (e.g. an endpoint 404'd, parser found zero photos). */
    DEGRADED,

    /** Unreachable — network/server down. The cache keeps the frame alive regardless (§6). */
    OFFLINE,

    /** Never synced yet. */
    UNKNOWN,
}
