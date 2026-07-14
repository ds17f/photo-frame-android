package com.photoframe.data.source.immich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ImmichClientTest {

    @Test fun normalizeBaseUrl_defaultsToHttpsAndAddsTrailingSlash() {
        assertEquals(
            "https://immich.example.com/",
            ImmichClient.normalizeBaseUrl("immich.example.com"),
        )
    }

    @Test fun normalizeBaseUrl_preservesExplicitHttp() {
        assertEquals("http://192.168.1.5:2283/", ImmichClient.normalizeBaseUrl("http://192.168.1.5:2283"))
    }

    @Test fun normalizeBaseUrl_keepsHttps() {
        assertEquals("https://immich.example.com/", ImmichClient.normalizeBaseUrl("https://immich.example.com"))
    }

    @Test fun normalizeBaseUrl_stripsTrailingSlashesAndApiSuffix() {
        assertEquals("https://host:2283/", ImmichClient.normalizeBaseUrl("host:2283/api/"))
        assertEquals("http://host:2283/", ImmichClient.normalizeBaseUrl("http://host:2283///"))
    }

    @Test fun parseImmichTime_utcInstant() {
        assertEquals(
            Instant.parse("2024-06-01T12:00:00Z"),
            ImmichSource.parseImmichTime("2024-06-01T12:00:00.000Z"),
        )
    }

    @Test fun parseImmichTime_offsetDateTime() {
        assertEquals(
            Instant.parse("2024-06-01T10:00:00Z"),
            ImmichSource.parseImmichTime("2024-06-01T12:00:00+02:00"),
        )
    }

    @Test fun parseImmichTime_nullAndBlankAreNull() {
        assertNull(ImmichSource.parseImmichTime(null))
        assertNull(ImmichSource.parseImmichTime("  "))
    }
}
