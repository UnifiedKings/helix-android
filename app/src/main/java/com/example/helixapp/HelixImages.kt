package com.example.helixapp

import android.content.Context
import coil.request.ImageRequest
import okhttp3.Request

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

    /**
     * Fetch artwork bytes using the same authenticated client as the rest of Helix.
     *
     * Android's system media notification/quick-settings player does not reliably
     * send the Helix session cookie when it resolves artworkUri itself, so authenticated
     * Subsonic cover URLs can show as a blank placeholder there. Embedding a small
     * artwork byte array in MediaMetadata gives the system UI the image directly.
     */
    fun fetchArtworkBytes(context: Context, absoluteUrl: String, maxBytes: Long = 1_000_000L): ByteArray? {
        val url = absoluteUrl.trim()
        if (url.isBlank()) return null

        return runCatching {
            val req = Request.Builder().url(url).get().build()
            HelixClient.okHttpClient(context).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null

                val body = resp.body ?: return null
                val contentLength = body.contentLength()
                if (contentLength > maxBytes) return null

                val bytes = body.bytes()
                if (bytes.size.toLong() <= maxBytes) bytes else null
            }
        }.getOrNull()
    }

}
