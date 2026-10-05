package com.example.helixapp.playback

import com.example.helixapp.parsePlaylists
import com.example.helixapp.parseStations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HelixLibraryBrowserTest {

    @Test
    fun topLevelFoldersAreBrowsableNotPlayable() {
        val ids = HelixLibraryBrowser.topLevel().map { it.mediaId }
        assertEquals(listOf("queue", "stations", "playlists", "recent"), ids)
        HelixLibraryBrowser.topLevel().forEach {
            assertEquals(true, it.mediaMetadata.isBrowsable)
            assertEquals(false, it.mediaMetadata.isPlayable)
        }
        assertEquals(true, HelixLibraryBrowser.root().mediaMetadata.isBrowsable)
    }

    @Test
    fun stationsBecomePlayableItems() {
        val items = HelixLibraryBrowser.stationItems(
            null,
            parseStations("""[{"id": "s1", "name": "Jessie Murph Radio"}, {"id": "", "name": "broken"}, {"id": "s2"}]"""),
        )
        assertEquals(listOf("station:s1", "station:s2"), items.map { it.mediaId })
        assertEquals("Jessie Murph Radio", items[0].mediaMetadata.title)
        assertEquals("Station", items[1].mediaMetadata.title)
        assertEquals(true, items[0].mediaMetadata.isPlayable)
        assertEquals(false, items[0].mediaMetadata.isBrowsable)
    }

    @Test
    fun systemPlaylistsArePlayedByKey() {
        val items = HelixLibraryBrowser.playlistItems(
            parsePlaylists("""[{"id": "p1", "name": "Liked songs", "system_key": "liked", "track_count": 1},
                {"id": "p2", "name": "Gym", "track_count": 12}]"""),
        )
        assertEquals(listOf("playlist:liked", "playlist:p2"), items.map { it.mediaId })
        assertEquals("1 song", items[0].mediaMetadata.artist)
        assertEquals("12 songs", items[1].mediaMetadata.artist)
    }

    @Test
    fun recentItemsUseHistoryIds() {
        val items = HelixLibraryBrowser.recentItems(
            """{"has_more": false, "items": [
                {"id": "h1", "title": "Forever", "artist": "Jessie Murph", "event": "completed",
                 "created_at": "2026-10-05T06:28:54Z", "yt_video_id": "abc"}
            ]}""",
        )
        val item = items.single()
        assertEquals("recent:h1", item.mediaId)
        assertEquals("Forever", item.mediaMetadata.title)
        assertEquals("Jessie Murph", item.mediaMetadata.artist)
    }

    @Test
    fun emptyListsAreFine() {
        assertTrue(HelixLibraryBrowser.stationItems(null, emptyList()).isEmpty())
        assertTrue(HelixLibraryBrowser.playlistItems(emptyList()).isEmpty())
        assertFalse(HelixLibraryBrowser.recentItems("""{"items": []}""").isNotEmpty())
    }

    @Test
    fun browseIdsAreToldApartFromQueueItemIds() {
        assertTrue(HelixLibraryBrowser.isPlayableId("queue:3:abc"))
        assertTrue(HelixLibraryBrowser.isPlayableId("station:s1"))
        assertTrue(HelixLibraryBrowser.isPlayableId("playlist:liked"))
        assertTrue(HelixLibraryBrowser.isPlayableId("recent:h1"))
        assertFalse(HelixLibraryBrowser.isPlayableId("170b955c-c1f8-42e3-bf74-85f4653ea7f3"))
        assertFalse(HelixLibraryBrowser.isPlayableId("stations"))
    }
}
