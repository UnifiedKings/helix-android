package com.example.helixapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HelixJsonTest {

    @Test
    fun songsReadEveryKeySpellingAndSkipUntitled() {
        val songs = parseSongs(
            """{"songs": [
                {"title": "Dirty", "artist": "Jessie Murph", "album": "That Ain't No Man", "video_id": "v1", "thumbnail_url": "t1"},
                {"name": "Upgrade", "artists": "Jessie Murph", "videoId": "v2", "thumbnail": "t2"},
                {"title": "Local", "artist_name": "Someone", "source": "subsonic", "subsonic_song_id": "s9", "thumb": "t3"},
                {"title": "", "video_id": "nope"}
            ]}"""
        )
        assertEquals(listOf("Dirty", "Upgrade", "Local"), songs.map { it.title })
        assertEquals(listOf("v1", "v2", ""), songs.map { it.videoId })
        assertEquals(listOf("t1", "t2", "t3"), songs.map { it.thumbnailUrl })
        assertEquals("Jessie Murph", songs[1].artist)
        assertEquals("Someone", songs[2].artist)
        assertTrue(songs[2].isFromSubsonic)
        assertEquals("s9", songs[2].subsonicSongId)
        assertEquals("ytmusic", songs[0].source)
    }

    @Test
    fun popularTracksFallBackToTheArtistName() {
        val tracks = parsePopularTracks("""{"artist_name": "Lil Wayne", "tracks": [{"title": "Lollipop", "video_id": "a"}]}""")
        assertEquals("Lil Wayne", tracks.single().artist)
        val songsKey = parsePopularTracks("""{"songs": [{"title": "A Milli", "video_id": "b"}]}""", fallbackArtist = "Lil Wayne")
        assertEquals("Lil Wayne", songsKey.single().artist)
    }

    @Test
    fun albumsKeepSubsonicOnlyAlbumsAndSkipUntitled() {
        val albums = parseAlbums(
            """{"albums": [
                {"title": "Tha Carter IV", "artist": "Lil Wayne", "year": "2011", "browse_id": "b1"},
                {"name": "Local Album", "artists": "Someone", "release_year": "1999", "source": "subsonic", "subsonic_album_id": "sa"},
                {"title": " "}
            ]}"""
        )
        assertEquals(listOf("Tha Carter IV", "Local Album"), albums.map { it.title })
        assertEquals("b1", albums[0].browseId)
        assertEquals("", albums[1].browseId)
        assertEquals("1999", albums[1].year)
        assertTrue(albums[1].isFromSubsonic)
    }

    @Test
    fun artistsToleratesAnEmptyBodyAndSkipsNameless() {
        assertTrue(parseArtists("").isEmpty())
        val artists = parseArtists(
            """{"artists": [
                {"name": "Jessie Murph", "browse_id": "UC1", "thumbnail_url": "t", "subscribers": "1M"},
                {"artist": "Lil Wayne", "artist_id": "UC2", "monthly_listeners": "40M"},
                {"name": "", "browse_id": "UC3"}
            ]}"""
        )
        assertEquals(listOf("Jessie Murph", "Lil Wayne"), artists.map { it.name })
        assertEquals(listOf("UC1", "UC2"), artists.map { it.browseId })
        assertEquals("1M", artists[0].subscriberCount)
        assertEquals("40M", artists[1].monthlyListeners)
    }

    @Test
    fun albumViewFillsTrackArtistsFromTheAlbum() {
        val album = parseAlbumView(
            """{"title": "Tha Carter IV", "album_artist": "Lil Wayne", "year": "2011", "thumbnail_url": "t",
                "tracks": [
                  {"title": "Blunt Blowin", "duration_seconds": 312, "video_id": "v1"},
                  {"pos": 5, "title": "How To Love", "artist": "Lil Wayne feat. nobody", "videoId": "v2"},
                  {"title": ""}
                ]}"""
        )
        assertEquals("Lil Wayne", album.artist)
        assertEquals(listOf(1, 5), album.tracks.map { it.pos })
        assertEquals("Lil Wayne", album.tracks[0].artist)
        assertEquals("Lil Wayne feat. nobody", album.tracks[1].artist)
        assertEquals(312, album.tracks[0].durationSeconds)
        assertEquals(listOf("v1", "v2"), album.tracks.map { it.videoId })
    }

    @Test
    fun stationsPreferConfigSeedsOverTopLevelOnes() {
        val stations = parseStations(
            """[
                {"id": "s1", "name": "Jessie murph", "station_type": "listenbrainz_similar_artist",
                 "config": {"seed_artist": "Jessie Murph", "discovery": 0.5}, "seed_artist": "ignored", "thumbnail_url": "t"},
                {"id": "s2", "name": "Old", "seed_type": "artist", "seed_artist": "Lil Wayne"}
            ]"""
        )
        assertEquals("Jessie Murph", stations[0].seedArtist)
        assertEquals(0.5f, stations[0].discovery)
        assertEquals(0.75f, stations[0].seedInfluence)
        assertEquals("Lil Wayne", stations[1].seedArtist)
        assertEquals("listenbrainz_similar_artist", stations[1].stationType)
        assertEquals(0.35f, stations[1].discovery)
    }

    @Test
    fun stationProvidersDropBlankTypesAndOptions() {
        val providers = parseStationProviders(
            """[
                {"station_type": "artist_radio", "display_name": "Artist radio", "config_options": [
                    {"key": "seed_artists", "type": "artist_search", "min_items": 1, "choices": [{"value": "a"}]},
                    {"key": "", "label": "broken"}
                ]},
                {"station_type": "", "display_name": "nothing"}
            ]"""
        )
        val option = providers.single().configOptions.single()
        assertEquals("seed_artists", option.label)
        assertEquals(1, option.minItems)
        assertEquals(null, option.maxItems)
        assertEquals(999, option.order)
        assertEquals("options", option.category)
        assertEquals("a", option.choices.single().label)
    }

    @Test
    fun playlistsArePlayedBySystemKeyWhenTheyHaveOne() {
        val playlists = parsePlaylists(
            """[{"id": "p1", "name": "Liked Songs", "system_key": "liked", "track_count": 3}, {"id": "p2", "name": "Gym"}]"""
        )
        assertEquals(listOf("liked", "p2"), playlists.map { it.playId })
        assertEquals(3, playlists[0].trackCount)
        assertEquals(0, playlists[1].trackCount)
    }

    @Test
    fun playlistDetailReadsTracks() {
        val detail = parsePlaylistDetail(
            """{"playlist": {"name": "Gym", "system_key": ""},
                "tracks": [{"id": "t1", "title": "Dirty", "artist": "Jessie Murph", "duration_ms": 180000, "yt_video_id": "v1"}]}"""
        )
        assertEquals("Gym", detail.name)
        val track = detail.tracks.single()
        assertEquals("t1", track.id)
        assertEquals(180000L, track.durationMs)
        assertEquals("v1", track.ytVideoId)
        assertEquals("Playlist", parsePlaylistDetail("{}").name)
    }

    @Test
    fun artistDetailAndSimilarArtists() {
        val detail = parseArtistDetail("""{"name": "Lil Wayne"}""", browseId = "UC2")
        assertEquals("UC2", detail.browseId)
        assertEquals("unresolved", detail.resolutionStatus)
        val similar = parseSimilarArtists(
            """{"similar_artists": [{"artist_name": "Drake", "artist_mbid": "m1"}, {"name": "", "browse_id": "", "mb_artist_id": ""}]}"""
        )
        assertEquals("Drake", similar.single().name)
        assertEquals("m1", similar.single().mbArtistId)
    }
}
