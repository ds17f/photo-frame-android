package com.photoframe.data.sync

import com.photoframe.data.cache.PhotoCache
import com.photoframe.data.source.Sink
import java.io.File
import java.io.InputStream

/** Result of a completed [CacheSink] write: the cache file and its size on disk. */
data class SinkResult(val file: File, val sizeBytes: Long)

/**
 * A [Sink] bound to one (sourceId, remoteId) that streams downloaded bytes into [PhotoCache]
 * (DESIGN §6). The syncer creates one per download and reads [result] afterward. The source
 * stays ignorant of the cache layout — it only knows how to produce bytes.
 */
class CacheSink(
    private val cache: PhotoCache,
    private val sourceId: String,
    private val remoteId: String,
) : Sink {

    /** Populated once [write] completes; null if the download never delivered bytes. */
    var result: SinkResult? = null
        private set

    override suspend fun write(input: InputStream, contentType: String?) {
        val file = cache.writeFrom(sourceId, remoteId, extensionFor(contentType), input)
        result = SinkResult(file, file.length())
    }

    private fun extensionFor(contentType: String?): String = when {
        contentType == null -> "jpg"
        contentType.contains("png", ignoreCase = true) -> "png"
        contentType.contains("webp", ignoreCase = true) -> "webp"
        contentType.contains("heic", ignoreCase = true) -> "heic"
        contentType.contains("heif", ignoreCase = true) -> "heif"
        else -> "jpg" // image/jpeg and Immich previews
    }
}
