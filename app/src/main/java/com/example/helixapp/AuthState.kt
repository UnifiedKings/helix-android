package com.example.helixapp

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the saved Helix session has stopped working.
 *
 * The app treats "has a session cookie" as logged in, but the server can stop accepting it
 * (expired, revoked, password changed). Any 401 for a request that carried the cookie flips
 * this on: API calls (HelixClient), the audio stream (PlaybackService) and the realtime
 * socket (PlayerRealtime). The UI then asks the user to log in again; a successful login or
 * a logout clears it.
 */
object AuthState {
    private val _sessionExpired = MutableStateFlow(false)
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    fun markExpired(source: String) {
        if (_sessionExpired.value) return
        Log.w("HELIX_PLAYER", "Helix session rejected by the server ($source); asking the user to log in again")
        _sessionExpired.value = true
    }

    fun clear() {
        _sessionExpired.value = false
    }

    /**
     * Whether a response means the session expired: a 401 for a request that sent the session
     * cookie. A 401 from the login request itself just means a wrong password.
     */
    fun isSessionExpiry(code: Int, path: String, sentSessionCookie: Boolean): Boolean =
        code == 401 && sentSessionCookie && !path.trimEnd('/').endsWith("auth/login")
}
