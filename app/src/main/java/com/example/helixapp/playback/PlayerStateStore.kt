package com.example.helixapp.playback

import android.content.Context
import com.example.helixapp.HelixClient
import com.example.helixapp.HelixHttpException
import com.example.helixapp.HelixPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** What the backend says is playing, as screens display it. */
data class PlayerStateSnapshot(
    val now: NowPlayingUi?,
    val queue: List<QueueItemUi>,
    val isPlaying: Boolean,
    val activeStationName: String?,
)

/**
 * The latest backend player state, shared by every screen.
 *
 * Fed by every place that already receives a full state: /ws/player snapshots
 * (PlayerRealtime), the coordinator's /api/playback/state syncs (HelixTransport) and
 * responses that return the queue (e.g. reorder). Screens collect [state] instead of
 * fetching /state themselves; [refresh] is only for an explicit reload.
 */
object PlayerStateStore {
    private val _state = MutableStateFlow<PlayerStateSnapshot?>(null)
    val state: StateFlow<PlayerStateSnapshot?> = _state.asStateFlow()

    fun publish(stateJson: JSONObject) {
        val (now, queue) = HelixTransport.parseQueueFromState(stateJson)
        val previous = _state.value
        // Not every payload carries the active station; keep the last known one if absent.
        val stationName = if (stateJson.has("active_station")) {
            stateJson.optJSONObject("active_station")
                ?.optString("name", "")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        } else {
            previous?.activeStationName
        }
        _state.value = PlayerStateSnapshot(
            now = now,
            queue = queue,
            isPlaying = stateJson.optBoolean("is_playing", false),
            activeStationName = stationName,
        )
    }

    /** Publish a raw state body; ignores bodies that aren't a JSON object. */
    fun publish(stateJson: String) {
        runCatching { JSONObject(stateJson) }.getOrNull()?.let(::publish)
    }

    fun clear() {
        _state.value = null
    }

    /** Fetch /api/playback/state and publish it. Throws on failure. */
    suspend fun refresh(ctx: Context) {
        val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
        val resp = withContext(Dispatchers.IO) { api.playerState() }
        if (!resp.isSuccessful) throw HelixHttpException(resp.code())
        publish(resp.body().orEmpty())
    }
}
