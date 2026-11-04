package com.photoframe.data.cache

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.InputStream

/** Where the retained-pool cache files live (streaming-cache model, docs/STORAGE_PLAN.md). */
enum class CacheLocation {
    /** App-private internal storage (`filesDir`) — default, always available. */
    INTERNAL,

    /** App-private dir on a removable, mounted SD card — no runtime permission, wiped on uninstall. */
    SD_CARD,
}

/**
 * App-private cache layout (DESIGN §6): `<root>/photos/<sourceId>/<remoteId>`, display-sized files
 * only. The **root is a resolved setting** (streaming-cache model): [CacheLocation.INTERNAL] or
 * [CacheLocation.SD_CARD]. When SD is selected but unavailable (card pulled/unmounted), the root
 * transparently falls back to internal and logs — render-time missing-file handling and the startup
 * sweep repair the stale SD paths — so reach never depends on the card (it lives in the index).
 */
class PhotoCache(private val context: Context) {

    @Volatile private var location: CacheLocation = CacheLocation.INTERNAL

    fun setLocation(newLocation: CacheLocation) {
        location = newLocation
    }

    fun currentLocation(): CacheLocation = location

    /** True when a removable, mounted SD card is present to cache onto. */
    fun sdCardAvailable(): Boolean = resolveRemovableRoot() != null

    /** Free bytes on the volume backing [loc], or null if that location isn't available. */
    fun usableBytes(loc: CacheLocation): Long? = when (loc) {
        CacheLocation.INTERNAL -> internalRoot().apply { mkdirs() }.usableSpace
        CacheLocation.SD_CARD -> resolveRemovableRoot()?.apply { mkdirs() }?.usableSpace
    }

    fun dirForSource(sourceId: String): File =
        File(root(), sanitize(sourceId)).apply { mkdirs() }

    fun fileFor(sourceId: String, remoteId: String, extension: String): File =
        File(dirForSource(sourceId), sanitize(remoteId) + "." + extension.trimStart('.'))

    /** Copy [input] into the cache and return the written file. */
    fun writeFrom(sourceId: String, remoteId: String, extension: String, input: InputStream): File {
        val dest = fileFor(sourceId, remoteId, extension)
        val staging = File.createTempFile(dest.name, ".part", dest.parentFile)
        try {
            staging.outputStream().use { out -> input.copyTo(out) }
            check(staging.length() > 0) { "Empty photo download" }
            check(staging.renameTo(dest)) { "Could not commit photo download" }
            return dest
        } finally {
            staging.delete()
        }
    }

    /** Total bytes currently held at the active location. */
    fun totalBytes(): Long =
        root().walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Delete all cache files at the **active** location (used before switching location). */
    fun deleteAllFiles() {
        val root = root()
        runCatching { root.deleteRecursively() }
            .onFailure { Log.w(TAG, "Failed to clear cache at ${root.path}", it) }
        root.mkdirs()
    }

    /** The resolved cache root for the active location, falling back to internal if SD is gone. */
    private fun root(): File {
        val dir = when (location) {
            CacheLocation.INTERNAL -> internalRoot()
            CacheLocation.SD_CARD -> resolveRemovableRoot() ?: internalRoot().also {
                Log.w(TAG, "SD card unavailable — falling back to internal storage")
            }
        }
        return dir.apply { mkdirs() }
    }

    private fun internalRoot(): File = File(context.filesDir, "photos")

    /** The app-specific `photos` dir on a removable, mounted SD card, or null if there is none. */
    private fun resolveRemovableRoot(): File? =
        context.getExternalFilesDirs(null).asSequence()
            .filterNotNull()
            .firstOrNull { dir ->
                runCatching {
                    Environment.isExternalStorageRemovable(dir) &&
                        Environment.getExternalStorageState(dir) == Environment.MEDIA_MOUNTED
                }.getOrDefault(false)
            }
            ?.let { File(it, "photos") }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    companion object {
        private const val TAG = "PhotoCache"
    }
}
