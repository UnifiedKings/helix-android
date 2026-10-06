package com.example.helixapp

import com.example.helixapp.data.SearchResults
import com.example.helixapp.data.SubsonicLookup
import com.example.helixapp.data.SubsonicRepository
import com.example.helixapp.data.SubsonicTrackRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeLibrary : FakeLibraryRepository() {
        val queries = mutableListOf<String>()
        /** Queries listed here wait until the test completes their gate. */
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        var empty = false

        override suspend fun search(query: String): SearchResults {
            queries += query
            gates[query]?.await()
            if (empty) return SearchResults(emptyList(), emptyList())
            return SearchResults(
                songs = listOf(SearchSong("$query song", "View artist", "Tha Carter IV", "", "v-$query")),
                albums = listOf(SearchAlbum("Tha Carter IV", "Lil Wayne", "2011", "", "b-$query")),
            )
        }

        override suspend fun searchArtists(query: String, limit: Int) =
            if (empty) emptyList() else listOf(SearchArtist("$query artist", "", "UC-$query"))
    }

    private class FakeSubsonic : SubsonicRepository {
        val lookups = mutableListOf<String>()
        val added = mutableListOf<SubsonicTrackRequest>()
        override suspend fun resolve(songs: List<SubsonicLookup>, albums: List<SubsonicLookup>): Map<String, Boolean> {
            lookups += (songs + albums).map { it.key }
            return (songs + albums).associate { it.key to it.key.startsWith("song:") }
        }
        override suspend fun addTrack(track: SubsonicTrackRequest) { added += track }
        override suspend fun addAlbum(album: JSONObject) = Unit
    }

    private class FakeRecents(var items: List<RecentSearchPlay.Item> = emptyList()) : RecentsStore {
        override fun get() = items
        override fun clear() { items = emptyList() }
    }

    private fun vm(
        library: FakeLibrary = FakeLibrary(),
        subsonic: FakeSubsonic = FakeSubsonic(),
        recents: FakeRecents = FakeRecents(),
        signedIn: Boolean = true,
    ) = SearchViewModel(library, subsonic, recents, isSignedIn = { signedIn })

    @Test
    fun searchesAfterTheDebounceOnly() = runTest(dispatcher) {
        val library = FakeLibrary()
        val vm = vm(library)
        runCurrent()
        vm.setQuery("lil")
        advanceTimeBy(200)
        vm.setQuery("lil wayne")
        advanceTimeBy(SearchViewModel.DEBOUNCE_MS - 1)
        assertTrue(library.queries.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf("lil wayne"), library.queries)
        val s = vm.state.value
        assertEquals(listOf("lil wayne song"), s.songs.map { it.title })
        assertEquals(listOf("lil wayne artist"), s.artists.map { it.name })
        assertTrue(s.songInSubsonic(s.songs.single()))
        assertFalse(s.albumInSubsonic(s.albums.single()))
        assertFalse(s.loading)
        assertNull(s.message)
    }

    @Test
    fun aNewerQueryWinsOverASlowOlderOne() = runTest(dispatcher) {
        val library = FakeLibrary().apply { gates["slow"] = CompletableDeferred() }
        val vm = vm(library)
        vm.setQuery("slow")
        advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 1)
        runCurrent()
        vm.setQuery("fast")
        advanceUntilIdle()
        library.gates.getValue("slow").complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("fast song"), vm.state.value.songs.map { it.title })
    }

    @Test
    fun emptyResultsAndSignedOut() = runTest(dispatcher) {
        val empty = vm(FakeLibrary().apply { this.empty = true })
        empty.setQuery("zzz")
        val signedOut = vm(signedIn = false)
        signedOut.setQuery("lil wayne")
        advanceUntilIdle()
        assertEquals("No results", empty.state.value.message)
        assertEquals("Not logged in — go to Settings", signedOut.state.value.message)
    }

    @Test
    fun recentsShowWhileTheQueryIsEmptyAndCanBeCleared() = runTest(dispatcher) {
        val subsonic = FakeSubsonic()
        val recents = FakeRecents(
            listOf(
                RecentSearchPlay.Item(RecentSearchPlay.Kind.SONG, "v1", "Dirty", "Jessie Murph", "", "", "", ts = 2),
                RecentSearchPlay.Item(RecentSearchPlay.Kind.SONG, "s9", "Local", "Someone", "", "", "", source = "subsonic", ts = 1),
                RecentSearchPlay.Item(RecentSearchPlay.Kind.ALBUM, "b1", "Tha Carter IV", "Lil Wayne", "", "2011", "", ts = 0),
            )
        )
        val vm = vm(subsonic = subsonic, recents = recents)
        advanceUntilIdle()
        assertEquals(3, vm.state.value.recents.size)
        assertEquals(listOf("song:v1", "song:", "album:b1"), subsonic.lookups)
        vm.clearRecents()
        assertTrue(vm.state.value.recents.isEmpty())
        assertTrue(recents.items.isEmpty())
    }

    @Test
    fun recentSubsonicSongsKeepTheirSubsonicId() {
        val song = RecentSearchPlay.Item(RecentSearchPlay.Kind.SONG, "s9", "Local", "Someone", "", "", "", source = "subsonic", ts = 1)
            .toSearchSong()
        assertEquals("", song.videoId)
        assertEquals("s9", song.subsonicSongId)
        assertTrue(song.isFromSubsonic)
    }

    @Test
    fun addToSubsonicReplacesJunkArtistsWithTheAlbumArtist() = runTest(dispatcher) {
        val subsonic = FakeSubsonic()
        val vm = vm(subsonic = subsonic)
        vm.setQuery("x")
        advanceUntilIdle()
        vm.addSongToSubsonic(vm.state.value.songs.single(), artUrl = "art")
        advanceUntilIdle()
        val added = subsonic.added.single()
        assertEquals("Lil Wayne", added.artist)
        assertEquals("Lil Wayne", added.albumArtist)
        assertEquals("Tha Carter IV", added.album)
    }
}
