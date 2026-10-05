package com.example.helixapp.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where "Play next" moves a song. */
class QueueOrderTest {

    private fun order(ids: String, current: String?, move: String) =
        PlaybackActions.orderWithPlayNext(ids.split(","), current, move).joinToString(",")

    @Test
    fun appendedSongMovesRightAfterTheCurrentOne() {
        assertEquals("a,b,new,c,d", order("a,b,c,d,new", current = "b", move = "new"))
    }

    @Test
    fun songLaterInTheQueueMovesUp() {
        assertEquals("a,b,d,c", order("a,b,c,d", current = "b", move = "d"))
    }

    @Test
    fun songBeforeTheCurrentOneMovesAfterIt() {
        // Played songs stay in the queue before the current item.
        assertEquals("b,c,a,d", order("a,b,c,d", current = "c", move = "a"))
    }

    @Test
    fun alreadyNextIsUnchanged() {
        assertEquals("a,b,c", order("a,b,c", current = "a", move = "b"))
    }

    @Test
    fun theCurrentSongIsNeverMoved() {
        assertEquals("a,b,c", order("a,b,c", current = "b", move = "b"))
    }

    @Test
    fun withNothingPlayingTheSongMovesToTheFront() {
        assertEquals("c,a,b", order("a,b,c", current = null, move = "c"))
    }

    @Test
    fun unknownSongLeavesTheQueueAlone() {
        assertEquals("a,b,c", order("a,b,c", current = "a", move = "zzz"))
    }
}
