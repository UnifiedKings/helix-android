package com.example.helixapp.playback

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether this phone plays Helix audio itself ("Play on this device"), stored per device.
 *
 * Every open Helix client follows the shared backend state and streams its own audio, with
 * no position sync between them. Turning this off makes the phone a remote: it still shows
 * and controls the shared queue and transport, but never loads or plays a stream locally.
 */
object DevicePlayback {
    private const val PREFS = "helix_prefs"
    private const val KEY_PLAY_ON_DEVICE = "play_on_device"
    private const val KEY_KEEP_PLAYING_WHEN_CLOSED = "keep_playing_when_closed"

    private val _enabled = MutableStateFlow(true)

    @Volatile
    private var loaded = false

    /** Live value for screens. Call [isEnabled] once first so it reflects the saved setting. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun isEnabled(ctx: Context): Boolean {
        if (!loaded) {
            _enabled.value = ctx.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_PLAY_ON_DEVICE, true)
            loaded = true
        }
        return _enabled.value
    }

    /**
     * Whether playback continues in the background after the app is swiped away from recent
     * apps (on by default). When off, closing the app stops playback and pauses the backend.
     */
    fun keepsPlayingWhenClosed(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_KEEP_PLAYING_WHEN_CLOSED, true)

    fun setKeepsPlayingWhenClosed(ctx: Context, keep: Boolean) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_KEEP_PLAYING_WHEN_CLOSED, keep)
            .apply()
    }

    fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PLAY_ON_DEVICE, enabled)
            .apply()
        loaded = true
        _enabled.value = enabled
    }
}
