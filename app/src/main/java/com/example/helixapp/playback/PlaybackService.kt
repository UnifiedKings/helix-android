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
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.helixapp.AuthState
import com.example.helixapp.HelixClient
import com.example.helixapp.HelixPrefs
import com.example.helixapp.MainActivity
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class PlaybackService : MediaSessionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var httpFactory: DefaultHttpDataSource.Factory

    @Volatile private var closingFromTaskRemoval = false

    @Volatile private var lastEndedAtMs: Long = 0L
    @Volatile private var lastEndedUri: String? = null

    @Volatile private var lastStreamErrorUri: String? = null
    @Volatile private var streamErrorRetryCount: Int = 0

    @Volatile private var earlyEndUri: String? = null
    @Volatile private var earlyEndRetryCount: Int = 0

    // Track handoff (screen-off autoplay fix).
    //
    // The Media3 timeline only ever holds the current track. When it ends, ExoPlayer goes to
    // STATE_ENDED and two things happen that break autoplay with the screen locked:
    //   1. ExoPlayer drops its wake lock and audio stops, so the CPU can suspend before the
    //      /ended -> /state -> load-next-stream round trip finishes.
    //   2. Media3 sees "not playing" and demotes the service out of the foreground. When the
    //      next track then calls play(), Android 12+ refuses to re-promote a background app
    //      (ForegroundServiceStartNotAllowedException), so playback stalls until the user
    //      unlocks the phone.
    // While a handoff is active we hold a partial wake lock and suppress Media3 notification
    // updates so the service stays in the foreground until the next track is READY.
    @Volatile private var handoffActive = false
    private var handoffTimeoutJob: Job? = null
    private lateinit var handoffWakeLock: PowerManager.WakeLock

    private val noisyAudioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return

            Log.i("HELIX_PLAYER", "Audio output disconnected; pausing playback")
            // Stay paused even if another device advances the queue; don't resume on the speaker.
            HelixTransport.holdLocalPlayback()
            player.pause()

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

        HelixTransport.resetSyncState()

        createNotificationChannel()
        setMediaNotificationProvider(DefaultMediaNotificationProvider(this))

        handoffWakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "helix:track-handoff")
            .apply { setReferenceCounted(false) }

        httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory)
            )
            // Keep CPU + Wi-Fi awake while buffering/playing a stream with the screen off.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // Let ExoPlayer request audio focus: pause for calls and other media apps, duck
            // under navigation prompts. Permanent focus loss is mirrored to the backend below.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus= */ true,
            )
            .build()

        // Media3 may receive an is_playing=true snapshot almost immediately after the app opens.
        // Block play commands until the backend has first been forced into a paused state.
        val sessionPlayer: Player = HelixForwardingPlayer(player) {
            !closingFromTaskRemoval
        }

        // Artwork for the notification and lock screen is loaded by the session from artworkUri.
        // Reuse the stream's HTTP factory so those requests carry the Helix session cookie;
        // Subsonic cover URLs need it, and the default loader sends no cookie.
        val bitmapLoader = CacheBitmapLoader(
            DataSourceBitmapLoader(
                MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()),
                DefaultDataSource.Factory(this, httpFactory),
            )
        )

        session = MediaSession.Builder(this, sessionPlayer)
            .setCallback(HelixSessionCallback(this, sessionPlayer, scope))
            .setSessionActivity(buildNowPlayingPendingIntent())
            .setBitmapLoader(bitmapLoader)
            .build()

        player.addListener(object : Player.Listener {
            private var lastMetadataTitle: String? = null
            private var lastMetadataArtist: String? = null

            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                val newTitle = mediaMetadata.title?.toString()
                val newArtist = mediaMetadata.artist?.toString()

                // Deduplicate metadata events (live streams fire ID3 tags constantly)
                if (newTitle == lastMetadataTitle && newArtist == lastMetadataArtist) {
                    return
                }

                lastMetadataTitle = newTitle
                lastMetadataArtist = newArtist

                // ID3 tags arrived and the song has genuinely changed.
                // Wake the CPU to ensure the lock screen can redraw in Doze mode.
                val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                val wakeLock = powerManager.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "HelixApp::ID3TagWakeLock"
                )
                wakeLock.acquire(3 * 1000L)

                Log.d("HELIX_PLAYER", "ID3 Tags changed, forcing notification redraw: title=$newTitle artist=$newArtist")

                // Force MediaSessionService to immediately invalidate its cached notification
                // and push the fresh ID3 tags to the lock screen, without touching the Timeline
                // (which would destructively reset the audio buffer and seek position).
                session?.let { s ->
                    s.setCustomLayout(s.customLayout)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Keeps the realtime connection alive in the background only while audible.
                PlayerRealtime.setLocalPlaybackActive(isPlaying)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // Another app took audio focus for good (transient losses such as calls are
                // suppressed and resumed by ExoPlayer itself). Tell the backend we paused, or the
                // next backend sync would call play() and grab focus straight back.
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                    Log.i("HELIX_PLAYER", "Audio focus lost; pausing backend")
                    scope.launch {
                        runCatching { PlayerCommandCoordinator.pause(this@PlaybackService) }
                            .onFailure { Log.e("HELIX_PLAYER", "Backend pause after focus loss failed", it) }
                    }
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                Log.d(
                    "HELIX_PLAYER",
                    "playbackState=$state isPlaying=${player.isPlaying} items=${player.mediaItemCount}",
                )

                if (state == Player.STATE_ENDED) {
                    val uri = player.currentMediaItem?.localConfiguration?.uri?.toString()
                    val nowMs = SystemClock.elapsedRealtime()
                    val dur = player.duration
                    val pos = player.currentPosition

                    val sameItemFast =
                        uri != null &&
                        uri == lastEndedUri &&
                        (nowMs - lastEndedAtMs) < ENDED_COOLDOWN_MS
                    val endedSuspiciouslyEarly =
                        dur > 0 &&
                        pos >= 0 &&
                        pos < (dur - ENDED_EARLY_TOLERANCE_MS)

                    if (sameItemFast) {
                        Log.w("HELIX_PLAYER", "STATE_ENDED ignored (cooldown) uri=$uri")
                        return
                    }
                    if (endedSuspiciouslyEarly) {
                        // The stream was cut off (e.g. the connection dropped mid-track). Resume
                        // from where it stopped instead of going silent; give up after a few
                        // attempts and treat the track as finished so playback moves on.
                        if (uri != earlyEndUri) {
                            earlyEndUri = uri
                            earlyEndRetryCount = 0
                        }
                        if (earlyEndRetryCount < MAX_EARLY_END_RETRIES) {
                            earlyEndRetryCount++
                            Log.w(
                                "HELIX_PLAYER",
                                "STATE_ENDED early; resuming attempt=$earlyEndRetryCount pos=$pos dur=$dur uri=$uri"
                            )
                            beginHandoff("stream ended early")
                            player.seekTo(pos)
                            player.play()
                            return
                        }
                        Log.w(
                            "HELIX_PLAYER",
                            "STATE_ENDED early again; treating as ended pos=$pos dur=$dur uri=$uri"
                        )
                    }

                    lastEndedAtMs = nowMs
                    lastEndedUri = uri

                    if (SleepTimer.consumeTrackEnd()) {
                        // Sleep timer set to "end of track": advance the queue so the next song
                        // is ready later, but stay silent and leave the session paused (the
                        // server's /ended marks it playing again).
                        HelixTransport.holdLocalPlayback()
                        scope.launch {
                            runCatching { PlayerCommandCoordinator.trackEnded(this@PlaybackService) }
                                .onFailure { Log.e("HELIX_PLAYER", "Advancing after sleep timer failed", it) }
                            runCatching { PlayerCommandCoordinator.pause(this@PlaybackService) }
                                .onFailure { Log.e("HELIX_PLAYER", "Pausing after sleep timer failed", it) }
                        }
                        return
                    }

                    // Must be set synchronously, before Media3's notification manager reacts
                    // to STATE_ENDED and demotes the service.
                    beginHandoff("track ended")

                    scope.launch {
                        Log.d(
                            "HELIX_PLAYER",
                            "STATE_ENDED -> notifying backend /api/playback/ended"
                        )
                        try {
                            PlayerCommandCoordinator.trackEnded(this@PlaybackService)
                        } catch (e: Exception) {
                            Log.e("HELIX_PLAYER", "Track handoff failed", e)
                            endHandoff("handoff error")
                        }
                    }
                    return
                }

                if (handoffActive) {
                    when {
                        state == Player.STATE_READY -> endHandoff("next track ready")
                        state == Player.STATE_IDLE && player.playerError == null ->
                            endHandoff("player cleared")
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val uri = player.currentMediaItem
                    ?.localConfiguration
                    ?.uri
                    ?.toString()
                    .orEmpty()

                Log.e(
                    "HELIX_PLAYER",
                    "ExoPlayer error=${error.errorCodeName} uri=$uri",
                    error
                )

                val httpStatus = generateSequence(error.cause) { it.cause }
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                    .firstOrNull()
                    ?.responseCode
                if (httpStatus == 401 && uri.contains("/api/stream/")) {
                    // The stream rejected the session cookie; retrying can't help until login.
                    AuthState.markExpired("stream")
                    endHandoff("session expired")
                    return
                }

                if (isRetryableStreamError(uri, httpStatus)) {
                    if (lastStreamErrorUri != uri) {
                        lastStreamErrorUri = uri
                        streamErrorRetryCount = 0
                    }

                    if (streamErrorRetryCount < MAX_STREAM_ERROR_RETRIES) {
                        val attempt = ++streamErrorRetryCount
                        // A retry from a locked screen needs the same protection as a track
                        // handoff, or play() can't bring the service back to the foreground.
                        beginHandoff("stream retry $attempt")
                        scope.launch {
                            delay(STREAM_ERROR_RETRY_DELAY_MS * attempt)
                            // The backend may have replaced or removed this item meanwhile (a
                            // station rebuilding its queue returns 404 for dropped items). Retrying
                            // would prepare()/play() whatever is current now.
                            val currentUri = player.currentMediaItem?.localConfiguration?.uri?.toString()
                            if (currentUri != uri) {
                                Log.d("HELIX_PLAYER", "Skipping stream retry; item changed uri=$uri now=$currentUri")
                                if (currentUri == null) endHandoff("retry target cleared")
                                return@launch
                            }
                            Log.w(
                                "HELIX_PLAYER",
                                "Retrying stream after temporary HTTP error attempt=$attempt uri=$uri"
                            )
                            refreshAuthHeaders()
                            runCatching {
                                player.prepare()
                                player.play()
                            }.onFailure {
                                Log.e(
                                    "HELIX_PLAYER",
                                    "Stream retry failed before ExoPlayer request",
                                    it
                                )
                            }
                        }
                        return
                    }
                }
                endHandoff("unrecoverable player error")
            }
        })

        registerNoisyAudioReceiver()
        refreshAuthHeaders()

        // A cold start opens quietly: the local player starts paused and the backend is left
        // alone. This used to pause the backend, which paused every other Helix client's
        // shared state (the web player then showed "Paused" while still playing). Instead,
        // HelixTransport keeps this phone silent until the user asks to listen here (see
        // HelixTransport.waitingForLocalPlay).
        player.pause()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH_AUTH) {
            Log.d("HELIX_PLAYER", "Received ACTION_REFRESH_AUTH")
            refreshAuthHeaders()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The app's own MediaController must unbind in every case: a bound controller keeps
        // the service from stopping later.
        PlaybackController.release()

        val keepPlaying = DevicePlayback.keepsPlayingWhenClosed(this)
        // Not isPlaybackOngoing(): Media3 keeps the service in the foreground for a while after
        // a pause, so that would also keep a paused phone running. Ask whether it's playing (or
        // switching tracks) right now.
        val playingHere = ::player.isInitialized &&
            (handoffActive || (player.playWhenReady && player.mediaItemCount > 0))
        if (keepPlaying && playingHere) {
            // Playing (or mid track handoff): keep going in the foreground, like other music
            // apps. The notification and lock screen stay, and realtime sync keeps running.
            Log.i("HELIX_PLAYER", "App task removed; playback continues in the background")
            return
        }

        if (keepPlaying) {
            // Not playing here (paused, or this phone is a remote): stop quietly. The backend
            // is left alone so other devices keep playing. Media3 requires the service to stop
            // when playback isn't ongoing.
            Log.i("HELIX_PLAYER", "App task removed while not playing; stopping the service")
            closingFromTaskRemoval = true
            endHandoff("task removed", refreshNotification = false)
            if (::player.isInitialized) {
                player.pause()
                player.stop()
                player.clearMediaItems()
            }
            stopSelf()
            return
        }

        Log.i("HELIX_PLAYER", "App task removed; stopping Helix playback (keep playing is off)")

        closingFromTaskRemoval = true
        endHandoff("task removed", refreshNotification = false)

        if (::player.isInitialized) {
            // Remove the old item immediately, not just its playing state. Android may keep
            // this service alive briefly after the task is swiped away, and a very fast reopen
            // can otherwise reconnect to the same MediaSession and inherit stale metadata/art
            // from the previous track even though the backend queue has already changed.
            player.pause()
            player.stop()
            player.clearMediaItems()
        }

        scope.launch {
            try {
                withTimeoutOrNull(TASK_REMOVAL_PAUSE_TIMEOUT_MS) {
                    val api = HelixClient.create(
                        this@PlaybackService,
                        HelixPrefs.getBaseUrl(this@PlaybackService),
                        startRealtime = false,
                    )
                    withContext(Dispatchers.IO) { api.pause() }
                }
            } catch (e: Exception) {
                Log.w("HELIX_PLAYER", "Backend pause during task removal failed", e)
            } finally {
                stopSelf()
            }
        }

        super.onTaskRemoved(rootIntent)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession {
        return requireNotNull(session)
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (handoffActive) {
            // Hold the current foreground notification through the track handoff. It is
            // refreshed as soon as the handoff ends (see endHandoff).
            return
        }
        super.onUpdateNotification(session, startInForegroundRequired)
    }

    private fun beginHandoff(reason: String) {
        Log.d("HELIX_PLAYER", "Handoff begin ($reason)")
        handoffActive = true
        handoffWakeLock.acquire(HANDOFF_MAX_MS)
        handoffTimeoutJob?.cancel()
        handoffTimeoutJob = scope.launch {
            delay(HANDOFF_MAX_MS)
            endHandoff("timeout")
        }
    }

    private fun endHandoff(reason: String, refreshNotification: Boolean = true) {
        if (!handoffActive) return
        Log.d("HELIX_PLAYER", "Handoff end ($reason)")
        handoffActive = false
        handoffTimeoutJob?.cancel()
        handoffTimeoutJob = null
        if (::handoffWakeLock.isInitialized && handoffWakeLock.isHeld) {
            handoffWakeLock.release()
        }
        // Catch up on any notification/foreground updates suppressed during the handoff.
        if (!refreshNotification) return
        session?.let { s ->
            runCatching { onUpdateNotification(s, false) }
                .onFailure { Log.w("HELIX_PLAYER", "Notification refresh after handoff failed", it) }
        }
    }

    override fun onDestroy() {
        endHandoff("service destroyed", refreshNotification = false)
        PlayerRealtime.setLocalPlaybackActive(false)
        unregisterNoisyAudioReceiver()

        if (::player.isInitialized) {
            player.pause()
            player.release()
        }

        session?.release()
        session = null

        scope.cancel()
        super.onDestroy()
    }

    private fun registerNoisyAudioReceiver() {
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                noisyAudioReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
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
            Log.w(
                "HELIX_PLAYER",
                "No mr_session token available; stream requests may 401"
            )
            return
        }

        Log.d(
            "HELIX_PLAYER",
            "Setting stream Cookie header (len=${token.length})"
        )

        httpFactory.setDefaultRequestProperties(
            mapOf(
                "Cookie" to "mr_session=$token",
            )
        )
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
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_NOW_PLAYING, true)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)

        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private class HelixForwardingPlayer(
        delegate: Player,
        private val canPlay: () -> Boolean,
    ) : ForwardingPlayer(delegate) {

        private fun canExposeTransport(): Boolean = currentMediaItem != null

        override fun play() {
            if (!canPlay()) {
                Log.d(
                    "HELIX_PLAYER",
                    "Ignoring Media3 play while task removal is in progress"
                )
                super.pause()
                return
            }
            super.play()
        }

        override fun hasNextMediaItem(): Boolean {
            return super.hasNextMediaItem() || canExposeTransport()
        }

        override fun hasPreviousMediaItem(): Boolean {
            return super.hasPreviousMediaItem() || canExposeTransport()
        }

        override fun isCommandAvailable(command: Int): Boolean {
            if (
                canExposeTransport() &&
                (
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

        const val ACTION_REFRESH_AUTH =
            "com.example.helixapp.action.REFRESH_AUTH"

        private const val ENDED_COOLDOWN_MS = 1500L
        private const val ENDED_EARLY_TOLERANCE_MS = 1000L
        private const val MAX_STREAM_ERROR_RETRIES = 8
        private const val STREAM_ERROR_RETRY_DELAY_MS = 1500L
        private const val MAX_EARLY_END_RETRIES = 3

        private const val HANDOFF_MAX_MS = 30_000L

        /**
         * HTTP statuses on a Helix stream worth retrying: 404 while a station's next item is
         * still being prepared or the queue is rebuilt, and 502/503/504 for a busy server or a
         * reverse-proxy hiccup.
         */
        private val RETRYABLE_STREAM_STATUSES = setOf(404, 502, 503, 504)

        /** Whether a stream error should be retried. Reads the status code rather than error text. */
        internal fun isRetryableStreamError(uri: String, httpStatus: Int?): Boolean =
            uri.contains("/api/stream/") && httpStatus in RETRYABLE_STREAM_STATUSES

        private const val TASK_REMOVAL_PAUSE_TIMEOUT_MS = 2_000L
    }
}
