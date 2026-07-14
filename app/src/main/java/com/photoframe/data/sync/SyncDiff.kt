package com.photoframe.data.sync

import com.photoframe.data.source.DeletionPolicy
import com.photoframe.data.source.RemotePhoto

/** Minimal view of an already-cached photo, for diffing (keeps [SyncDiff] Android-free/testable). */
data class ExistingPhoto(val remoteId: String, val contentHash: String?)

/** The work a sync run must do for one source: what to fetch, what to remove. */
data class SyncPlan(
    val toDownload: List<RemotePhoto>,
    val toDelete: List<String>,
) {
    val isNoOp: Boolean get() = toDownload.isEmpty() && toDelete.isEmpty()
}

/**
 * Pure diff between a source's desired photo set and what's already cached (DESIGN §6). Kept
 * free of Android/IO so it is unit-testable. Download rule: a remote photo is (re)downloaded
 * when it is new, or when both sides expose a [RemotePhoto.contentHash] and they differ.
 * Delete rule: cached photos absent from the remote set are removed only under
 * [DeletionPolicy.MIRROR]; [DeletionPolicy.KEEP_UNTIL_EVICTED] leaves them for LRU eviction.
 */
object SyncDiff {

    fun plan(
        remote: List<RemotePhoto>,
        existing: List<ExistingPhoto>,
        deletionPolicy: DeletionPolicy,
    ): SyncPlan {
        val existingByeId = existing.associateBy { it.remoteId }
        val remoteIds = remote.mapTo(HashSet(remote.size)) { it.remoteId }

        val toDownload = remote.filter { r ->
            val have = existingByeId[r.remoteId]
            when {
                have == null -> true // new
                r.contentHash != null && have.contentHash != null ->
                    r.contentHash != have.contentHash // changed
                else -> false // present, no reliable hash to compare — leave it
            }
        }

        val toDelete = when (deletionPolicy) {
            DeletionPolicy.MIRROR -> existing.map { it.remoteId }.filter { it !in remoteIds }
            DeletionPolicy.KEEP_UNTIL_EVICTED -> emptyList()
        }

        return SyncPlan(toDownload = toDownload, toDelete = toDelete)
    }
}
