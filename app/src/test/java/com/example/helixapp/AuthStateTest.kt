package com.example.helixapp

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthStateTest {

    @After
    fun tearDown() = AuthState.clear()

    @Test
    fun rejectedSessionCookieIsAnExpiry() {
        assertTrue(AuthState.isSessionExpiry(401, "/api/playback/state", sentSessionCookie = true))
    }

    @Test
    fun wrongPasswordAtLoginIsNotAnExpiry() {
        assertFalse(AuthState.isSessionExpiry(401, "/auth/login", sentSessionCookie = true))
        assertFalse(AuthState.isSessionExpiry(401, "/auth/login/", sentSessionCookie = false))
    }

    @Test
    fun requestWithoutACookieIsNotAnExpiry() {
        // Not logged in at all is a different state from an expired login.
        assertFalse(AuthState.isSessionExpiry(401, "/api/playback/state", sentSessionCookie = false))
    }

    @Test
    fun otherErrorsAreNotAnExpiry() {
        assertFalse(AuthState.isSessionExpiry(403, "/api/admin/users", sentSessionCookie = true))
        assertFalse(AuthState.isSessionExpiry(500, "/api/playback/state", sentSessionCookie = true))
    }

    @Test
    fun loginOrLogoutClearsTheExpiredFlag() {
        AuthState.markExpired("test")
        assertTrue(AuthState.sessionExpired.value)
        AuthState.clear()
        assertFalse(AuthState.sessionExpired.value)
    }
}
