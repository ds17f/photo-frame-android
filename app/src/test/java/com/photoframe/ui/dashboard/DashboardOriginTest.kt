package com.photoframe.ui.dashboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardOriginTest {
    @Test fun allowsSameHttpsOriginIncludingDefaultPort() {
        assertTrue(sameOrigin("https://frame.example/", "https://FRAME.example:443/next?q=1"))
        assertTrue(sameOrigin("https://frame.example:8443/", "https://frame.example:8443/next"))
    }

    @Test fun rejectsNavigationOutsideConfiguredOrigin() {
        val url = "https://frame.example/"
        listOf("http://frame.example/", "https://frame.example:8443/", "https://other.example/",
            "https://frame.example.evil/", "https://user:pass@frame.example/", "file:///data/private",
            "javascript:alert(1)", "/relative", "not a URL").forEach {
            assertFalse(it, sameOrigin(url, it))
        }
    }
}
