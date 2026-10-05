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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object PlaybackController {

    private const val PICTURE_TYPE_FRONT_COVER = 3

    @Volatile
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    @Volatile
    private var controller: MediaController? = null

    suspend fun awaitController(ctx: Context): MediaController {
        val existing = controller
        if (existing != null) return existing

        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            get(ctx) { c ->
                if (!cont.isCompleted) cont.resume(c)
            }
        }
    }

    data class Snapshot(
        val mediaId: String?,
        val isPlaying: Boolean,
    )

    suspend fun snapshot(ctx: Context): Snapshot {
        val c = awaitController(ctx)
        return Snapshot(c.currentMediaItem?.mediaId, c.isPlaying)
    }

    suspend fun awaitAudibleStart(
        ctx: Context,
        startSnapshot: Snapshot,
        timeoutMs: Long = 8_000L,
    ): Boolean {
        val c = awaitController(ctx)

        val nowId = c.currentMediaItem?.mediaId
        if (!startSnapshot.isPlaying && c.isPlaying) return true
        if (
            startSnapshot.isPlaying &&
            startSnapshot.mediaId != null &&
            nowId != null &&
            nowId != startSnapshot.mediaId &&
            c.isPlaying
        ) return true

        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                val listener = object : Player.Listener {
                    private fun checkAndResume() {
                        if (cont.isCompleted) return

                        if (!startSnapshot.isPlaying && c.isPlaying) {
                            cont.resume(true)
                            return
                        }
                        if (startSnapshot.isPlaying && c.isPlaying) {
                            val cur = c.currentMediaItem?.mediaId
                            if (
                                startSnapshot.mediaId == null ||
                                cur == null ||
                                cur != startSnapshot.mediaId
                            ) {
                                if (c.playbackState == Player.STATE_READY) {
                                    cont.resume(true)
                                }
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        checkAndResume()
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        checkAndResume()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        checkAndResume()
                    }
                }

                c.addListener(listener)
                cont.invokeOnCancellation { c.removeListener(listener) }

                // Kick off a single check immediately in case it's already audible
                if (!cont.isCompleted) {
                    if (!startSnapshot.isPlaying && c.isPlaying) {
                        cont.resume(true)
                    } else if (startSnapshot.isPlaying && c.isPlaying) {
                        val cur = c.currentMediaItem?.mediaId
                        if (
                            startSnapshot.mediaId == null ||
                            cur == null ||
                            cur != startSnapshot.mediaId
                        ) {
                            if (c.playbackState == Player.STATE_READY) {
                                cont.resume(true)
                            }
                        }
                    }
                }
            }
        } ?: false
    }

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
            val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
            future = MediaController.Builder(ctx, token).buildAsync()
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

    fun playUrl(ctx: Context, url: String, autoplay: Boolean = true) {
        Log.d("HELIX_PLAYER", "playUrl() -> $url")
        get(ctx) { c ->
            val item = MediaItem.fromUri(url)
            c.setMediaItem(item)
            c.prepare()
            if (autoplay) c.play() else c.pause()
        }
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
                artworkData?.let { bytes ->
                    setArtworkData(bytes, PICTURE_TYPE_FRONT_COVER)
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
    val artworkData: ByteArray? = null,
)
