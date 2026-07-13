package com.photoframe.ui

import android.content.Context

/**
 * Phase 0 fixture source: the bundled sample photos under `assets/samples/`. A later
 * phase replaces this with the real cache-backed playlist; the asset URIs here already
 * decode through Coil off the UI thread, respecting EXIF orientation.
 */
object SamplePhotos {

    private const val DIR = "samples"

    /** Asset URIs (`file:///android_asset/...`) for every bundled sample image. */
    fun uris(context: Context): List<String> =
        runCatching {
            context.assets.list(DIR)
                ?.filter { it.endsWith(".jpg", true) || it.endsWith(".png", true) }
                ?.sorted()
                ?.map { "file:///android_asset/$DIR/$it" }
                ?: emptyList()
        }.getOrDefault(emptyList())
}
