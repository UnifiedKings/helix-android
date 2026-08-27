package com.example.helixapp.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.ForwardingPlayer
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.helixapp.MainActivity
import com.example.helixapp.HelixPrefs
import com.example.helixapp.HelixClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class PlaybackService : MediaSessionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer

    private lateinit var httpFactory: DefaultHttpDataSource.Factory

    @Volatile private var lastEndedAtMs: Long = 0L
    @Volatile private var lastEndedUri: String? = null

    @Volatile private var lastStreamErrorUri: String? = null
    @Volatile private var streamErrorRetryCount: Int = 0

    private val noisyAudioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return

            Log.i("HELIX_PLAYER", "Audio output disconnected; pausing playback")
            player.pause()

            // Keep the backend player state aligned with the local pause. Without this, the
            // next state refresh can see is_playing=true and immediately resume playback.
            scope.launch {
                runCatching {
                    val api = HelixClient.create(
                        this@PlaybackService,
                        HelixPrefs.getBaseUrl(this@PlaybackService)
                    )
                    withContext(Dispatchers.IO) { api.pause() }
                }.onFailure {
                    Log.e("HELIX_PLAYER", "Backend pause after audio disconnect failed", it)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Force a one-time backend sync before the first play after process start.
        // Otherwise the player may resume a stale, previously loaded media item.
        HelixTransport.resetSyncState()

        createNotificationChannel()
        setMediaNotificationProvider(DefaultMediaNotificationProvider(this))

        httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory)
            )
            .build()
        val sessionPlayer: Player = HelixForwardingPlayer(player)

        session = MediaSession.Builder(this, sessionPlayer)
            .setCallback(HelixSessionCallback(this, sessionPlayer, scope))
            // Tapping the system media notification (and lockscreen card) should open Helix
            // directly into the Now Playing tab.
            .setSessionActivity(buildNowPlayingPendingIntent())
            .build()

        // Log useful state transitions + errors.
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                Log.d(
                    "HELIX_PLAYER",
                    "playbackState=$state isPlaying=${player.isPlaying} items=${player.mediaItemCount}",
                )

                if (state == Player.STATE_ENDED) {
    // ExoPlayer contains only the real current track. Natural completion therefore always lands
    // here; notify Helix so the backend advances its authoritative queue, then load the new current.

    // Guard against tight loops if playback ends immediately (e.g., auth failures, short/invalid streams).
    val uri = player.currentMediaItem?.localConfiguration?.uri?.toString()
    val nowMs = SystemClock.elapsedRealtime()
    val dur = player.duration
    val pos = player.currentPosition

    val sameItemFast = (uri != null && uri == lastEndedUri && (nowMs - lastEndedAtMs) < ENDED_COOLDOWN_MS)
    val endedSuspiciouslyEarly = (dur > 0 && pos >= 0 && pos < (dur - ENDED_EARLY_TOLERANCE_MS))

    if (sameItemFast) {
        Log.w("HELIX_PLAYER", "STATE_ENDED ignored (cooldown) uri=$uri")
        return
    }
    if (endedSuspiciouslyEarly) {
        Log.w("HELIX_PLAYER", "STATE_ENDED ignored (ended early) pos=$pos dur=$dur uri=$uri")
        lastEndedAtMs = nowMs
        lastEndedUri = uri
        return
    }

    lastEndedAtMs = nowMs
    lastEndedUri = uri

    scope.launch {
        Log.d("HELIX_PLAYER", "STATE_ENDED -> notifying backend /api/playback/ended")
        HelixTransport.backendEndedAndRefresh(this@PlaybackService)
    }
}
            }

            override fun onPlayerError(error: PlaybackException) {
                val uri = player.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty()
                Log.e("HELIX_PLAYER", "ExoPlayer error=${error.errorCodeName} uri=$uri", error)

                // Station / YT-backed tracks can return HTTP 404/503 for a few seconds while
                // Helix is resolving the YT id or creating the progressive .part file. Treat
                // those as a temporary stream-not-ready condition instead of a permanent failure.
                if (shouldRetryTemporaryStreamError(error, uri)) {
                    if (lastStreamErrorUri != uri) {
                        lastStreamErrorUri = uri
                        streamErrorRetryCount = 0
                    }

                    if (streamErrorRetryCount < MAX_STREAM_ERROR_RETRIES) {
                        val attempt = ++streamErrorRetryCount
                        scope.launch {
                            delay(STREAM_ERROR_RETRY_DELAY_MS * attempt)
                            Log.w("HELIX_PLAYER", "Retrying stream after temporary HTTP error attempt=$attempt uri=$uri")
                            refreshAuthHeaders()
                            runCatching {
                                player.prepare()
                                player.play()
                            }.onFailure {
                                Log.e("HELIX_PLAYER", "Stream retry failed before ExoPlayer request", it)
                            }
                        }
                    }
                }
            }
        })

        registerNoisyAudioReceiver()

        // Ensure our first request has correct cookie.
        refreshAuthHeaders()
    }

    
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_REFRESH_AUTH) {
        Log.d("HELIX_PLAYER", "Received ACTION_REFRESH_AUTH")
        refreshAuthHeaders()
    }
    return super.onStartCommand(intent, flags, startId)
}

override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession {
    return requireNotNull(session)
}

    override fun onDestroy() {
        unregisterNoisyAudioReceiver()
        session?.release()
        session = null
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun registerNoisyAudioReceiver() {
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(noisyAudioReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(noisyAudioReceiver, filter)
        }
    }

    private fun unregisterNoisyAudioReceiver() {
        runCatching { unregisterReceiver(noisyAudioReceiver) }
    }

    fun refreshAuthHeaders() {
        val token = HelixPrefs.getSessionToken(this).orEmpty()
        if (token.isBlank()) {
            Log.w("HELIX_PLAYER", "No mr_session token available; stream requests may 401")
            return
        }

        Log.d("HELIX_PLAYER", "Setting stream Cookie header (len=${token.length})")
        httpFactory.setDefaultRequestProperties(
            mapOf(
                "Cookie" to "mr_session=$token",
            )
        )
    }


    private fun shouldRetryTemporaryStreamError(error: PlaybackException, uri: String): Boolean {
        if (!uri.contains("/api/stream/")) return false
        if (error.errorCodeName != "ERROR_CODE_IO_BAD_HTTP_STATUS") return false

        val msg = buildString {
            append(error.message.orEmpty())
            var cause = error.cause
            while (cause != null) {
                append(' ')
                append(cause.message.orEmpty())
                cause = cause.cause
            }
        }

        return msg.contains("404") || msg.contains("503")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Helix Playback",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNowPlayingPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            // Ensure we reuse the existing activity when possible.
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_NOW_PLAYING, true)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)

        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    
    /**
     * A thin Player wrapper that advertises fake previous/next transport capability to Android.
     *
     * ExoPlayer itself contains only one real media item. The fake capabilities exist solely so
     * lock-screen, notification, headset, and other Media3 controllers show Previous/Next. The
     * corresponding commands are consumed by HelixSessionCallback and forwarded to the backend.
     */
    private class HelixForwardingPlayer(delegate: Player) : ForwardingPlayer(delegate) {

        private fun canExposeTransport(): Boolean = currentMediaItem != null

        override fun hasNextMediaItem(): Boolean {
            return super.hasNextMediaItem() || canExposeTransport()
        }

        override fun hasPreviousMediaItem(): Boolean {
            return super.hasPreviousMediaItem() || canExposeTransport()
        }

        override fun isCommandAvailable(command: Int): Boolean {
            if (canExposeTransport() && (
                    command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                    command == Player.COMMAND_SEEK_TO_NEXT ||
                    command == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ||
                    command == Player.COMMAND_SEEK_TO_PREVIOUS
                )
            ) {
                return true
            }
            return super.isCommandAvailable(command)
        }

        override fun getAvailableCommands(): Player.Commands {
            val base = super.getAvailableCommands()
            if (!canExposeTransport()) return base

            return Player.Commands.Builder()
                .addAll(base)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .build()
        }
    }

companion object {
    const val CHANNEL_ID = "helix_playback"

    // When the user logs in/out, the session cookie changes; streaming requests need to update headers.
    const val ACTION_REFRESH_AUTH = "com.example.helixapp.action.REFRESH_AUTH"

    // Avoid spamming the backend if ExoPlayer reports ENDED in a tight loop.
    private const val ENDED_COOLDOWN_MS = 1500L
    private const val ENDED_EARLY_TOLERANCE_MS = 1000L
    private const val MAX_STREAM_ERROR_RETRIES = 8
    private const val STREAM_ERROR_RETRY_DELAY_MS = 1500L
}
}
