package com.example.helixapp

import com.example.helixapp.data.RatedTrack
import com.example.helixapp.data.RatingRepository
import com.example.helixapp.data.SubsonicLookup
import com.example.helixapp.data.SubsonicRepository
import com.example.helixapp.data.SubsonicTrackRequest
import com.example.helixapp.playback.NowPlayingUi
import com.example.helixapp.playback.PlayerStateSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
class NowPlayingViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun song(id: String, yt: String? = "v-$id", sub: String? = null, source: String = "ytmusic") =
        NowPlayingUi(id, "Song $id", "Artist", "Album", "", 200_000, source, yt, sub)

    private val store = MutableStateFlow<PlayerStateSnapshot?>(null)

    private fun play(now: NowPlayingUi?) {
        store.value = PlayerStateSnapshot(now, listOfNotNull(), isPlaying = true, activeStationName = "Radio")
    }

    private class FakeRatings : RatingRepository {
        val liked = mutableSetOf<String>()
        var toggleFails = false
        val toggles = mutableListOf<String>()
        override suspend fun isLiked(track: RatedTrack) = track.title in liked
        override suspend fun isDisliked(track: RatedTrack) = false
        override suspend fun toggleLike(track: RatedTrack) {
            toggles += "like ${track.title}"
            if (toggleFails) throw HelixHttpException(500)
        }
        override suspend fun toggleDislike(track: RatedTrack) {
            toggles += "dislike ${track.title}"
            if (toggleFails) throw HelixHttpException(500)
        }
    }

    private class FakeSubsonic : SubsonicRepository {
        val available = mutableSetOf<String>()
        val lookups = mutableListOf<SubsonicLookup>()
        val added = mutableListOf<SubsonicTrackRequest>()
        var addFails = false
        override suspend fun resolve(songs: List<SubsonicLookup>, albums: List<SubsonicLookup>): Map<String, Boolean> {
            lookups += songs
            return songs.associate { it.key to (it.key in available) }
        }
        override suspend fun addTrack(track: SubsonicTrackRequest) {
            if (addFails) throw HelixHttpException(400, "Missing album artist")
            added += track
        }
        override suspend fun addAlbum(album: JSONObject) = Unit
    }

    private fun vm(ratings: FakeRatings = FakeRatings(), subsonic: FakeSubsonic = FakeSubsonic(), signedIn: Boolean = true) =
        NowPlayingViewModel(store, refreshState = {}, ratings, subsonic, isSignedIn = { signedIn })

    @Test
    fun mirrorsTheSharedStateAndLooksUpTheSong() = runTest(dispatcher) {
        val ratings = FakeRatings().apply { liked += "Song a" }
        val subsonic = FakeSubsonic().apply { available += "song:v-a" }
        play(song("a"))
        val vm = vm(ratings, subsonic)
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals("a", s.now?.queueItemId)
        assertEquals("Radio", s.activeStationName)
        assertTrue(s.backendPlaying)
        assertTrue(s.liked)
        assertEquals(true, s.inSubsonic)
    }

    @Test
    fun subsonicSongsAreInSubsonicWithoutALookup() = runTest(dispatcher) {
        val subsonic = FakeSubsonic()
        play(song("a", yt = null, sub = "s1", source = "subsonic"))
        val vm = vm(subsonic = subsonic)
        advanceUntilIdle()
        assertEquals(true, vm.state.value.inSubsonic)
        assertTrue(subsonic.lookups.isEmpty())
    }

    @Test
    fun songsWithoutAYouTubeIdUseATextKey() {
        val key = NowPlayingViewModel.subsonicKey(NowPlayingUi("q", " How  To Love ", "Lil Wayne", "Tha Carter IV", "", 240_000, "inbound", null, null))
        assertEquals("song:text:how to love|lil wayne|tha carter iv|240000", key)
    }

    @Test
    fun aNewSongResetsAndRechecks() = runTest(dispatcher) {
        val ratings = FakeRatings().apply { liked += "Song a" }
        play(song("a"))
        val vm = vm(ratings)
        advanceUntilIdle()
        assertTrue(vm.state.value.liked)
        play(song("b"))
        advanceUntilIdle()
        assertFalse(vm.state.value.liked)
        assertEquals("b", vm.state.value.now?.queueItemId)
        // A new snapshot of the same song (e.g. pause) updates the play state only.
        store.value = store.value!!.copy(isPlaying = false)
        advanceUntilIdle()
        assertFalse(vm.state.value.backendPlaying)
        assertEquals("b", vm.state.value.now?.queueItemId)
    }

    @Test
    fun likingIsOptimisticAndRollsBackOnFailure() = runTest(dispatcher) {
        val ratings = FakeRatings()
        play(song("a"))
        val vm = vm(ratings)
        advanceUntilIdle()
        val track = RatedTrack("Song a", "Artist", "Album", 200_000, "", "ytmusic", "v-a", null)
        vm.toggleLike(track)
        assertTrue(vm.state.value.liked)
        assertTrue(vm.state.value.ratingInFlight)
        advanceUntilIdle()
        assertTrue(vm.state.value.liked)
        assertFalse(vm.state.value.ratingInFlight)

        ratings.toggleFails = true
        vm.toggleDislike(track)
        assertTrue(vm.state.value.disliked)
        assertFalse(vm.state.value.liked) // disliking clears the like…
        advanceUntilIdle()
        assertFalse(vm.state.value.disliked) // …until the server refuses
        assertTrue(vm.state.value.liked)
    }

    @Test
    fun songsWithoutAnIdCantBeRated() = runTest(dispatcher) {
        val ratings = FakeRatings()
        play(song("a"))
        val vm = vm(ratings)
        advanceUntilIdle()
        vm.toggleLike(RatedTrack("x", "y", "", 0, "", "", null, null))
        advanceUntilIdle()
        assertTrue(ratings.toggles.isEmpty())
    }

    @Test
    fun addToSubsonicPollsUntilTheImportShowsUp() = runTest(dispatcher) {
        val subsonic = FakeSubsonic()
        play(song("a"))
        val vm = vm(subsonic = subsonic)
        advanceUntilIdle()
        assertEquals(false, vm.state.value.inSubsonic)
        vm.addToSubsonic()
        runCurrent()
        assertTrue(vm.state.value.addToSubsonicPending)
        assertEquals("v-a", subsonic.added.single().ytVideoId)
        advanceTimeBy(3 * NowPlayingViewModel.SUBSONIC_POLL_MS + 1)
        assertTrue(vm.state.value.addToSubsonicPending)
        subsonic.available += "song:v-a"
        advanceTimeBy(NowPlayingViewModel.SUBSONIC_POLL_MS + 1)
        runCurrent()
        assertEquals(true, vm.state.value.inSubsonic)
        assertFalse(vm.state.value.addToSubsonicPending)
    }

    @Test
    fun pollingGivesUpAndFailedAddsStopAtOnce() = runTest(dispatcher) {
        val subsonic = FakeSubsonic()
        play(song("a"))
        val vm = vm(subsonic = subsonic)
        advanceUntilIdle()
        vm.addToSubsonic()
        advanceUntilIdle()
        assertFalse(vm.state.value.addToSubsonicPending)
        assertEquals(false, vm.state.value.inSubsonic)

        subsonic.addFails = true
        vm.addToSubsonic()
        runCurrent()
        assertFalse(vm.state.value.addToSubsonicPending)
    }

    @Test
    fun signedOutShowsAHint() = runTest(dispatcher) {
        val vm = vm(signedIn = false)
        advanceUntilIdle()
        assertEquals("Not logged in — go to Login", vm.state.value.error)
        assertNull(vm.state.value.now)
    }

    @Test
    fun ratingParsers() {
        val keys = listOf("liked", "is_liked")
        assertTrue(parseRatingFlag("true", keys))
        assertTrue(parseRatingFlag("""{"is_liked": true}""", keys))
        assertFalse(parseRatingFlag("""{"liked": false}""", keys))
        assertFalse(parseRatingFlag("nope", keys))
        assertEquals(
            listOf("Dirty" to "Jessie Murph"),
            parseLikedSongs("""{"items": [{"title": "Dirty", "artist": "Jessie Murph"}]}"""),
        )
    }
}
