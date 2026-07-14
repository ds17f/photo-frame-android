package com.photoframe.data.sync

import com.photoframe.data.source.DeletionPolicy
import com.photoframe.data.source.RemotePhoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncDiffTest {

    private fun remote(id: String, hash: String? = null) = RemotePhoto(remoteId = id, contentHash = hash)
    private fun have(id: String, hash: String? = null) = ExistingPhoto(id, hash)

    @Test fun newPhotos_areDownloaded() {
        val plan = SyncDiff.plan(
            remote = listOf(remote("a"), remote("b")),
            existing = listOf(have("a")),
            deletionPolicy = DeletionPolicy.MIRROR,
        )
        assertEquals(listOf("b"), plan.toDownload.map { it.remoteId })
        assertTrue(plan.toDelete.isEmpty())
    }

    @Test fun mirror_deletesVanishedPhotos() {
        val plan = SyncDiff.plan(
            remote = listOf(remote("a")),
            existing = listOf(have("a"), have("gone")),
            deletionPolicy = DeletionPolicy.MIRROR,
        )
        assertTrue(plan.toDownload.isEmpty())
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

    @Test fun changedContentHash_isRedownloaded() {
        val plan = SyncDiff.plan(
            remote = listOf(remote("a", hash = "new")),
            existing = listOf(have("a", hash = "old")),
            deletionPolicy = DeletionPolicy.MIRROR,
        )
        assertEquals(listOf("a"), plan.toDownload.map { it.remoteId })
    }

    @Test fun sameHash_isNotRedownloaded() {
        val plan = SyncDiff.plan(
            remote = listOf(remote("a", hash = "same")),
            existing = listOf(have("a", hash = "same")),
            deletionPolicy = DeletionPolicy.MIRROR,
        )
        assertTrue(plan.isNoOp)
    }

    @Test fun missingHashOnEitherSide_leavesExistingAlone() {
        // No reliable comparison possible → don't churn the download.
        val plan = SyncDiff.plan(
            remote = listOf(remote("a", hash = null)),
            existing = listOf(have("a", hash = "old")),
            deletionPolicy = DeletionPolicy.MIRROR,
        )
        assertTrue(plan.toDownload.isEmpty())
    }
}
