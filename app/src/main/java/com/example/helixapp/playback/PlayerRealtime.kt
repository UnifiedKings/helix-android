package com.example.helixapp.playback

import android.content.Context
import android.util.Log
import com.example.helixapp.HelixPrefs
import com.example.helixapp.RefreshSignals
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
 */
object PlayerRealtime {
    private const val TAG = "HELIX_REALTIME"
    private const val RECONNECT_DELAY_MS = 1_500L
    private const val PING_INTERVAL_MS = 20_000L
    private const val FALLBACK_REFRESH_MS = 15_000L

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

    private var reconnectJob: Job? = null
    private var pingJob: Job? = null
    private var fallbackJob: Job? = null

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
            startFallbackLoop()
            connect()
            return
        }

        // Login, logout, or server URL changes should not leave an open socket attached to
        // the previous session/server. HelixClient.create() is called immediately after those
        // changes, so this also makes the switch happen without waiting for the next ping.
        if (currentKey != activeConnectionKey) {
            reconnectNow()
        }
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
        val old = socket
        socket = null
        old?.close(1000, "Helix connection changed")
        connect()
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
                reconnectJob?.cancel()
                startPingLoop(webSocket)
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

        // All player.state events can affect an open queue, even when the current song did not
        // change (append/remove/reorder). Let screens refresh from the authoritative backend.
        RefreshSignals.bumpPlayer()

        val now = state.optJSONObject("now_playing")
        val queueItemId = now
            ?.optString("id", now.optString("queue_item_id", ""))
            ?.takeIf { it.isNotBlank() }
        val isPlaying = state.optBoolean("is_playing", false)

        // Media3 only needs a transport sync if playback identity/state actually changed.
        // Queue-only updates are handled by RefreshSignals without reloading the audio stream.
        val transportChanged = queueItemId != lastQueueItemId || isPlaying != lastIsPlaying
        lastQueueItemId = queueItemId
        lastIsPlaying = isPlaying
        if (transportChanged) syncRequests.trySend(Unit)
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

                // Match the browser hook. Helix accepts a text ping and keeps the connection alive.
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
                    RefreshSignals.bumpPlayer()
                    syncRequests.trySend(Unit)
                }
            }
        }
    }

    @Synchronized
    private fun scheduleReconnect() {
        if (!started || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            reconnectJob = null
            connect()
        }
    }

    private fun connectionKey(context: Context): String {
        return HelixPrefs.getBaseUrl(context).trim().trimEnd('/') + "|" +
            HelixPrefs.getSessionToken(context).orEmpty()
    }
}
