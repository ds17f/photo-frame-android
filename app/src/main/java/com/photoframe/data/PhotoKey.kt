package com.photoframe.data

/**
 * A photo's composite identity split back out of the playlist/UI `sourceId/remoteId` string key
 * (see [com.photoframe.data.db.PhotoEntity.key]). The prefetcher and playback resolve engine ids
 * back to their DB primary key through this. Source ids are sanitized (no `/`) and remote ids are
 * opaque tokens (Immich UUIDs), so splitting on the first `/` is unambiguous.
 */
data class PhotoKey(val sourceId: String, val remoteId: String) {
    val key: String get() = "$sourceId/$remoteId"

    companion object {
        fun parse(key: String): PhotoKey {
            val slash = key.indexOf('/')
            require(slash > 0) { "Malformed photo key: $key" }
            return PhotoKey(key.substring(0, slash), key.substring(slash + 1))
        }
    }
}
