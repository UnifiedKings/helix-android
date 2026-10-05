package com.example.helixapp

import com.example.helixapp.playback.PlaybackActions
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HelixErrorsTest {

    @Test
    fun httpErrorsShowTheStatus() {
        assertEquals("Play failed (HTTP 500)", HelixHttpException(500).toUserMessage("Play"))
    }

    @Test
    fun expiredSessionSaysHowToFixIt() {
        assertEquals(
            "Queue failed: your session expired. Log in again in Settings.",
            HelixHttpException(401).toUserMessage("Queue"),
        )
    }

    @Test
    fun networkFailuresSayTheServerIsUnreachable() {
        listOf(UnknownHostException(), ConnectException(), SocketTimeoutException()).forEach {
            assertEquals("Play failed: can't reach the Helix server", it.toUserMessage("Play"))
        }
    }

    @Test
    fun timeoutsUseTheirOwnMessage() {
        assertEquals(
            "Station took too long to load.",
            HelixTimeoutException("Station took too long to load.").toUserMessage("Starting the station"),
        )
    }
}

class StationReadyTest {

    private fun state(activeId: String?, activeName: String?, nowId: String?, queueIds: List<String>) =
        JSONObject().apply {
            if (activeId != null || activeName != null) {
                put("active_station", JSONObject().put("id", activeId ?: "").put("name", activeName ?: ""))
            }
            if (nowId != null) put("now_playing", JSONObject().put("id", nowId))
            put("queue", JSONArray().apply { queueIds.forEach { put(JSONObject().put("id", it)) } })
        }.toString()

    @Test
    fun readyWhenStationIsActiveWithAQueuedCurrentTrack() {
        assertTrue(PlaybackActions.isStationReady(state("s1", "Radio", "q1", listOf("q1")), "s1", "Radio"))
    }

    @Test
    fun matchesByNameWhenIdIsMissing() {
        assertTrue(PlaybackActions.isStationReady(state(null, "radio", "q1", listOf("q1")), "", "Radio"))
    }

    @Test
    fun notReadyWhileAnotherStationIsActive() {
        assertFalse(PlaybackActions.isStationReady(state("s2", "Other", "q1", listOf("q1")), "s1", "Radio"))
    }

    @Test
    fun notReadyUntilTheCurrentTrackIsInTheQueue() {
        assertFalse(PlaybackActions.isStationReady(state("s1", "Radio", "q1", listOf("q0")), "s1", "Radio"))
        assertFalse(PlaybackActions.isStationReady(state("s1", "Radio", null, listOf("q0")), "s1", "Radio"))
    }

    @Test
    fun notReadyForUnparseableBodies() {
        assertFalse(PlaybackActions.isStationReady("not json", "s1", "Radio"))
    }
}
