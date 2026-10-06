package com.example.helixapp

import android.content.Context
import com.example.helixapp.prefs.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** A login attempt failed; [message] is ready to show to the user. */
class LoginException(message: String) : Exception(message)

/** Logging in to a Helix server, shared by the Welcome screen and Settings > Connection. */
object HelixLogin {
    private const val COOKIE_NAME = "mr_session"

    /**
     * Log in and save the server, username and session. Throws [LoginException] with a
     * user-facing message on failure (network errors are mapped with [toUserMessage]).
     */
    suspend fun logIn(ctx: Context, serverUrl: String, username: String, password: String) {
        val baseUrl = normalizeServerUrl(serverUrl)
            ?: throw LoginException("That server address isn't valid. Example: https://helix.example.com")
        val user = username.trim()
        if (user.isEmpty() || password.isEmpty()) throw LoginException("Enter your username and password")

        AppPrefs.saveBaseUrl(ctx, baseUrl)
        HelixPrefs.setUsername(ctx, user)

        val resp = try {
            val api = HelixClient.create(ctx, baseUrl)
            val body = JSONObject().put("username", user).put("password", password).toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            withContext(Dispatchers.IO) { api.login(body) }
        } catch (e: Exception) {
            throw LoginException(e.toUserMessage("Login"))
        }
        if (!resp.isSuccessful) {
            throw LoginException(
                if (resp.code() == 401) "Wrong username or password" else "Login failed (HTTP ${resp.code()})"
            )
        }
        val token = sessionTokenFrom(resp.headers().values("Set-Cookie"))
            ?: throw LoginException("Logged in, but the server didn't send a session cookie")
        AppPrefs.saveSessionCookie(ctx, token)
    }

    /**
     * Clean up a typed server address: trim it, drop a trailing slash and assume https:// when
     * no scheme is given. Null if it isn't a usable http(s) URL.
     */
    internal fun normalizeServerUrl(input: String): String? {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val withScheme = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(trimmed)) trimmed else "https://$trimmed"
        val url = withScheme.toHttpUrlOrNull() ?: return null
        return if (url.scheme == "http" || url.scheme == "https") withScheme else null
    }

    /** The mr_session value from a login response's Set-Cookie headers. */
    internal fun sessionTokenFrom(setCookieHeaders: List<String>): String? =
        setCookieHeaders.firstNotNullOfOrNull { header ->
            val idx = header.indexOf("$COOKIE_NAME=")
            if (idx < 0) null
            else header.substring(idx + COOKIE_NAME.length + 1).substringBefore(';').trim().ifBlank { null }
        }
}
