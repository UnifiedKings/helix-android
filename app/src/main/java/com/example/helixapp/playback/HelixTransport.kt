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
     * When the app/service process starts, Media3 may still have a previously loaded media item
     * in memory. If the backend "now_playing" has changed since then, pressing play would resume
     * the stale local item (often the *next* queue entry).
     *
     * We use this flag to force a one-time refreshAndSync() before the first play after process
     * start, keeping Android and backend aligned.
     */
    @Volatile
    var needsInitialSync: Boolean = true
        private set

    fun markInitialSynced() {
        needsInitialSync = false
    }

    fun resetSyncState() {
        lastNowId = null
        lastSourceLower = ""
        needsInitialSync = true
    }


    fun isStationPlayback(): Boolean {
        // Heuristic: backend includes a 'source' string on now_playing (e.g., station context).
        // We treat anything containing 'station' as station playback.
        return lastSourceLower.contains("station")
    }

    private fun streamUrl(baseUrl: String, queueItemId: String): String {
        return baseUrl.trimEnd('/') + "/api/stream/" + queueItemId
    }

    /**
     * Mirror the web frontend player behavior:
     *  - Fetch /api/playback/state
     *  - Load /api/stream/{id} when now_playing changes (or forced)
     *  - Play or pause locally based on backend is_playing
     */
    suspend fun refreshAndSync(ctx: Context, forceLoadStream: Boolean = false, forceRestart: Boolean = false) {
        val baseUrl = HelixPrefs.getBaseUrl(ctx)
        val api = HelixClient.create(ctx, baseUrl)

        Log.d("HELIX_PLAYER", "Refreshing player state from backend")
        val resp = withContext(Dispatchers.IO) { api.playerState() }
        if (!resp.isSuccessful) {
            Log.w("HELIX_PLAYER", "playback/state not successful: ${resp.code()}")
            return
        }

        val body = resp.body().orEmpty()
        val state = JSONObject(body)

        val now = state.optJSONObject("now_playing")
        if (now == null) {
            Log.w("HELIX_PLAYER", "No now_playing in playback/state")
            return
        }

        // Backend returns PlayerQueueItem with field name "id" (not "queue_item_id").
        // "queue_item_id" was an older client-side assumption.
        val qid = now.optString("id", now.optString("queue_item_id", ""))
        if (qid.isBlank()) {
            Log.w("HELIX_PLAYER", "now_playing missing id")
            return
        }

        val isPlaying = state.optBoolean("is_playing", true)

        lastSourceLower = now.optString("source", "").lowercase()

        // Media3 owns only the real current track. Previous/next are intentionally *not* loaded
        // into ExoPlayer. PlaybackService advertises fake previous/next capabilities to Android so
        // system UIs show both transport buttons, and HelixSessionCallback captures those commands
        // and forwards them to the Helix backend.
        val title = now.optString("title", "")
        val artist = now.optString("artist", "")
        val album = now.optString("album", "")
        val art = now.optString("art_url", "")
        val absArt = HelixImages.absoluteUrl(baseUrl, art)
        val artworkData = if (absArt.isNotBlank()) {
            withContext(Dispatchers.IO) { HelixImages.fetchArtworkBytes(ctx, absArt) }
        } else {
            null
        }
        val currentItem = QueueMediaItem(
            queueItemId = qid,
            url = streamUrl(baseUrl, qid),
            title = title,
            artist = artist,
            album = album,
            artworkUrl = absArt,
            artworkData = artworkData,
        )

        val shouldLoad = forceLoadStream || forceRestart || (lastNowId != qid)
        lastNowId = qid

        if (shouldLoad) {
            Log.d("HELIX_PLAYER", "Applying current-only Media3 item now=$qid (fake prev/next transport enabled)")
            PlaybackController.setCurrentItem(ctx, currentItem, autoplay = isPlaying)
        } else {
            if (isPlaying) PlaybackController.resume(ctx) else PlaybackController.pause(ctx)
        }

        if (forceRestart) Log.d("HELIX_PLAYER", "forceRestart=true")
    }

    // Backwards compatible helper used by existing screens.
    suspend fun refreshAndPlayCurrent(ctx: Context, forceRestart: Boolean = false) {
        refreshAndSync(ctx, forceLoadStream = forceRestart, forceRestart = forceRestart)
    }

    suspend fun backendEndedAndRefresh(ctx: Context) {
        val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
        Log.d("HELIX_PLAYER", "POST /api/playback/ended")
        withContext(Dispatchers.IO) { api.ended() }
        refreshAndSync(ctx, forceLoadStream = true)
    }

    fun parseQueueFromState(stateJson: String): Pair<NowPlayingUi?, List<QueueItemUi>> {
        val root = JSONObject(stateJson)
        val now = root.optJSONObject("now_playing")
        val nowUi = now?.let {
            NowPlayingUi(
                queueItemId = it.optString("id", it.optString("queue_item_id", "")),
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
