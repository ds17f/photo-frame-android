package com.photoframe.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardSettingsTest {
    @Test fun acceptsHttpsDashboardAddresses() {
        listOf(
            "https://dashboard.example.com/frame",
            "HTTPS://example.com:8443/dashboard?frame=kitchen",
            "https://192.168.1.20/frame",
            "https://[::1]/frame",
        ).forEach { assertTrue(it, isValidDashboardUrl(it)) }
    }

    @Test fun rejectsUnsafeOrMalformedAddresses() {
        listOf(
            "", " ", "http://example.com/frame", "file:///etc/passwd",
            "javascript:alert(1)", "https:///frame", "https://",
            "https://user:password@example.com/frame", "https://user@example.com",
            "https://example.com:99999", "https://example.com:0",
            "https://example.com/a b", " https://example.com",
        ).forEach { assertFalse(it, isValidDashboardUrl(it)) }
    }
}
