package com.example.helixapp.playback

import android.content.Context
import android.util.Log
import com.example.helixapp.HelixPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Process-wide realtime mirror of the web frontend's usePlayer() websocket behavior.
 *
 * Helix remains authoritative. This socket only observes /ws/player and asks the native
 * Media3 transport plus interested screens to refresh when a player.state snapshot arrives.
 *
 * The connection (with its ping and fallback polling) only runs while it is useful: while the
 * app is on screen, or while this phone is playing audio (so it keeps following changes made
 * on other devices). After [IDLE_DISCONNECT_MS] with neither, it disconnects completely; it
 * reconnects and catches up as soon as either becomes true again.
 */
object PlayerRealtime {
    private const val TAG = "HELIX_REALTIME"
    private const val RECONNECT_DELAY_MS = 1_500L
    private const val PING_INTERVAL_MS = 20_000L
    private const val FALLBACK_REFRESH_MS = 15_000L
    // Long enough to ride out track handoffs and quick app switches without reconnecting.
    private const val IDLE_DISCONNECT_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncRequests = Channel<Unit>(Channel.CONFLATED)
    private val client = OkHttpClient.Builder().build()

    @Volatile private var appContext: Context? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile private var socketOpen = false
    @Volatile private var started = false
    @Volatile private var activeConnectionKey = ""
    @Volatile private var lastSequence = 0L
    @Volatile private var lastQueueItemId: String? = null
    @Volatile private var lastIsPlaying: Boolean? = null
    @Volatile private var lastCurrentWasQueued: Boolean? = null
    @Volatile private var appVisible = false
    @Volatile private var localPlaybackActive = false

    private var reconnectJob: Job? = null
    private var pingJob: Job? = null
    private var fallbackJob: Job? = null
    private var idleJob: Job? = null

    @Synchronized
    fun ensureStarted(context: Context) {
        appContext = context.applicationContext
        val currentKey = connectionKey(context)

        if (!started) {
            started = true
            scope.launch {
                for (ignored in syncRequests) {
                    val ctx = appContext ?: continue
                    if (HelixPrefs.getSessionToken(ctx).isNullOrBlank()) continue
                    runCatching { PlayerCommandCoordinator.syncFromBackend(ctx) }
                        .onFailure { Log.w(TAG, "Realtime player sync failed", it) }
                }
            }
            updateConnection()
            return
        }

        if (currentKey != activeConnectionKey) {
            reconnectNow()
        }
    }

    /** MainActivity reports whether the app is on screen (onStart/onStop). */
    fun setAppVisible(visible: Boolean) {
        if (appVisible == visible) return
        appVisible = visible
        updateConnection()
    }

    /** PlaybackService reports whether this phone is currently playing audio. */
    fun setLocalPlaybackActive(active: Boolean) {
        if (localPlaybackActive == active) return
        localPlaybackActive = active
        updateConnection()
    }

    private fun isNeeded(): Boolean = appVisible || localPlaybackActive

    @Synchronized
    private fun updateConnection() {
        if (!started) return
        if (isNeeded()) {
            idleJob?.cancel()
            idleJob = null
            if (fallbackJob?.isActive != true) startFallbackLoop()
            if (socket == null && reconnectJob?.isActive != true) {
                reconnectAttempts = 0
                connect()
            }
            return
        }
        val hasWork = socket != null || fallbackJob?.isActive == true || reconnectJob?.isActive == true
        if (hasWork && idleJob?.isActive != true) {
            idleJob = scope.launch {
                delay(IDLE_DISCONNECT_MS)
                disconnectIfIdle()
            }
        }
    }

    @Synchronized
    private fun disconnectIfIdle() {
        idleJob = null
        if (isNeeded()) return
        Log.d(TAG, "App in background and not playing; disconnecting realtime")
        reconnectJob?.cancel()
        reconnectJob = null
        pingJob?.cancel()
        pingJob = null
        fallbackJob?.cancel()
        fallbackJob = null
        socketOpen = false
        // Forget the last snapshot so the first one after reconnecting always syncs.
        lastSequence = 0L
        lastQueueItemId = null
        lastIsPlaying = null
        lastCurrentWasQueued = null
        val old = socket
        socket = null
        old?.close(1000, "Idle")
    }

    @Synchronized
    private fun reconnectNow() {
        reconnectJob?.cancel()
        reconnectJob = null
        pingJob?.cancel()
        pingJob = null
        socketOpen = false
        lastSequence = 0L
        lastQueueItemId = null
        lastIsPlaying = null
        lastCurrentWasQueued = null
        val old = socket
        socket = null
        old?.close(1000, "Helix connection changed")
        if (isNeeded()) connect()
    }

    @Synchronized
    private fun connect() {
        val ctx = appContext ?: return
        val token = HelixPrefs.getSessionToken(ctx).orEmpty()
        val baseUrl = HelixPrefs.getBaseUrl(ctx).trim().trimEnd('/')

        if (token.isBlank() || baseUrl.isBlank()) {
            activeConnectionKey = connectionKey(ctx)
            scheduleReconnect()
            return
        }

        val socketBase = when {
            baseUrl.startsWith("https://", ignoreCase = true) -> "wss://" + baseUrl.substringAfter("://")
            baseUrl.startsWith("http://", ignoreCase = true) -> "ws://" + baseUrl.substringAfter("://")
            else -> "ws://$baseUrl"
        }
        val url = "$socketBase/ws/player"
        activeConnectionKey = connectionKey(ctx)

        val request = Request.Builder()
            .url(url)
            .header("Cookie", "mr_session=$token")
            .build()

        Log.d(TAG, "Connecting to $url")
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (socket !== webSocket) return
                Log.d(TAG, "Player websocket connected")
                socketOpen = true
                reconnectAttempts = 0
                reconnectJob?.cancel()
                startPingLoop(webSocket)
                // Catch up on anything that changed while disconnected (e.g. in the background).
                syncRequests.trySend(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (socket !== webSocket) return
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket !== webSocket) return
                Log.d(TAG, "Player websocket closed code=$code reason=$reason")
                socketOpen = false
                pingJob?.cancel()
                pingJob = null
                socket = null
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket !== webSocket) return
                Log.w(TAG, "Player websocket failed", t)
                socketOpen = false
                pingJob?.cancel()
                pingJob = null
                socket = null
                scheduleReconnect()
            }
        })
    }

    private fun handleMessage(text: String) {
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (message.optString("type") != "player.state") return
        val state = message.optJSONObject("state") ?: return

        val seq = message.optLong("seq", 0L)
        if (seq > 0L) {
            if (seq <= lastSequence) return
            lastSequence = seq
        }

        // Screens read the snapshot straight from the store; no extra /state fetch needed.
        PlayerStateStore.publish(state)

        val now = state.optJSONObject("now_playing")
        val queueItemId = now
            ?.optString("id", now.optString("queue_item_id", ""))
            ?.takeIf { it.isNotBlank() }
        val isPlaying = state.optBoolean("is_playing", false)

        // A queue rebuild can temporarily leave now_playing pointing at an item that has
        // already been removed from the queue. The queue-item ID may therefore remain the
        // same even though the transport is now invalid. Treat that as a transport change
        // so HelixTransport can reject/clear the orphaned Media3 item immediately.
        val queue = state.optJSONArray("queue")
        var currentIsQueued = queueItemId == null
        if (queueItemId != null && queue != null) {
            currentIsQueued = false
            for (i in 0 until queue.length()) {
                val item = queue.optJSONObject(i) ?: continue
                val itemId = item.optString("id", item.optString("queue_item_id", ""))
                if (itemId == queueItemId) {
                    currentIsQueued = true
                    break
                }
            }
        }

        val transportChanged =
            queueItemId != lastQueueItemId ||
            isPlaying != lastIsPlaying ||
            currentIsQueued != lastCurrentWasQueued ||
            (queueItemId != null && !currentIsQueued)

        lastQueueItemId = queueItemId
        lastIsPlaying = isPlaying
        lastCurrentWasQueued = currentIsQueued

        if (transportChanged) {
            if (queueItemId != null && !currentIsQueued) {
                Log.w(TAG, "Realtime state has orphan now_playing=$queueItemId; forcing transport sync")
            }
            syncRequests.trySend(Unit)
        }
    }

    @Synchronized
    private fun startPingLoop(webSocket: WebSocket) {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive && socket === webSocket && socketOpen) {
                delay(PING_INTERVAL_MS)
                if (socket !== webSocket || !socketOpen) break

                val ctx = appContext ?: break
                if (connectionKey(ctx) != activeConnectionKey) {
                    reconnectNow()
                    break
                }

                if (!webSocket.send("ping")) {
                    webSocket.cancel()
                    break
                }
            }
        }
    }

    private fun startFallbackLoop() {
        fallbackJob?.cancel()
        fallbackJob = scope.launch {
            while (isActive) {
                delay(FALLBACK_REFRESH_MS)
                if (!socketOpen) {
                    // The sync fetches /state, which also updates PlayerStateStore.
                    syncRequests.trySend(Unit)
                }
            }
        }
    }

    private var reconnectAttempts = 0

    @Synchronized
    private fun scheduleReconnect() {
        if (!started || !isNeeded() || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val delayMs = (RECONNECT_DELAY_MS * (1 shl minOf(reconnectAttempts, 6)))
                .coerceAtMost(30_000L)
            reconnectAttempts++
            delay(delayMs)
            reconnectJob = null
            connect()
        }
    }

    private fun connectionKey(context: Context): String {
        return HelixPrefs.getBaseUrl(context).trim().trimEnd('/') + "|" +
            HelixPrefs.getSessionToken(context).orEmpty()
    }
}
