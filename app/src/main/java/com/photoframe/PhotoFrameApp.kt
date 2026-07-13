package com.photoframe

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache

/**
 * Application entry point. Provides a conservatively-sized Coil [ImageLoader] — Fire HD
 * devices have only 2–3 GB RAM (DESIGN.md §3), so the in-memory bitmap cache is capped
 * well below Coil's default 25% of app memory.
 */
class PhotoFrameApp : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.15)
                    .build()
            }
            // Downsampling to the target view size happens per-request; we never hold
            // full-resolution originals in memory.
            .allowRgb565(true)
            .build()
}
