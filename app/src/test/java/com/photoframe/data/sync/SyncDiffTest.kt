package com.photoframe.data.sync

import com.photoframe.data.source.DeletionPolicy
import com.photoframe.data.sync.SyncDiff.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncDiffTest {

    // ---- classify --------------------------------------------------------------------------

    @Test fun newPhoto_isInserted() {
        assertEquals(Action.INSERT, SyncDiff.classify(remoteHash = "a", existingHash = null, exists = false))
    }

    @Test fun changedHash_isReindexed() {
        assertEquals(Action.REINDEX, SyncDiff.classify(remoteHash = "new", existingHash = "old", exists = true))
    }

    @Test fun sameHash_isSkipped() {
        assertEquals(Action.SKIP, SyncDiff.classify(remoteHash = "same", existingHash = "same", exists = true))
    }

    @Test fun missingHashOnEitherSide_isSkipped() {
        // No reliable change signal → don't drop the cached file needlessly.
        assertEquals(Action.SKIP, SyncDiff.classify(remoteHash = null, existingHash = "old", exists = true))
        assertEquals(Action.SKIP, SyncDiff.classify(remoteHash = "new", existingHash = null, exists = true))
    }

    // ---- toDelete --------------------------------------------------------------------------

    @Test fun mirror_deletesUnseenExisting() {
        val toDelete = SyncDiff.toDelete(
            existingIds = setOf("a", "gone"),
            seenIds = setOf("a"),
            deletionPolicy = DeletionPolicy.MIRROR,
        )
        assertEquals(listOf("gone"), toDelete)
    }

    @Test fun mirror_keepsSeen() {
        val toDelete = SyncDiff.toDelete(
            existingIds = setOf("a", "b"),
            seenIds = setOf("a", "b"),
            deletionPolicy = DeletionPolicy.MIRROR,
        )
        assertTrue(toDelete.isEmpty())
    }

    @Test fun keepUntilEvicted_neverDeletes() {
        val toDelete = SyncDiff.toDelete(
            existingIds = setOf("a", "gone"),
            seenIds = setOf("a"),
            deletionPolicy = DeletionPolicy.KEEP_UNTIL_EVICTED,
        )
        assertTrue(toDelete.isEmpty())
    }
}
