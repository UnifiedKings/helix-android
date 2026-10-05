package com.example.helixapp

import com.example.helixapp.data.SimilarArtists
import com.example.helixapp.data.StationRepository
import com.example.helixapp.data.SubsonicLookup
import com.example.helixapp.data.SubsonicRepository
import com.example.helixapp.data.SubsonicTrackRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
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
class ArtistViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeLibrary(
        val name: String = "Lil Wayne",
        /** Similar-artist responses in order; the last one repeats. */
        val similar: List<SimilarArtists> = listOf(SimilarArtists(listOf(SimilarArtistUi("Drake")), "resolved")),
        val failDetail: Exception? = null,
    ) : FakeLibraryRepository() {
        var similarCalls = 0

        override suspend fun artist(browseId: String): ArtistDetailUi {
            failDetail?.let { throw it }
            return ArtistDetailUi(browseId, name, thumbnailUrl = "small", mbArtistId = "", resolutionStatus = "resolved")
        }
        override suspend fun searchArtists(query: String, limit: Int) =
            listOf(SearchArtist("Someone Else", "x", "UCx"), SearchArtist(name, "big", "UC2"))
        override suspend fun artistPopular(browseId: String, limit: Int) =
            listOf(SearchSong("Lollipop", name, "Tha Carter III", "", "v1"))
        override suspend fun artistAlbums(browseId: String): List<SearchAlbum> = throw HelixHttpException(500)
        override suspend fun similarArtists(browseId: String): SimilarArtists =
            similar[similarCalls++.coerceAtMost(similar.lastIndex)]
    }

    private class FakeStations : StationRepository {
        val created = mutableListOf<String>()
        override suspend fun list(): List<StationUi> = emptyList()
        override suspend fun createArtistStation(artist: String): String { created += artist; return "s1" }
        override suspend fun providers() = emptyList<StationProviderUi>()
        override suspend fun create(payload: JSONObject) = Unit
        override suspend fun update(stationId: String, payload: JSONObject) = Unit
        override suspend fun delete(stationId: String) = Unit
    }

    private class FakeSubsonic : SubsonicRepository {
        val added = mutableListOf<SubsonicTrackRequest>()
        override suspend fun resolve(songs: List<SubsonicLookup>, albums: List<SubsonicLookup>) = emptyMap<String, Boolean>()
        override suspend fun addTrack(track: SubsonicTrackRequest) { added += track }
        override suspend fun addAlbum(album: JSONObject) = Unit
    }

    private fun vm(library: FakeLibrary, stations: FakeStations = FakeStations(), subsonic: FakeSubsonic = FakeSubsonic()) =
        ArtistViewModel("UC2", library, stations, subsonic)

    @Test
    fun loadsDetailsPopularAndSimilar() = runTest(dispatcher) {
        val vm = vm(FakeLibrary())
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals("Lil Wayne", s.artist.name)
        // The search result with the same name supplies the better image.
        assertEquals("big", s.artist.thumbnailUrl)
        assertEquals(listOf("Lollipop"), s.popular.map { it.title })
        // A failed albums request just leaves the section empty.
        assertTrue(s.albums.isEmpty())
        assertEquals(SimilarState.Ready, s.similarState)
        assertEquals(listOf("Drake"), s.similar.map { it.name })
        assertFalse(s.loading)
        assertEquals("", s.status)
    }

    @Test
    fun pollsSimilarArtistsWhileTheServerResolves() = runTest(dispatcher) {
        val library = FakeLibrary(
            similar = listOf(
                SimilarArtists(emptyList(), "resolving"),
                SimilarArtists(emptyList(), "resolving"),
                SimilarArtists(listOf(SimilarArtistUi("Drake")), "resolved"),
            )
        )
        val vm = vm(library)
        advanceUntilIdle()
        assertEquals(3, library.similarCalls)
        assertEquals(2 * ArtistViewModel.SIMILAR_POLL_MS, currentTime)
        assertEquals(SimilarState.Ready, vm.state.value.similarState)
    }

    @Test
    fun givesUpAfterTheLastAttempt() = runTest(dispatcher) {
        val library = FakeLibrary(similar = listOf(SimilarArtists(emptyList(), "resolving")))
        val vm = vm(library)
        advanceUntilIdle()
        assertEquals(ArtistViewModel.SIMILAR_ATTEMPTS, library.similarCalls)
        assertEquals(SimilarState.Loading, vm.state.value.similarState)
        assertEquals("Similar artists are still loading", vm.state.value.status)
    }

    @Test
    fun resolvedWithoutResultsIsEmpty() = runTest(dispatcher) {
        val vm = vm(FakeLibrary(similar = listOf(SimilarArtists(emptyList(), "failed"))))
        advanceUntilIdle()
        assertEquals(SimilarState.Empty, vm.state.value.similarState)
        assertEquals("", vm.state.value.status)
    }

    @Test
    fun detailErrorsAndUnknownArtists() = runTest(dispatcher) {
        val failing = vm(FakeLibrary(failDetail = HelixHttpException(404)))
        val unnamed = vm(FakeLibrary(name = ""))
        advanceUntilIdle()
        assertEquals("Loading artist failed (HTTP 404)", failing.state.value.status)
        assertFalse(failing.state.value.loading)
        assertEquals("Artist not found", unnamed.state.value.status)
    }

    @Test
    fun createStationAndAddToSubsonic() = runTest(dispatcher) {
        val stations = FakeStations()
        val subsonic = FakeSubsonic()
        val vm = vm(FakeLibrary(), stations, subsonic)
        advanceUntilIdle()
        vm.createStation()
        vm.addToSubsonic(vm.state.value.popular.single(), artUrl = "https://art")
        advanceUntilIdle()
        assertEquals(listOf("Lil Wayne"), stations.created)
        val added = subsonic.added.single()
        assertEquals("v1", added.ytVideoId)
        assertEquals("Tha Carter III", added.album)
        assertEquals("https://art", added.artUrl)
    }
}
