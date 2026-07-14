package com.photoframe.data

/** Photo source types (DESIGN §5). Phase 1 only exercises LOCAL_FOLDER-style fixtures. */
enum class SourceType {
    IMMICH,
    GPHOTOS_PICKER,
    GPHOTOS_SHARED_ALBUM,
    LOCAL_FOLDER,
}

/**
 * Lifecycle of a photo index row (streaming-cache model, see docs/STORAGE_PLAN.md). Every remote
 * photo has a row; the file is optional. A row moves INDEXED → READY when the prefetcher downloads
 * its file, and back to INDEXED when the file is evicted, its content changes, or it goes missing.
 * Rows are deleted only when the remote photo vanishes — never by eviction.
 */
enum class PhotoStatus {
    /** Indexed (metadata only) — no file on disk. The prefetcher downloads these on demand. */
    INDEXED,

    /** File present in the cache and displayable. The slideshow renders only READY rows. */
    READY,
}
