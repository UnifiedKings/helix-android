package com.example.helixapp

import android.content.Context
import coil.request.ImageRequest

/** Image helpers for Helix. */
object HelixImages {
    private const val COOKIE_NAME = "mr_session"

    fun absoluteUrl(baseUrl: String, url: String): String {
        val u = url.trim()
        if (u.isEmpty()) return ""
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        val b = baseUrl.trim().trimEnd('/')
        return if (u.startsWith("/")) "$b$u" else "$b/$u"
    }

    /**
     * Build an ImageRequest that includes the Helix session cookie.
     * This is required for authenticated cover endpoints.
     */
    fun request(context: Context, absoluteUrl: String): ImageRequest {
        val token = HelixPrefs.getSessionToken(context)
        val b = ImageRequest.Builder(context).data(absoluteUrl)
        if (!token.isNullOrBlank()) {
            b.addHeader("Cookie", "$COOKIE_NAME=$token")
        }
        return b.build()
    }
}
