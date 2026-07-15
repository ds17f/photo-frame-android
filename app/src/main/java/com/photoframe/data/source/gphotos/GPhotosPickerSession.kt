package com.photoframe.data.source.gphotos

import android.graphics.BitmapFactory
import android.util.Log
import com.photoframe.data.PhotoStatus
import com.photoframe.data.cache.PhotoCache
import com.photoframe.data.db.PhotoDao
import com.photoframe.data.db.PhotoEntity
import com.photoframe.data.sync.CacheSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Drives one Google Photos pick (DESIGN §5.2) for a source: create a session, wait for the user to
 * finish picking (on their phone, via the picker QR), then **download every picked item immediately**
 * into the cache and index it as `READY`. Picker grants and baseURLs are short-lived, so the local
 * cache — not the server — is the source of truth; nothing here is deferred to the periodic sync.
 *
 * New picks **append** (deduped by media-item id) unless [replace] clears the set first. Reuses
 * [CacheSink] for cache placement so file layout/extension logic stays in one place.
 */
class GPhotosPickerSession(
    private val sourceId: String,
    private val api: GPhotosPickerApi,
    private val cache: PhotoCache,
    private val photoDao: PhotoDao,
    /** Returns a fresh OAuth access token (refreshes silently if needed). */
    private val accessTokenProvider: suspend () -> String,
) {
    private val downloadHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** What the UI needs to render the pick prompt: the URL to QR-encode + the session handle. */
    data class SessionInfo(
        val sessionId: String,
        val pickerUri: String,
        val pollIntervalMs: Long,
        val timeoutMs: Long,
    )

    suspend fun createSession(): SessionInfo {
        val auth = GPhotosPickerApi.bearer(accessTokenProvider())
        val session = api.createSession(auth)
        val uri = session.pickerUri
            ?: throw IllegalStateException("Google did not return a picker URL")
        return SessionInfo(
            sessionId = session.id,
            pickerUri = uri,
            pollIntervalMs = parseDurationMs(session.pollingConfig?.pollInterval) ?: DEFAULT_POLL_MS,
            timeoutMs = parseDurationMs(session.pollingConfig?.timeoutIn) ?: DEFAULT_TIMEOUT_MS,
        )
    }

    /**
     * Poll until the user has finished picking (`mediaItemsSet`), or until [shouldContinue] returns
     * false (user cancelled) or the session times out. Returns true when items are ready to download.
     */
    suspend fun awaitPicked(info: SessionInfo, shouldContinue: () -> Boolean): Boolean {
        val auth = GPhotosPickerApi.bearer(accessTokenProvider())
        val deadline = elapsed() + info.timeoutMs
        while (shouldContinue()) {
            val session = api.getSession(auth, info.sessionId)
            if (session.mediaItemsSet) return true
            if (elapsed() >= deadline) return false
            delay(info.pollIntervalMs.coerceAtLeast(MIN_POLL_MS))
        }
        return false
    }

    /**
     * Download every picked PHOTO into the cache and index it `READY`. When [replace] is true, the
     * source's existing photos (rows + files) are cleared first. [onProgress] reports (done, total).
     * Returns the number of photos newly added.
     */
    suspend fun downloadPicked(
        info: SessionInfo,
        replace: Boolean,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val auth = GPhotosPickerApi.bearer(accessTokenProvider())
        val items = collectItems(auth, info.sessionId).filter { it.type == null || it.type == "PHOTO" }

        if (replace) {
            photoDao.deleteAllForSource(sourceId)
            runCatching { cache.dirForSource(sourceId).deleteRecursively() }
        }

        var added = 0
        val total = items.size
        onProgress(0, total)
        for ((index, item) in items.withIndex()) {
            val existing = photoDao.getByKey(sourceId, item.id)
            if (existing == null) {
                if (download(auth, item)) added++
            }
            onProgress(index + 1, total)
        }
        added
    }

    suspend fun deleteSession(sessionId: String) {
        val auth = GPhotosPickerApi.bearer(accessTokenProvider())
        runCatching { api.deleteSession(auth, sessionId) }
            .onFailure { Log.w(TAG, "Failed to delete picker session $sessionId", it) }
    }

    // ---- Internals --------------------------------------------------------------------------

    private suspend fun collectItems(auth: String, sessionId: String): List<PickerMediaItemDto> {
        val all = ArrayList<PickerMediaItemDto>()
        var pageToken: String? = null
        do {
            val page = api.mediaItems(auth, sessionId, pageToken = pageToken)
            all += page.mediaItems
            pageToken = page.nextPageToken
        } while (!pageToken.isNullOrEmpty())
        return all
    }

    /** Download one picked item at display size and insert its `READY` row. Returns success. */
    private suspend fun download(auth: String, item: PickerMediaItemDto): Boolean {
        val baseUrl = item.mediaFile?.baseUrl ?: return false
        val request = Request.Builder()
            .url("$baseUrl=w$DISPLAY_PX-h$DISPLAY_PX")
            .header("Authorization", auth)
            .build()
        val sink = CacheSink(cache, sourceId, item.id)
        try {
            downloadHttp.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Download failed for ${item.id}: HTTP ${resp.code}")
                    return false
                }
                val bodyStream = resp.body?.byteStream() ?: return false
                sink.write(bodyStream, item.mediaFile.mimeType)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Download error for ${item.id}", e)
            return false
        }
        val result = sink.result ?: return false
        if (result.sizeBytes == 0L) {
            result.file.delete()
            return false
        }
        // Decoded bounds are the definitive display dims (EXIF-oriented) for portrait pairing;
        // fall back to the picker's reported metadata.
        val (w, h) = decodeBounds(result.file)
        photoDao.insertNew(
            listOf(
                PhotoEntity(
                    sourceId = sourceId,
                    remoteId = item.id,
                    localPath = result.file.absolutePath,
                    takenAt = parseInstant(item.createTime)?.toEpochMilli(),
                    width = w ?: item.mediaFile.mediaFileMetadata?.width,
                    height = h ?: item.mediaFile.mediaFileMetadata?.height,
                    locationLabel = null,
                    addedAt = System.currentTimeMillis(),
                    lastShownAt = null,
                    sizeBytes = result.sizeBytes,
                    status = PhotoStatus.READY.name,
                    contentHash = null,
                ),
            ),
        )
        return true
    }

    private fun decodeBounds(file: File): Pair<Int?, Int?> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return opts.outWidth.takeIf { it > 0 } to opts.outHeight.takeIf { it > 0 }
    }

    private fun parseInstant(value: String?): Instant? =
        value?.let { runCatching { Instant.parse(it) }.getOrNull() }

    /** Parse a protobuf Duration string ("5s", "5.000s", "300s") to millis. */
    private fun parseDurationMs(value: String?): Long? {
        val trimmed = value?.trim()?.removeSuffix("s") ?: return null
        val seconds = trimmed.toDoubleOrNull() ?: return null
        return (seconds * 1000).toLong()
    }

    private fun elapsed(): Long = System.nanoTime() / 1_000_000

    companion object {
        private const val TAG = "GPhotosPickerSession"
        private const val DISPLAY_PX = 2048
        private const val DEFAULT_POLL_MS = 3_000L
        private const val MIN_POLL_MS = 1_000L
        private const val DEFAULT_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
