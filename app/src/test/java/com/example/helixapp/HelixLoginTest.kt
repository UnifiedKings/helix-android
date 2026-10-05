package com.example.helixapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HelixLoginTest {

    @Test
    fun keepsAFullAddressAndDropsTheTrailingSlash() {
        assertEquals("https://helix.example.com", HelixLogin.normalizeServerUrl("  https://helix.example.com/ "))
        assertEquals("http://192.168.1.5:10011", HelixLogin.normalizeServerUrl("http://192.168.1.5:10011"))
    }

    @Test
    fun assumesHttpsWhenNoSchemeIsTyped() {
        assertEquals("https://helix.example.com", HelixLogin.normalizeServerUrl("helix.example.com"))
    }

    @Test
    fun rejectsUnusableAddresses() {
        assertNull(HelixLogin.normalizeServerUrl(""))
        assertNull(HelixLogin.normalizeServerUrl("ftp://helix.example.com"))
        assertNull(HelixLogin.normalizeServerUrl("not a url"))
    }

    @Test
    fun readsTheSessionCookie() {
        assertEquals(
            "abc123",
            HelixLogin.sessionTokenFrom(listOf("other=1; Path=/", "mr_session=abc123; Path=/; HttpOnly")),
        )
        assertNull(HelixLogin.sessionTokenFrom(listOf("other=1")))
        assertNull(HelixLogin.sessionTokenFrom(listOf("mr_session=; Max-Age=0")))
    }
}
