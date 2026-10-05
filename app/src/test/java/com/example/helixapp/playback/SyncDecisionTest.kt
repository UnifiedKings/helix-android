package com.example.helixapp.playback

import com.example.helixapp.playback.HelixTransport.SyncAction
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules for how the phone's player follows backend state snapshots. */
class SyncDecisionTest {

    private val baseUrl = "https://helix.example"

    private fun state(
        nowId: String? = "q2",
        queueIds: List<String> = listOf("q1", "q2", "q3"),
        isPlaying: Boolean = true,
        source: String = "station",
    ): JSONObject = JSONObject().apply {
        put("is_playing", isPlaying)
        if (nowId != null) {
            put(
                "now_playing",
                JSONObject()
                    .put("id", nowId)
                    .put("title", "Song $nowId")
                    .put("artist", "Artist")
                    .put("art_url", "/api/art/$nowId")
                    .put("source", source),
            )
        }
        put("queue", JSONArray().apply { queueIds.forEach { put(JSONObject().put("id", it)) } })
    }

    private fun decide(
        state: JSONObject,
        loadedItemId: String? = null,
        forceLoad: Boolean = false,
        playOnDevice: Boolean = true,
        waitingForLocalPlay: Boolean = false,
    ) = HelixTransport.decideSync(state, baseUrl, loadedItemId, forceLoad, playOnDevice, waitingForLocalPlay)

    @Test
    fun newCurrentItemIsLoadedAndPlayed() {
        val action = decide(state(nowId = "q2"), loadedItemId = "q1")

        assertTrue(action is SyncAction.Load)
        action as SyncAction.Load
        assertEquals("q2", action.item.queueItemId)
        assertEquals("$baseUrl/api/stream/q2", action.item.url)
        assertEquals("$baseUrl/api/art/q2", action.item.artworkUrl)
        assertTrue(action.autoplay)
        assertEquals("station", action.sourceLower)
    }

    @Test
    fun sameItemOnlyChangesPlayState() {
        assertEquals(
            SyncAction.SetPlaying(playing = false, sourceLower = "station"),
            decide(state(isPlaying = false), loadedItemId = "q2"),
        )
    }

    @Test
    fun forceLoadReloadsTheSameItem() {
        val action = decide(state(), loadedItemId = "q2", forceLoad = true)
        assertTrue(action is SyncAction.Load)
    }

    @Test
    fun noCurrentItemClears() {
        assertTrue(decide(state(nowId = null)) is SyncAction.Clear)
    }

    @Test
    fun currentItemMissingFromQueueIsRejected() {
        // A queue rebuild can briefly leave now_playing pointing at a removed item.
        assertTrue(decide(state(nowId = "gone")) is SyncAction.Clear)
    }

    @Test
    fun remoteModeNeverPlaysLocally() {
        assertEquals(SyncAction.Unload("station"), decide(state(), playOnDevice = false))
    }

    @Test
    fun openingTheAppLoadsTheCurrentSongSilently() {
        // Backend is playing on another device; the phone was just opened.
        val action = decide(state(isPlaying = true), loadedItemId = null, waitingForLocalPlay = true)

        assertTrue(action is SyncAction.Load)
        assertFalse((action as SyncAction.Load).autoplay)
    }

    @Test
    fun pausedPhoneStaysSilentWhenAnotherDeviceAdvancesTheQueue() {
        // The user paused the phone; the web player kept going, its song ended, and the
        // backend moved to the next item and marked the session playing again.
        val action = decide(state(nowId = "q3", isPlaying = true), loadedItemId = "q2", waitingForLocalPlay = true)

        assertTrue(action is SyncAction.Load)
        action as SyncAction.Load
        assertEquals("q3", action.item.queueItemId)
        assertFalse(action.autoplay)
    }

    @Test
    fun pausedPhoneDoesNotResumeTheSameItem() {
        assertEquals(
            SyncAction.SetPlaying(playing = false, sourceLower = "station"),
            decide(state(isPlaying = true), loadedItemId = "q2", waitingForLocalPlay = true),
        )
    }

    @Test
    fun acceptsLegacyQueueItemIdKey() {
        val legacy = JSONObject()
            .put("is_playing", true)
            .put("now_playing", JSONObject().put("queue_item_id", "q9"))
            .put("queue", JSONArray().put(JSONObject().put("queue_item_id", "q9")))

        val action = decide(legacy)
        assertTrue(action is SyncAction.Load)
        assertEquals("q9", (action as SyncAction.Load).item.queueItemId)
    }
}
