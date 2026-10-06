package com.example.helixapp.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerTest {

    @Test
    fun fullVolumeUntilTheFadeStarts() {
        assertEquals(1f, SleepTimer.fadeVolume(60_000L))
        assertEquals(1f, SleepTimer.fadeVolume(SleepTimer.FADE_MS))
    }

    @Test
    fun fadesLinearlyToSilence() {
        assertEquals(0.5f, SleepTimer.fadeVolume(SleepTimer.FADE_MS / 2), 0.001f)
        assertEquals(0f, SleepTimer.fadeVolume(0L))
        assertEquals(0f, SleepTimer.fadeVolume(-500L))
    }

    @Test
    fun formatsTheCountdown() {
        assertEquals("30:00", SleepTimer.formatRemaining(30 * 60_000L))
        assertEquals("1:00:00", SleepTimer.formatRemaining(60 * 60_000L))
        assertEquals("0:01", SleepTimer.formatRemaining(400L)) // rounds up, never shows 0:00 early
        assertEquals("0:00", SleepTimer.formatRemaining(0L))
    }

    @Test
    fun withNoTimerATrackEndIsNotConsumed() {
        assertTrue(SleepTimer.state.value == SleepTimer.State.Off)
        assertFalse(SleepTimer.consumeTrackEnd())
    }
}
