package com.photoframe.data.sync

import com.photoframe.data.source.DeletionPolicy
import com.photoframe.data.source.RemotePhoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncDiffTest {

    private fun remote(id: String, hash: String? = null) = RemotePhoto(remoteId = id, contentHash = hash)
    private fun have(id: String, hash: String? = null) = ExistingPhoto(id, hash)

    @Test fun newPhotos_areInserted() {
        val plan = SyncDiff.plan(
            remote = listOf(remote("a"), remote("b")),
            existing = listOf(have("a")),
            deletionPolicy = DeletionPolicy.MIRROR,
        )
        assertEquals(listOf("b"), plan.toInsert.map { it.remoteId })
        assertTrue(plan.toReindex.isEmpty())
        assertTrue(plan.toDelete.isEmpty())
    }

    @Test fun mirror_deletesVanishedPhotos() {
        val plan = SyncDiff.plan(
            remote = listOf(remote("a")),
            existing = listOf(have("a"), have("gone")),
            deletionPolicy = DeletionPolicy.MIRROR,
        )
        assertTrue(plan.toInsert.isEmpty())
        assertEquals(listOf("gone"), plan.toDelete)
    }

    @Test fun keepUntilEvicted_neverDeletes() {
        val plan = SyncDiff.plan(
            remote = listOf(remote("a")),
            existing = listOf(have("a"), have("gone")),
            deletionPolicy = DeletionPolicy.KEEP_UNTIL_EVICTED,
        )
        assertTrue(plan.toDelete.isEmpty())
    }

    @Test fun changedContentHash_isReindexed() {
        val plan = SyncDiff.plan(
            remote = listOf(remote("a", hash = "new")),
            existing = listOf(have("a", hash = "old")),
            deletionPolicy = DeletionPolicy.MIRROR,
        )
        assertEquals(listOf("a"), plan.toReindex.map { it.remoteId })
        assertTrue(plan.toInsert.isEmpty())
    }

    @Test fun sameHash_isNoOp() {
        val plan = SyncDiff.plan(
            remote = listOf(remote("a", hash = "same")),
            existing = listOf(have("a", hash = "same")),
            deletionPolicy = DeletionPolicy.MIRROR,
        )
        assertTrue(plan.isNoOp)
    }

    @Test fun missingHashOnEitherSide_leavesExistingAlone() {
        // No reliable change signal → don't re-index (would drop the cached file needlessly).
        val plan = SyncDiff.plan(
            remote = listOf(remote("a", hash = null)),
            existing = listOf(have("a", hash = "old")),
            deletionPolicy = DeletionPolicy.MIRROR,
        )
        assertTrue(plan.toInsert.isEmpty())
        assertTrue(plan.toReindex.isEmpty())
    }
}
