package com.example.helixapp.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

object PlaybackController {

    @Volatile
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    @Volatile
    private var controller: MediaController? = null

    /**
     * Run [onReady] with the shared MediaController, always on the controller's application
     * (main) thread: MediaController throws when called from any other thread. Callers on
     * background threads (e.g. the realtime websocket sync) get their work posted, in order.
     */
    @Synchronized
    fun get(ctx: Context, onReady: (MediaController) -> Unit) {
        val existing = controller?.takeIf { it.isConnected }
        if (existing == null && controller != null) {
            // The service was destroyed (e.g. after the task was removed) and took the
            // connection with it. Drop the dead controller and connect again below.
            Log.w("HELIX_PLAYER", "MediaController disconnected; reconnecting")
            controller = null
            controllerFuture = null
        }
        if (existing != null) {
            val looper = existing.applicationLooper
            if (Looper.myLooper() == looper) {
                onReady(existing)
            } else {
                Handler(looper).post { onReady(existing) }
            }
            return
        }

        var future = controllerFuture
        if (future == null) {
            // Bind with the application context. Callers often pass an Activity (a screen's
            // LocalContext); Android force-unbinds an Activity's connections when it's destroyed
            // (e.g. the app is swiped away), and the later release() then crashed with
            // "Service not registered".
            val appCtx = ctx.applicationContext
            val token = SessionToken(appCtx, ComponentName(appCtx, PlaybackService::class.java))
            future = MediaController.Builder(appCtx, token).buildAsync()
            controllerFuture = future
        }

        future.addListener(
            {
                val c = try {
                    future.get()
                } catch (e: Exception) {
                    // Connecting to PlaybackService failed. Forget the failed attempt so the
                    // next call retries, instead of rethrowing the same failure forever.
                    Log.e("HELIX_PLAYER", "MediaController connection failed", e)
                    synchronized(this) {
                        if (controllerFuture === future) controllerFuture = null
                    }
                    return@addListener
                }
                controller = c
                Log.d("HELIX_PLAYER", "MediaController ready")
                onReady(c)
            },
            androidx.core.content.ContextCompat.getMainExecutor(ctx)
        )
    }

    fun setCurrentItem(
        ctx: Context,
        current: QueueMediaItem,
        autoplay: Boolean,
    ) {
        get(ctx) { c ->
            val mediaItem = current.toMediaItem()
            val existingId = c.currentMediaItem?.mediaId
            val existingUri = c.currentMediaItem?.localConfiguration?.uri
            val desiredUri = mediaItem.localConfiguration?.uri

            if (
                c.mediaItemCount != 1 ||
                existingId != current.queueItemId ||
                existingUri != desiredUri
            ) {
                Log.d(
                    "HELIX_PLAYER",
                    "setCurrentItem(): replacing Media3 timeline with current=${current.queueItemId} autoplay=$autoplay",
                )
                c.setMediaItem(mediaItem, true)
                c.prepare()
            }

            if (autoplay) c.play() else c.pause()
        }
    }

    fun clear(ctx: Context) {
        get(ctx) { c ->
            Log.d("HELIX_PLAYER", "clear(): removing stale Media3 item")
            c.pause()
            c.stop()
            c.clearMediaItems()
        }
    }

    /** Stop and unload local playback, if anything is loaded. Quiet no-op otherwise. */
    fun clearIfLoaded(ctx: Context) {
        get(ctx) { c ->
            if (c.mediaItemCount == 0) return@get
            Log.d("HELIX_PLAYER", "clearIfLoaded(): this device is not playing audio; unloading")
            c.pause()
            c.stop()
            c.clearMediaItems()
        }
    }

    @Synchronized
    fun release() {
        val existingFuture = controllerFuture ?: return
        controllerFuture = null
        controller = null
        runCatching { MediaController.releaseFuture(existingFuture) }
            .onFailure { Log.w("HELIX_PLAYER", "MediaController release failed", it) }
    }

    private fun QueueMediaItem.toMediaItem(): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .apply {
                if (artworkUrl.isNotBlank()) {
                    setArtworkUri(Uri.parse(artworkUrl))
                }
            }
            .build()

        return MediaItem.Builder()
            .setMediaId(queueItemId)
            .setUri(url)
            .setMediaMetadata(meta)
            .build()
    }

    fun pause(ctx: Context) {
        get(ctx) { it.pause() }
    }

    fun resume(ctx: Context) {
        get(ctx) { it.play() }
    }
}

data class QueueMediaItem(
    val queueItemId: String,
    val url: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: String,
)
