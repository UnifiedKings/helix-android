package com.example.helixapp.playback

import android.content.Context
import android.os.SystemClock
import com.example.helixapp.HelixClient
import com.example.helixapp.HelixHttpException
import com.example.helixapp.HelixPrefs
import com.example.helixapp.HelixTimeoutException
import com.example.helixapp.showLoadingOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * The playback and queue actions screens can trigger.
 *
 * Anything that changes what's playing goes through [PlayerCommandCoordinator.changePlayback],
 * so it is serialized with lock-screen commands and websocket syncs. Every function throws on
 * failure ([HelixHttpException] for a backend rejection, IOException for network trouble);
 * screens run them with `launchPlaybackAction` for consistent feedback.
 */
object PlaybackActions {
    private const val STATION_START_TIMEOUT_MS = 30_000L
    private const val STATION_POLL_INTERVAL_MS = 500L

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun JSONObject.toBody(): RequestBody = toString().toRequestBody(JSON)

    suspend fun playTrack(ctx: Context, track: JSONObject) {
        PlayerCommandCoordinator.changePlayback(ctx) { it.playTrack(track.toBody()) }
    }

    suspend fun playAlbum(ctx: Context, album: JSONObject) {
        PlayerCommandCoordinator.changePlayback(ctx) { it.playAlbum(album.toBody()) }
    }

    suspend fun playPlaylist(ctx: Context, playlistId: String, shuffle: Boolean) {
        val body = JSONObject().put("playlist_id", playlistId).put("shuffle", shuffle)
        PlayerCommandCoordinator.changePlayback(ctx) { it.playPlaylist(body.toBody()) }
    }

    suspend fun jumpTo(ctx: Context, queueIndex: Int) {
        val body = JSONObject().put("index", queueIndex)
        PlayerCommandCoordinator.changePlayback(ctx) { it.jump(body.toBody()) }
    }

    suspend fun queueTrack(ctx: Context, track: JSONObject) {
        val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
        val resp = withContext(Dispatchers.IO) { api.queueAppendTrack(track.toBody()) }
        if (!resp.isSuccessful) throw HelixHttpException(resp.code())
    }

    suspend fun queueAlbum(ctx: Context, album: JSONObject) {
        val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
        val resp = withContext(Dispatchers.IO) { api.queueAppendAlbum(album.toBody()) }
        if (!resp.isSuccessful) throw HelixHttpException(resp.code())
    }

    /**
     * Start a station from scratch. The backend builds the station's queue asynchronously
     * (up to ~30 s), so wait until it reports the station active with a queued current track
     * before resuming and loading it. Updates the loading overlay as it goes.
     */
    suspend fun playStation(ctx: Context, stationId: String, stationName: String) {
        val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
        val resp = withContext(Dispatchers.IO) {
            api.playStation(stationId, JSONObject().put("reset", true).toBody())
        }
        if (!resp.isSuccessful) throw HelixHttpException(resp.code())

        showLoadingOverlay("Building station…\nStations can take up to 30 seconds to load.")
        if (!waitForStationReady(ctx, stationId, stationName)) {
            throw HelixTimeoutException("Station took too long to load. Try again in a moment.")
        }

        showLoadingOverlay("Loading now playing…")
        PlayerCommandCoordinator.changePlayback(ctx, forceLoadStream = true, forceRestart = true) {
            it.resume()
        }
    }

    private suspend fun waitForStationReady(
        ctx: Context,
        stationId: String,
        stationName: String,
    ): Boolean {
        val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < STATION_START_TIMEOUT_MS) {
            val resp = withContext(Dispatchers.IO) { api.playerState() }
            if (resp.isSuccessful && isStationReady(resp.body().orEmpty(), stationId, stationName)) {
                return true
            }
            delay(STATION_POLL_INTERVAL_MS)
        }
        return false
    }

    private fun isStationReady(stateJson: String, stationId: String, stationName: String): Boolean {
        val root = runCatching { JSONObject(stateJson) }.getOrNull() ?: return false
        val activeStation = root.optJSONObject("active_station")
        val activeId = activeStation
            ?.optString("id", activeStation.optString("station_id", ""))
            ?.trim()
            .orEmpty()
        val activeName = activeStation?.optString("name", "")?.trim().orEmpty()
        val stationMatches =
            (stationId.isNotBlank() && activeId == stationId) ||
                (stationName.isNotBlank() && activeName.equals(stationName, ignoreCase = true))
        if (!stationMatches) return false

        val now = root.optJSONObject("now_playing") ?: return false
        val qid = now.optString("id", now.optString("queue_item_id", ""))
        if (qid.isBlank()) return false

        val queue = root.optJSONArray("queue") ?: JSONArray()
        for (i in 0 until queue.length()) {
            val item = queue.optJSONObject(i) ?: continue
            if (item.optString("id", item.optString("queue_item_id", "")) == qid) return true
        }
        return false
    }
}
