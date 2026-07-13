package com.photoframe.data

/** Photo source types (DESIGN §5). Phase 1 only exercises LOCAL_FOLDER-style fixtures. */
enum class SourceType {
    IMMICH,
    GPHOTOS_PICKER,
    GPHOTOS_SHARED_ALBUM,
    LOCAL_FOLDER,
}

/** Lifecycle of a cached photo row. */
enum class PhotoStatus {
    /** File present in the cache and displayable. */
    READY,

    /** Indexed but not yet downloaded (used by the network sync engine in Phase 2). */
    PENDING,

    /** Backing file is gone; row should be re-synced or evicted. */
    MISSING,
}
