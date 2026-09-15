package com.usesonny.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SonnyConfigurationTest {
    @Test fun `bridge messages are accepted only from the hosted main frame`() {
        val configuration = SonnyConfiguration("site-a", "key-a")
        assertTrue(configuration.acceptsBridgeMessage("https://www.usesonny.com", true))
        assertFalse(configuration.acceptsBridgeMessage("https://www.usesonny.com", false))
        for (origin in listOf("http://www.usesonny.com", "https://www.usesonny.com:8443", "https://evil.example", "null")) {
            assertFalse(configuration.acceptsBridgeMessage(origin, true))
        }
    }

    @Test fun `identity bridge accepts only an HTTPS origin and valid public identifiers`() {
        for (origin in listOf("http://www.usesonny.com", "file:///tmp/chat", "https://user@www.usesonny.com", "https://www.usesonny.com/other", "https://www.usesonny.com?redirect=evil")) {
            assertThrows(IllegalArgumentException::class.java) { SonnyConfiguration("site-a", "key-a", origin) }
        }
        assertThrows(IllegalArgumentException::class.java) { SonnyConfiguration("", "key-a") }
        assertThrows(IllegalArgumentException::class.java) { SonnyConfiguration("site-a", "../another") }
    }

    @Test fun `configuration loads the Sonny hosted versioned bridge`() {
        val configuration = SonnyConfiguration(siteId = "site-a", appKey = "key-a")
        assertEquals(
            "https://www.usesonny.com/embed/widget?siteId=site-a&appKey=key-a&sdk=android%2F1.0.0",
            configuration.embedUrl,
        )
    }
}
