package com.photoframe.data.cache

import android.content.Context
import java.io.File
import java.io.InputStream

/**
 * App-private cache layout (DESIGN §6): `filesDir/photos/<sourceId>/<remoteId>`. Display-sized
 * files only. LRU eviction and the size cap arrive with the sync engine in Phase 2; this class
 * owns the on-disk layout and byte accounting the engine will build on.
 */
class PhotoCache(private val context: Context) {

    private val root: File by lazy { File(context.filesDir, "photos").apply { mkdirs() } }

    fun dirForSource(sourceId: String): File =
        File(root, sanitize(sourceId)).apply { mkdirs() }

    fun fileFor(sourceId: String, remoteId: String, extension: String): File =
        File(dirForSource(sourceId), sanitize(remoteId) + "." + extension.trimStart('.'))

    /** Copy [input] into the cache and return the written file. */
    fun writeFrom(sourceId: String, remoteId: String, extension: String, input: InputStream): File {
        val dest = fileFor(sourceId, remoteId, extension)
        dest.outputStream().use { out -> input.copyTo(out) }
        return dest
    }

    /** Total bytes currently held in the cache (across all sources). */
    fun totalBytes(): Long =
        root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
