package com.photoframe.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceConfigTest {

    @Test fun immichConfig_roundTripsThroughJson() {
        val config = ImmichConfig(
            baseUrl = "http://host:2283/",
            selection = ImmichSelection(
                albumIds = listOf("alb-1", "alb-2"),
                personIds = listOf("p-1"),
                favoritesOnly = true,
                wholeLibrary = true,
                rollingWindowDays = 365,
            ),
        )
        val decoded = sourceConfigFromJson(config.toJson())
        assertEquals(config, decoded)
    }

    @Test fun emptySelection_isDetected() {
        assertTrue(ImmichSelection().isEmpty)
        assertTrue(!ImmichSelection(favoritesOnly = true).isEmpty)
        assertTrue(!ImmichSelection(albumIds = listOf("x")).isEmpty)
    }
}
