package com.example.helixapp.playback

import android.content.Context
import android.util.Log
import com.example.helixapp.HelixClient
import com.example.helixapp.HelixImages
import com.example.helixapp.HelixPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object HelixTransport {

    @Volatile
    private var lastNowId: String? = null

    @Volatile
    private var lastSourceLower: String = ""

    /**
     * While true the phone follows the backend's current item but stays silent, until the user
     * asks to listen here (Play, or starting anything). Set when the service starts, so opening
     * the app never interrupts or doubles up audio on another device, and whenever playback is
     * paused on this phone, so another device advancing the shared queue (which marks it
     * playing again) can't restart the phone on its own.
     */
    @Volatile
    var waitingForLocalPlay: Boolean = true
        private set

    /** The user asked to listen on this phone. */
    fun allowLocalPlayback() {
        waitingForLocalPlay = false
    }

    /** The user paused on this phone: stay silent until they press Play here again. */
    fun holdLocalPlayback() {
        waitingForLocalPlay = true
    }

    fun resetSyncState() {
        lastNowId = null
        lastSourceLower = ""
        waitingForLocalPlay = true
    }

    fun isStationPlayback(): Boolean {
        return lastSourceLower.contains("station")
    }

    private fun streamUrl(baseUrl: String, queueItemId: String): String {
        return baseUrl.trimEnd('/') + "/api/stream/" + queueItemId
    }

    suspend fun refreshAndSync(ctx: Context, forceLoadStream: Boolean = false, forceRestart: Boolean = false) {
        val baseUrl = HelixPrefs.getBaseUrl(ctx)
        val api = HelixClient.create(ctx, baseUrl)

        Log.d("HELIX_PLAYER", "Refreshing player state from backend")
        val resp = withContext(Dispatchers.IO) { api.playerState() }
        if (!resp.isSuccessful) {
            Log.w("HELIX_PLAYER", "playback/state not successful: ${resp.code()}")
            return
        }

        val state = JSONObject(resp.body().orEmpty())
        PlayerStateStore.publish(state)

        val action = decideSync(
            state = state,
            baseUrl = baseUrl,
            loadedItemId = lastNowId,
            forceLoad = forceLoadStream || forceRestart,
            playOnDevice = DevicePlayback.isEnabled(ctx),
            waitingForLocalPlay = waitingForLocalPlay,
        )
        when (action) {
            is SyncAction.Clear -> {
                Log.w("HELIX_PLAYER", "${action.reason}; clearing local Media3 state")
                lastNowId = null
                lastSourceLower = ""
                PlaybackController.clear(ctx)
            }
            is SyncAction.Unload -> {
                // Remote mode: screens show the shared state (already published above), but this
                // phone plays nothing. Forget the loaded item so turning playback back on reloads it.
                lastNowId = null
                lastSourceLower = action.sourceLower
                PlaybackController.clearIfLoaded(ctx)
            }
            is SyncAction.Load -> {
                Log.d("HELIX_PLAYER", "Applying current-only Media3 item now=${action.item.queueItemId}")
                lastNowId = action.item.queueItemId
                lastSourceLower = action.sourceLower
                PlaybackController.setCurrentItem(ctx, action.item, autoplay = action.autoplay)
            }
            is SyncAction.SetPlaying -> {
                lastSourceLower = action.sourceLower
                if (action.playing) PlaybackController.resume(ctx) else PlaybackController.pause(ctx)
            }
        }

        if (forceRestart) Log.d("HELIX_PLAYER", "forceRestart=true")
    }

    /** What the local player should do with a backend state snapshot. */
    sealed class SyncAction {
        /** Nothing valid to play (no current item, or one missing from the queue). */
        data class Clear(val reason: String) : SyncAction()

        /** This phone is a remote ("Play on this device" off): play nothing locally. */
        data class Unload(val sourceLower: String) : SyncAction()

        /** Load the backend's current item, starting it only if [autoplay]. */
        data class Load(val item: QueueMediaItem, val autoplay: Boolean, val sourceLower: String) : SyncAction()

        /** The current item is already loaded; just play or pause it. */
        data class SetPlaying(val playing: Boolean, val sourceLower: String) : SyncAction()
    }

    /**
     * Decide how the local player follows a backend state snapshot. Pure (no Android or
     * network calls) so the sync rules can be unit tested.
     *
     * The phone plays only when the backend says playing and the user hasn't paused here or
     * just opened the app ([waitingForLocalPlay]); otherwise it follows the current item silently.
     */
    fun decideSync(
        state: JSONObject,
        baseUrl: String,
        loadedItemId: String?,
        forceLoad: Boolean,
        playOnDevice: Boolean,
        waitingForLocalPlay: Boolean,
    ): SyncAction {
        val now = state.optJSONObject("now_playing")
            ?: return SyncAction.Clear("No now_playing in playback/state")

        val qid = now.optString("id", now.optString("queue_item_id", ""))
        if (qid.isBlank()) return SyncAction.Clear("now_playing missing id")

        val queue = state.optJSONArray("queue") ?: JSONArray()
        val currentIsQueued = (0 until queue.length()).any { i ->
            val item = queue.optJSONObject(i) ?: return@any false
            item.optString("id", item.optString("queue_item_id", "")) == qid
        }
        if (!currentIsQueued) {
            return SyncAction.Clear("Rejecting orphan now_playing=$qid not present in backend queue")
        }

        val sourceLower = now.optString("source", "").lowercase()
        if (!playOnDevice) return SyncAction.Unload(sourceLower)

        val playLocally = state.optBoolean("is_playing", true) && !waitingForLocalPlay
        if (!forceLoad && loadedItemId == qid) {
            return SyncAction.SetPlaying(playLocally, sourceLower)
        }

        val item = QueueMediaItem(
            queueItemId = qid,
            url = streamUrl(baseUrl, qid),
            title = now.optString("title", ""),
            artist = now.optString("artist", ""),
            album = now.optString("album", ""),
            // Media3's bitmap loader fetches the artwork asynchronously (with the session cookie),
            // so play/pause is never blocked on an image download.
            artworkUrl = HelixImages.absoluteUrl(baseUrl, now.optString("art_url", "")),
        )
        return SyncAction.Load(item, autoplay = playLocally, sourceLower = sourceLower)
    }

    fun parseQueueFromState(stateJson: String): Pair<NowPlayingUi?, List<QueueItemUi>> =
        parseQueueFromState(JSONObject(stateJson))

    fun parseQueueFromState(root: JSONObject): Pair<NowPlayingUi?, List<QueueItemUi>> {
        val now = root.optJSONObject("now_playing")
        val arr = root.optJSONArray("queue") ?: JSONArray()
        val items = ArrayList<QueueItemUi>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            items.add(
                QueueItemUi(
                    index = i,
                    queueItemId = o.optString("id", o.optString("queue_item_id", "")),
                    title = o.optString("title", ""),
                    artist = o.optString("artist", ""),
                    album = o.optString("album", ""),
                    artUrl = o.optString("art_url", ""),
                    durationMs = o.optLong("duration_ms", 0L),
                    source = o.optString("source", ""),
                    ytVideoId = o.optString("yt_video_id", "").ifBlank { null },
                    subsonicSongId = o.optString("subsonic_song_id", "").ifBlank { null },
                )
            )
        }

        val queuedIds = items.mapTo(hashSetOf()) { it.queueItemId }
        val nowUi = now?.let {
            val id = it.optString("id", it.optString("queue_item_id", ""))
            if (id.isBlank() || id !in queuedIds) {
                null
            } else {
                NowPlayingUi(
                    queueItemId = id,
                    title = it.optString("title", ""),
                    artist = it.optString("artist", ""),
                    album = it.optString("album", ""),
                    artUrl = it.optString("art_url", ""),
                    durationMs = it.optLong("duration_ms", 0L),
                    source = it.optString("source", ""),
                    ytVideoId = it.optString("yt_video_id", "").ifBlank { null },
                    subsonicSongId = it.optString("subsonic_song_id", "").ifBlank { null },
                )
            }
        }

        return nowUi to items
    }
}

data class QueueItemUi(
    val index: Int,
    val queueItemId: String,
    val title: String,
    val artist: String,
    val album: String,
    val artUrl: String,
    val durationMs: Long,
    val source: String,
    val ytVideoId: String?,
    val subsonicSongId: String?,
)

data class NowPlayingUi(
    val queueItemId: String,
    val title: String,
    val artist: String,
    val album: String,
    val artUrl: String,
    val durationMs: Long,
    val source: String,
    val ytVideoId: String?,
    val subsonicSongId: String?,
)
