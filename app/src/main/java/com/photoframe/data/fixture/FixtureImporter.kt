package com.photoframe.data.fixture

import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.util.Log
import com.photoframe.data.PhotoRepository
import com.photoframe.data.PhotoStatus
import com.photoframe.data.cache.PhotoCache
import com.photoframe.data.db.PhotoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Phase-1 fixture source: indexes the bundled sample photos and an optional device "debug
 * folder" into the Room cache so the rendering core is provable before any network code
 * exists (PLAN Phase 1). All decoding/EXIF work runs on [Dispatchers.IO], never the UI thread.
 *
 * Debug folder (no runtime permission needed — app-private external dir):
 *   adb push <photos>/ /sdcard/Android/data/<applicationId>/files/debug_photos/
 * where <applicationId> is `com.photoframe` (release) or `com.photoframe.debug` (debug build).
 */
class FixtureImporter(
    private val context: Context,
    private val cache: PhotoCache,
    private val repository: PhotoRepository,
) {
    /** Index all fixtures into Room and return the number of photos indexed. */
    suspend fun importAll(): Int = withContext(Dispatchers.IO) {
        val rows = buildList {
            addAll(importBundledSamples())
            addAll(importDebugFolder())
        }
        if (rows.isNotEmpty()) repository.indexFixtures(rows)
        rows.size
    }

    /** Build entities for the bundled `assets/samples/` images (copied into the cache). */
    suspend fun importBundledSamples(): List<PhotoEntity> = withContext(Dispatchers.IO) {
        val names = runCatching { context.assets.list(ASSET_DIR)?.toList() }.getOrNull().orEmpty()
            .filter { it.isImage() }
        names.mapNotNull { name ->
            runCatching {
                val remoteId = name.substringBeforeLast('.')
                val ext = name.substringAfterLast('.', "jpg")
                val dest = cache.fileFor(SAMPLES_SOURCE_ID, remoteId, ext)
                if (!dest.exists() || dest.length() == 0L) {
                    context.assets.open("$ASSET_DIR/$name").use { input ->
                        cache.writeFrom(SAMPLES_SOURCE_ID, remoteId, ext, input)
                    }
                }
                entityFor(SAMPLES_SOURCE_ID, remoteId, dest)
            }.onFailure { Log.w(TAG, "Failed to import sample $name", it) }.getOrNull()
        }
    }

    /** Build entities for images in the device debug folder, referenced in place. */
    suspend fun importDebugFolder(): List<PhotoEntity> = withContext(Dispatchers.IO) {
        val dir = context.getExternalFilesDir(DEBUG_DIR) ?: return@withContext emptyList()
        val files = dir.listFiles { f -> f.isFile && f.name.isImage() }?.toList().orEmpty()
        files.mapNotNull { file ->
            runCatching {
                entityFor(DEBUG_SOURCE_ID, file.nameWithoutExtension, file)
            }.onFailure { Log.w(TAG, "Failed to import debug ${file.name}", it) }.getOrNull()
        }
    }

    private fun entityFor(sourceId: String, remoteId: String, file: File): PhotoEntity {
        val info = decodeInfo(file)
        return PhotoEntity(
            sourceId = sourceId,
            remoteId = remoteId,
            localPath = file.absolutePath,
            takenAt = info.takenAtMillis,
            width = info.displayWidth,
            height = info.displayHeight,
            locationLabel = null,
            addedAt = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis(),
            lastShownAt = null,
            sizeBytes = file.length(),
            status = PhotoStatus.READY.name,
        )
    }

    private data class ImageInfo(
        val displayWidth: Int?,
        val displayHeight: Int?,
        val takenAtMillis: Long?,
    )

    /** Decode dimensions (EXIF-corrected so they match what the user sees) + capture time. */
    private fun decodeInfo(file: File): ImageInfo {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        var w = opts.outWidth.takeIf { it > 0 }
        var h = opts.outHeight.takeIf { it > 0 }

        var takenAt: Long? = null
        runCatching {
            val exif = ExifInterface(file.absolutePath)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90,
                ExifInterface.ORIENTATION_ROTATE_270,
                ExifInterface.ORIENTATION_TRANSPOSE,
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    val tmp = w; w = h; h = tmp // display dims are swapped
                }
            }
            takenAt = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?.let { parseExifDate(it) }
        }
        return ImageInfo(w, h, takenAt)
    }

    private fun parseExifDate(value: String): Long? = runCatching {
        LocalDateTime.parse(value, EXIF_DATE_FORMAT)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }.getOrNull()

    private fun String.isImage(): Boolean {
        val n = lowercase()
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")
    }

    companion object {
        private const val TAG = "FixtureImporter"
        private const val ASSET_DIR = "samples"
        private const val DEBUG_DIR = "debug_photos"
        const val SAMPLES_SOURCE_ID = "fixture-samples"
        const val DEBUG_SOURCE_ID = "debug-folder"
        private val EXIF_DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
    }
}
