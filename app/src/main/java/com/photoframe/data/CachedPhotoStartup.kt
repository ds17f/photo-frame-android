package com.photoframe.data

import com.photoframe.data.db.PhotoEntity

/**
 * Finds the first usable cached photo for slideshow startup without loading the full photo index.
 * The caller supplies keyset-paged READY rows and must call this helper off the main thread.
 */
suspend fun findStartupPhoto(
    readPage: suspend (afterSource: String, afterRemote: String, limit: Int) -> List<PhotoEntity>,
    repairMissing: suspend (PhotoEntity) -> Unit,
    isUsableFile: (String) -> Boolean,
): PhotoEntity? {
    var afterSource = ""
    var afterRemote = ""

    while (true) {
        val page = readPage(afterSource, afterRemote, PAGE_SIZE)
        if (page.isEmpty()) return null

        for (photo in page) {
            if (photo.status != PhotoStatus.READY.name) continue
            val path = photo.localPath
            if (path.isNullOrEmpty() || !isUsableFile(path)) {
                repairMissing(photo)
                continue
            }
            return photo
        }

        val last = page.last()
        // A broken pager must not trap startup in a repeated-page loop.
        if (last.sourceId < afterSource ||
            (last.sourceId == afterSource && last.remoteId <= afterRemote)
        ) return null
        afterSource = last.sourceId
        afterRemote = last.remoteId
        if (page.size < PAGE_SIZE) return null
    }
}

private const val PAGE_SIZE = 32
