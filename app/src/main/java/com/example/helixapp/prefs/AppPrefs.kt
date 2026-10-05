package com.example.helixapp.prefs

import android.content.Context
import android.content.Intent
import com.example.helixapp.HelixPrefs
import com.example.helixapp.playback.PlaybackService
import com.example.helixapp.playback.PlayerStateStore

/** Compatibility wrapper around the app's shared Helix preferences. */
object AppPrefs {
    fun saveBaseUrl(ctx: Context, url: String) {
        HelixPrefs.setBaseUrl(ctx, url)
    }

    fun saveSessionCookie(ctx: Context, cookie: String) {
        HelixPrefs.setSessionToken(ctx, cookie)

        // If playback is already running, refresh stream headers immediately.
        runCatching {
            val intent = Intent(ctx, PlaybackService::class.java)
                .setAction(PlaybackService.ACTION_REFRESH_AUTH)
            ctx.startService(intent)
        }
    }

    fun clearSession(ctx: Context) {
        HelixPrefs.clearAuth(ctx)
        PlayerStateStore.clear()

        // Best effort: clear auth headers in the playback service too.
        runCatching {
            val intent = Intent(ctx, PlaybackService::class.java)
                .setAction(PlaybackService.ACTION_REFRESH_AUTH)
            ctx.startService(intent)
        }
    }
}
