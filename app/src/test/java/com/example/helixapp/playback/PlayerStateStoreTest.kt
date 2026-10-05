package com.example.helixapp.playback

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStateStoreTest {

    @After
    fun tearDown() = PlayerStateStore.clear()

    private fun state(nowId: String?, queueIds: List<String>, station: String? = null, includeStation: Boolean = true) =
        JSONObject().apply {
            put("is_playing", true)
            if (nowId != null) put("now_playing", JSONObject().put("id", nowId).put("title", "T").put("artist", "A"))
            put("queue", JSONArray().apply { queueIds.forEach { put(JSONObject().put("id", it).put("title", "T$it")) } })
            if (includeStation) put("active_station", if (station == null) JSONObject.NULL else JSONObject().put("name", station))
        }

    @Test
    fun publishesNowPlayingQueueAndStation() {
        PlayerStateStore.publish(state("b", listOf("a", "b"), station = "Jessie Murph"))

        val s = PlayerStateStore.state.value!!
        assertEquals("b", s.now?.queueItemId)
        assertEquals(listOf("a", "b"), s.queue.map { it.queueItemId })
        assertTrue(s.isPlaying)
        assertEquals("Jessie Murph", s.activeStationName)
    }

    @Test
    fun keepsStationNameWhenPayloadOmitsIt() {
        PlayerStateStore.publish(state("a", listOf("a"), station = "Jessie Murph"))
        PlayerStateStore.publish(state("a", listOf("a"), includeStation = false))

        assertEquals("Jessie Murph", PlayerStateStore.state.value!!.activeStationName)
    }

    @Test
    fun clearsStationNameWhenPayloadSaysNone() {
        PlayerStateStore.publish(state("a", listOf("a"), station = "Jessie Murph"))
        PlayerStateStore.publish(state("a", listOf("a"), station = null))

        assertNull(PlayerStateStore.state.value!!.activeStationName)
    }

    @Test
    fun orphanNowPlayingIsHidden() {
        PlayerStateStore.publish(state("gone", listOf("a", "b")))

        val s = PlayerStateStore.state.value!!
        assertNull(s.now)
        assertEquals(2, s.queue.size)
    }

    @Test
    fun ignoresBodiesThatAreNotJsonObjects() {
        PlayerStateStore.publish("<html>proxy error</html>")
        assertNull(PlayerStateStore.state.value)
        assertFalse(PlayerStateStore.state.value?.isPlaying ?: false)
    }
}
