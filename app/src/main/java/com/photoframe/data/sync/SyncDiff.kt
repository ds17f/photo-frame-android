package com.photoframe.data.sync

import com.photoframe.data.source.DeletionPolicy

/**
 * Pure per-photo diff decisions for the streaming metadata sync (kept Android-free/testable, like
 * [CacheEvictor]/[PrefetchPolicy]). The syncer streams the remote listing page by page, classifying
 * each photo against the current index, then removes whatever the full listing didn't cover.
 */
object SyncDiff {

    enum class Action {
        /** Not in the index yet → insert an `INDEXED` row (no download). */
        INSERT,

        /** Present but the remote content changed → drop the stale file, back to `INDEXED`. */
        REINDEX,

        /** Present and unchanged (or no reliable change signal) → leave it alone. */
        SKIP,
    }

    /**
     * Classify one remote photo against its index row. Re-index only when **both** sides expose a
     * content hash and they differ — an unknown hash on either side is not a reliable change signal,
     * so we don't churn the cached file.
     */
    fun classify(remoteHash: String?, existingHash: String?, exists: Boolean): Action = when {
        !exists -> Action.INSERT
        remoteHash != null && existingHash != null && remoteHash != existingHash -> Action.REINDEX
        else -> Action.SKIP
    }

    /**
     * Indexed photos to remove after a full listing: those absent from [seenIds]. Only under
     * [DeletionPolicy.MIRROR]; keep-until-evicted leaves them in the index (they stop refreshing).
     * Must only be called after the **complete** listing succeeded — a partial listing would delete
     * everything not yet streamed.
     */
    fun toDelete(
        existingIds: Set<String>,
        seenIds: Set<String>,
        deletionPolicy: DeletionPolicy,
    ): List<String> = when (deletionPolicy) {
        DeletionPolicy.MIRROR -> existingIds.filter { it !in seenIds }
        DeletionPolicy.KEEP_UNTIL_EVICTED -> emptyList()
    }
}
