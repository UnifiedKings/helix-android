package com.example.helixapp

import com.example.helixapp.data.SubsonicLookup
import com.example.helixapp.data.SubsonicRepository
import com.example.helixapp.data.SubsonicTrackRequest
import com.example.helixapp.data.errorDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlbumViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val album = AlbumView(
        title = "Tha Carter IV", artist = "Lil Wayne", year = "2011", thumbnailUrl = "t",
        tracks = listOf(
            AlbumTrack(1, "Blunt Blowin", "", 312, "v1"),
            AlbumTrack(2, "How To Love", "Lil Wayne", 240, "v2"),
            AlbumTrack(3, "Interlude", "Lil Wayne", 60, ""),
        ),
    )

    private inner class FakeLibrary(var fail: Exception? = null) : FakeLibraryRepository() {
        override suspend fun album(browseId: String): AlbumView {
            fail?.let { throw it }
            return album
        }
    }

    private class FakeSubsonic(val available: Set<String>) : SubsonicRepository {
        var lookups: List<SubsonicLookup> = emptyList()
        val added = mutableListOf<SubsonicTrackRequest>()
        override suspend fun resolve(songs: List<SubsonicLookup>, albums: List<SubsonicLookup>): Map<String, Boolean> {
            lookups = songs
            return songs.associate { it.key to (it.key in available) }
        }
        override suspend fun addTrack(track: SubsonicTrackRequest) { added += track }
        override suspend fun addAlbum(album: JSONObject) = Unit
    }

    @Test
    fun loadsTheAlbumAndResolvesIdentifiableTracks() = runTest(dispatcher) {
        val subsonic = FakeSubsonic(available = setOf("song:v1"))
        val vm = AlbumViewModel("b1", FakeLibrary(), subsonic, isSignedIn = { true })
        advanceUntilIdle()
        val state = vm.state.value
        assertFalse(state.loading)
        assertEquals(3, state.tracks.size)
        // The track without a video id isn't looked up; blank track artists use the album's.
        assertEquals(listOf("song:v1", "song:v2"), subsonic.lookups.map { it.key })
        assertEquals("Lil Wayne", subsonic.lookups[0].artist)
        assertTrue(state.trackInSubsonic(album.tracks[0]))
        assertFalse(state.trackInSubsonic(album.tracks[1]))
        assertFalse(state.fullyInSubsonic)
    }

    @Test
    fun fullyInSubsonicWhenEveryIdentifiableTrackIs() = runTest(dispatcher) {
        val vm = AlbumViewModel("b1", FakeLibrary(), FakeSubsonic(setOf("song:v1", "song:v2")), isSignedIn = { true })
        advanceUntilIdle()
        assertTrue(vm.state.value.fullyInSubsonic)
    }

    @Test
    fun loadErrorsAndMissingIds() = runTest(dispatcher) {
        val failing = AlbumViewModel("b1", FakeLibrary(HelixHttpException(404)), FakeSubsonic(emptySet()), isSignedIn = { true })
        val missing = AlbumViewModel("", FakeLibrary(), FakeSubsonic(emptySet()), isSignedIn = { true })
        advanceUntilIdle()
        assertEquals("Loading album failed (HTTP 404)", failing.state.value.error)
        assertEquals("Missing album id", missing.state.value.error)
    }

    @Test
    fun signedOutSkipsSubsonic() = runTest(dispatcher) {
        val subsonic = FakeSubsonic(setOf("song:v1"))
        val vm = AlbumViewModel("b1", FakeLibrary(), subsonic, isSignedIn = { false })
        advanceUntilIdle()
        assertTrue(subsonic.lookups.isEmpty())
        assertEquals(3, vm.state.value.tracks.size)
    }

    @Test
    fun addToSubsonicFillsArtistsFromTheAlbum() = runTest(dispatcher) {
        val subsonic = FakeSubsonic(emptySet())
        val vm = AlbumViewModel("b1", FakeLibrary(), subsonic, isSignedIn = { true })
        advanceUntilIdle()
        vm.addToSubsonic(album.tracks[0], artUrl = "https://art")
        vm.addToSubsonic(album.tracks[2], artUrl = "") // no video id: refused
        advanceUntilIdle()
        val added = subsonic.added.single()
        assertEquals("v1", added.ytVideoId)
        assertEquals("Lil Wayne", added.artist)
        assertEquals("Lil Wayne", added.albumArtist)
        assertEquals("Tha Carter IV", added.album)
        assertEquals("https://art", added.artUrl)
    }

    @Test
    fun subsonicResolveAndErrorDetails() {
        assertEquals(
            mapOf("song:a" to true, "song:b" to false, "album:c" to true),
            parseSubsonicResolve("""{"songs": {"song:a": {"available": true}, "song:b": {}}, "albums": {"album:c": {"available": true}}}"""),
        )
        assertEquals("Missing album artist", errorDetail("""{"detail": "Missing album artist"}"""))
        assertEquals("", errorDetail("""{"detail": [{"loc": ["body"], "msg": "field required"}]}"""))
        assertEquals("", errorDetail("<html>Bad Gateway</html>"))
        assertEquals("", errorDetail(null))
        assertEquals(
            "Add to Subsonic failed (HTTP 400): Missing album artist",
            HelixHttpException(400, "Missing album artist").toUserMessage("Add to Subsonic"),
        )
    }
}
