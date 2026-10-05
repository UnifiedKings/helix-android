package com.example.helixapp.playback

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.helixapp.HelixApi
import com.example.helixapp.HelixClient
import com.example.helixapp.HelixHttpException
import com.example.helixapp.HelixPartialException
import com.example.helixapp.HelixPrefs
import com.example.helixapp.HelixTimeoutException
import com.example.helixapp.showLoadingOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Response

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
     * Add a song so it plays right after the current one. The server has no "insert next"
     * request (only an account-wide add position), so append it, then move it with a reorder.
     */
    suspend fun playNext(ctx: Context, track: JSONObject) {
        val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
        val beforeIds = fetchQueue(api).second.map { it.queueItemId }.toSet()

        val resp = withContext(Dispatchers.IO) { api.queueAppendTrack(track.toBody()) }
        if (!resp.isSuccessful) throw HelixHttpException(resp.code())
        val body = resp.body().orEmpty()
        PlayerStateStore.publish(body)

        val (now, items) = HelixTransport.parseQueueFromState(body)
        val ids = items.map { it.queueItemId }
        val addedId = ids.firstOrNull { it !in beforeIds } ?: return
        val ordered = orderWithPlayNext(ids, now?.queueItemId, addedId)
        if (ordered != ids) {
            try {
                reorder(api, ordered)
            } catch (e: Exception) {
                throw HelixPartialException("Added to the end of the queue, but couldn't move it up to play next.", e)
            }
        }
    }

    /** Move a song that's already in the queue so it plays right after the current one. */
    suspend fun moveToNext(ctx: Context, queueItemId: String) {
        val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
        val (now, items) = fetchQueue(api)
        val ids = items.map { it.queueItemId }
        val ordered = orderWithPlayNext(ids, now?.queueItemId, queueItemId)
        if (ordered != ids) reorder(api, ordered)
    }

    /**
     * Remove a song from the queue. Removing the song that's playing makes the server skip to
     * the next one, so that goes through the coordinator like any other change of track.
     */
    suspend fun removeFromQueue(ctx: Context, queueItemId: String, isCurrent: Boolean) {
        if (isCurrent) {
            PlayerCommandCoordinator.changePlayback(ctx, forceLoadStream = true) { api ->
                if (deleteQueueItem(api, queueItemId)) Response.success("") else Response.error(500, "".toResponseBody())
            }
            return
        }
        val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
        if (!deleteQueueItem(api, queueItemId)) throw HelixHttpException(500)
        PlayerStateStore.refresh(ctx)
    }

    /**
     * Delete one queue item; true when it's gone. The server can answer 500 after it has
     * already deleted the item (renumbering the remaining positions can trip its unique
     * position constraint), so on an error check whether the item is actually still queued.
     */
    private suspend fun deleteQueueItem(api: HelixApi, queueItemId: String): Boolean {
        val resp = withContext(Dispatchers.IO) { api.queueRemoveItem(queueItemId) }
        if (resp.isSuccessful || resp.code() == 404) return true
        val stillQueued = fetchQueue(api).second.any { it.queueItemId == queueItemId }
        if (!stillQueued) Log.w("HELIX_PLAYER", "Remove answered HTTP ${resp.code()} but the item is gone; treating as removed")
        return !stillQueued
    }

    /**
     * Clear the queue but keep the current song playing. The server's own clear also removes
     * the current song and ends the station, so remove the other songs one at a time instead.
     */
    suspend fun clearQueueKeepingCurrent(ctx: Context) {
        val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
        val (now, items) = fetchQueue(api)
        items.map { it.queueItemId }
            .filter { it != now?.queueItemId }
            .forEach { id ->
                if (!deleteQueueItem(api, id)) throw HelixHttpException(500)
            }
        PlayerStateStore.refresh(ctx)
    }

    /**
     * The queue order after moving [moveId] to play right after [currentId]. With no current
     * song it moves to the front. Pure, for unit tests.
     */
    internal fun orderWithPlayNext(ids: List<String>, currentId: String?, moveId: String): List<String> {
        if (moveId == currentId || moveId !in ids) return ids
        val rest = ids.filter { it != moveId }
        val insertAt = currentId?.let { rest.indexOf(it) }?.takeIf { it >= 0 }?.plus(1) ?: 0
        return rest.subList(0, insertAt) + moveId + rest.subList(insertAt, rest.size)
    }

    private suspend fun fetchQueue(api: HelixApi): Pair<NowPlayingUi?, List<QueueItemUi>> {
        val resp = withContext(Dispatchers.IO) { api.playerState() }
        if (!resp.isSuccessful) throw HelixHttpException(resp.code())
        val body = resp.body().orEmpty()
        PlayerStateStore.publish(body)
        return HelixTransport.parseQueueFromState(body)
    }

    /** Save a new queue order (all queue item ids, in order) and publish the result. */
    suspend fun reorderQueue(ctx: Context, orderedIds: List<String>) {
        reorder(HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx)), orderedIds)
    }

    private suspend fun reorder(api: HelixApi, orderedIds: List<String>) {
        val body = JSONObject().put("item_ids", JSONArray(orderedIds))
        val resp = withContext(Dispatchers.IO) { api.reorderQueue(body.toBody()) }
        if (!resp.isSuccessful) throw HelixHttpException(resp.code())
        PlayerStateStore.publish(resp.body().orEmpty())
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

    internal fun isStationReady(stateJson: String, stationId: String, stationName: String): Boolean {
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
