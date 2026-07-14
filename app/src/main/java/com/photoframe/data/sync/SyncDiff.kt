package com.photoframe.data.sync

import com.photoframe.data.source.DeletionPolicy
import com.photoframe.data.source.RemotePhoto

/** Minimal view of an already-indexed photo, for diffing (keeps [SyncDiff] Android-free/testable). */
data class ExistingPhoto(val remoteId: String, val contentHash: String?)

/**
 * The index changes a metadata-sync run must apply for one source (streaming-cache model). No
 * downloads happen here — the prefetcher fetches files on demand from the resulting index.
 */
data class MetadataPlan(
    /** Remote photos not yet in the index → insert as `INDEXED` (metadata only, no file). */
    val toInsert: List<RemotePhoto>,
    /** Existing photos whose `contentHash` changed → drop the stale file, back to `INDEXED`. */
    val toReindex: List<RemotePhoto>,
    /** Indexed photos absent from the remote set → remove entirely (MIRROR only). */
    val toDelete: List<String>,
) {
    val isNoOp: Boolean get() = toInsert.isEmpty() && toReindex.isEmpty() && toDelete.isEmpty()
}

/**
 * Pure diff between a source's desired photo set and the current index (streaming-cache model). Kept
 * free of Android/IO so it is unit-testable.
 *
 * - **New** remote photos become `INDEXED` rows (no download; the prefetcher pulls them on demand).
 * - A photo whose `contentHash` **changed** (both sides expose one and they differ) is re-indexed:
 *   the syncer drops its stale file and flips it back to `INDEXED` for re-fetch.
 * - Unchanged existing photos are left completely alone — steady-state sync writes nothing, so a
 *   30-min pass over a 60k index is cheap.
 * - **Vanished** photos are removed only under [DeletionPolicy.MIRROR]; keep-until-evicted leaves
 *   them in the index (they simply stop being refreshed).
 */
object SyncDiff {

    fun plan(
        remote: List<RemotePhoto>,
        existing: List<ExistingPhoto>,
        deletionPolicy: DeletionPolicy,
    ): MetadataPlan {
        val existingById = existing.associateBy { it.remoteId }
        val remoteIds = remote.mapTo(HashSet(remote.size)) { it.remoteId }

        val toInsert = ArrayList<RemotePhoto>()
        val toReindex = ArrayList<RemotePhoto>()
        for (r in remote) {
            val have = existingById[r.remoteId]
            when {
                have == null -> toInsert += r
                r.contentHash != null && have.contentHash != null && r.contentHash != have.contentHash ->
                    toReindex += r
                // else: present, no reliable change signal — leave it untouched.
            }
        }

        val toDelete = when (deletionPolicy) {
            DeletionPolicy.MIRROR -> existing.map { it.remoteId }.filter { it !in remoteIds }
            DeletionPolicy.KEEP_UNTIL_EVICTED -> emptyList()
        }

        return MetadataPlan(toInsert = toInsert, toReindex = toReindex, toDelete = toDelete)
    }
}
