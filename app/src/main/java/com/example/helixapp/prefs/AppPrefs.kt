package com.example.helixapp.prefs

import android.content.Context
import android.content.Intent
import com.example.helixapp.HelixPrefs
import com.example.helixapp.HelixWebSession
import com.example.helixapp.playback.PlaybackService

/** Compatibility wrapper around the app's shared Helix preferences. */
object AppPrefs {
    fun saveBaseUrl(ctx: Context, url: String) {
        HelixPrefs.setBaseUrl(ctx, url)
    }

    fun getBaseUrl(ctx: Context): String? = HelixPrefs.getBaseUrl(ctx)

    fun saveSessionCookie(ctx: Context, cookie: String) {
        HelixPrefs.setSessionToken(ctx, cookie)
        HelixWebSession.sync(ctx)

        // If playback is already running, refresh stream headers immediately.
        runCatching {
            val intent = Intent(ctx, PlaybackService::class.java)
                .setAction(PlaybackService.ACTION_REFRESH_AUTH)
            ctx.startService(intent)
        }
    }

    fun getSessionCookie(ctx: Context): String? = HelixPrefs.getSessionToken(ctx)

    fun clearSession(ctx: Context) {
        HelixWebSession.clear(ctx)
        HelixPrefs.clearAuth(ctx)

        // Best effort: clear auth headers in the playback service too.
        runCatching {
            val intent = Intent(ctx, PlaybackService::class.java)
                .setAction(PlaybackService.ACTION_REFRESH_AUTH)
            ctx.startService(intent)
        }
    }
}
