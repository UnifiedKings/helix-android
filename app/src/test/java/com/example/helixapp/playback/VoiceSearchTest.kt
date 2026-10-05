package com.example.helixapp.playback

import com.example.helixapp.playback.VoiceSearch.Hints
import com.example.helixapp.playback.VoiceSearch.VoiceQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceSearchTest {

    @Test
    fun emptyPhraseResumes() {
        assertEquals(VoiceQuery.Resume, VoiceSearch.parse(null))
        assertEquals(VoiceQuery.Resume, VoiceSearch.parse("  "))
    }

    @Test
    fun radioAndStationAskForAStation() {
        assertEquals(VoiceQuery.Radio("jessie murph"), VoiceSearch.parse("Jessie Murph radio"))
        assertEquals(VoiceQuery.Radio("chill"), VoiceSearch.parse("chill station"))
        // A single word isn't a station request.
        assertEquals(VoiceQuery.Anything("radio"), VoiceSearch.parse("radio"))
    }

    @Test
    fun myAndPlaylistAskForAPlaylist() {
        assertEquals(VoiceQuery.Playlist("gym"), VoiceSearch.parse("my Gym playlist"))
        assertEquals(VoiceQuery.Playlist("liked songs"), VoiceSearch.parse("my liked songs"))
        assertEquals(VoiceQuery.Playlist("road trip"), VoiceSearch.parse("Road Trip playlist"))
    }

    @Test
    fun plainPhrasesDropBy() {
        assertEquals(VoiceQuery.Anything("Dirty Jessie Murph"), VoiceSearch.parse("Dirty by Jessie Murph"))
        assertEquals(VoiceQuery.Anything("How To Love"), VoiceSearch.parse("How To Love"))
    }

    @Test
    fun assistantHintsWin() {
        assertEquals(
            VoiceQuery.Artist("Jessie Murph"),
            VoiceSearch.parse("jessie murph", Hints(focus = "vnd.android.cursor.item/artist", artist = "Jessie Murph")),
        )
        assertEquals(
            VoiceQuery.Song("Dirty Jessie Murph"),
            VoiceSearch.parse("dirty by jessie murph", Hints(focus = "vnd.android.cursor.item/audio", title = "Dirty", artist = "Jessie Murph")),
        )
        assertEquals(
            VoiceQuery.Album("Tha Carter IV Lil Wayne"),
            VoiceSearch.parse("x", Hints(focus = "vnd.android.cursor.item/album", album = "Tha Carter IV", artist = "Lil Wayne")),
        )
        assertEquals(
            VoiceQuery.Playlist("gym"),
            VoiceSearch.parse("my gym playlist", Hints(focus = "vnd.android.cursor.item/playlist", playlist = "Gym")),
        )
        // Hints without the fields fall back to the phrase.
        assertEquals(
            VoiceQuery.Song("dirty"),
            VoiceSearch.parse("dirty", Hints(focus = "vnd.android.cursor.item/audio")),
        )
    }

    @Test
    fun unknownFocusUsesThePhrase() {
        assertEquals(
            VoiceQuery.Anything("Dirty"),
            VoiceSearch.parse("Dirty", Hints(focus = "vnd.android.cursor.item/*")),
        )
    }

    private val entries = listOf(
        "s1" to "Jessie murph",
        "s2" to "Lil Wayne Radio",
        "liked" to "Liked Songs",
        "p2" to "Rock 'n' Roll",
    )

    @Test
    fun namesMatchIgnoringCaseFillerAndPunctuation() {
        assertEquals("s1", VoiceSearch.matchName("Jessie Murph", entries))
        assertEquals("s1", VoiceSearch.matchName("jessie murph radio", entries))
        assertEquals("s2", VoiceSearch.matchName("lil wayne", entries))
        assertEquals("liked", VoiceSearch.matchName("my liked songs", entries))
        assertEquals("p2", VoiceSearch.matchName("rock n roll", entries))
    }

    @Test
    fun partialNamesDoNotMatch() {
        assertNull(VoiceSearch.matchName("jessie", entries))
        assertNull(VoiceSearch.matchName("radio", entries))
        assertNull(VoiceSearch.matchName("", entries))
    }
}
