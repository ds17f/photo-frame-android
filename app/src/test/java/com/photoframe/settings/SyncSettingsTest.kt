package com.photoframe.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncSettingsTest {

    private val gb = 1024L * 1024 * 1024
    private val mb = 1024L * 1024

    @Test fun defaultCap_is1GB() {
        // Streaming-cache model: the retained pool defaults to a modest 1 GB buffer, not 4 GB.
        assertEquals(1 * gb, SyncSettings().cacheCapBytes)
    }

    @Test fun capRange_is256MBto32GB() {
        assertEquals(256 * mb, SyncSettings.CACHE_CAP_RANGE.start)
        assertEquals(32 * gb, SyncSettings.CACHE_CAP_RANGE.endInclusive)
    }

    @Test fun defaultCap_isWithinRange() {
        assertTrue(SyncSettings().cacheCapBytes in SyncSettings.CACHE_CAP_RANGE)
    }
}
