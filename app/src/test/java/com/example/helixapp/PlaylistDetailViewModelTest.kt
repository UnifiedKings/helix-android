package com.example.helixapp

import com.example.helixapp.data.PlaylistRepository
import com.example.helixapp.data.SearchResults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun track(id: String) = PlaylistTrackUi(id, id, "", "", "", 0, "", "", "", "", "", "")

    private open class FakePlaylists(var tracks: List<PlaylistTrackUi>, val systemKey: String = "") : PlaylistRepository {
        val calls = mutableListOf<String>()
        var failReorder = false
        var failRemoveAfter = Int.MAX_VALUE
        val added = mutableListOf<SearchSong>()
        var failAddAfter = Int.MAX_VALUE

        override suspend fun list() = emptyList<PlaylistUi>()
        override suspend fun create(name: String) = Unit
        override suspend fun delete(playlistId: String) { calls += "delete $playlistId" }
        override suspend fun detail(playlistId: String): PlaylistDetail {
            calls += "detail $playlistId"
            return PlaylistDetail(if (playlistId == "liked") "Liked Songs" else "Gym", "", if (playlistId == "liked") "liked" else systemKey, tracks)
        }
        override suspend fun reorderTracks(playlistId: String, trackIds: List<String>): PlaylistDetail {
            calls += "reorder ${trackIds.joinToString(",")}"
            if (failReorder) throw HelixHttpException(500)
            tracks = trackIds.map { id -> tracks.first { it.id == id } }
            return PlaylistDetail("Gym", "", systemKey, tracks)
        }
        override suspend fun removeTrack(playlistId: String, trackId: String) {
            if (calls.count { it.startsWith("remove") } >= failRemoveAfter) throw HelixHttpException(500)
            calls += "remove $trackId"
            tracks = tracks.filterNot { it.id == trackId }
        }
        override suspend fun addTrack(playlistId: String, song: SearchSong) {
            if (added.size >= failAddAfter) throw HelixHttpException(500)
            added += song
        }
    }

    private fun ids(vm: PlaylistDetailViewModel) = vm.state.value.tracks.map { it.id }

    private fun loaded(repo: FakePlaylists, id: String = "p1") =
        PlaylistDetailViewModel(id, repo, isSignedIn = { true }).also { it.refresh() }

    @Test
    fun loadsAndFallsBackToLikedSongsByKey() = runTest(dispatcher) {
        val repo = FakePlaylists(listOf(track("a")), systemKey = "liked")
        val vm = loaded(repo, id = "uuid-of-liked")
        advanceUntilIdle()
        assertEquals(listOf("detail uuid-of-liked", "detail liked"), repo.calls)
        assertEquals("Liked Songs", vm.state.value.title)
        assertEquals("liked", vm.state.value.playId)
        assertFalse(vm.state.value.canEdit)
        vm.setEditMode(true)
        assertFalse(vm.state.value.editMode)
    }

    @Test
    fun moveHelpers() {
        val t = listOf("a", "b", "c", "d").map(::track)
        fun ids(list: List<PlaylistTrackUi>) = list.joinToString("") { it.id }
        assertEquals("bacd", ids(moveSelectedUp(t, setOf("b"))))
        assertEquals("abcd", ids(moveSelectedUp(t, setOf("a", "b")))) // already at the top
        assertEquals("acbd", ids(moveSelectedDown(t, setOf("b"))))
        assertEquals("abcd", ids(moveSelectedDown(t, setOf("c", "d"))))
        assertEquals("cabd", ids(moveSelectedToTop(t, setOf("c"))))
        assertEquals("bdac", ids(moveSelectedToTop(t, setOf("d", "b"))))
    }

    @Test
    fun reorderIsOptimisticAndRollsBack() = runTest(dispatcher) {
        val repo = FakePlaylists(listOf("a", "b", "c").map(::track))
        val vm = loaded(repo)
        advanceUntilIdle()
        vm.setEditMode(true)
        vm.toggleSelection(vm.state.value.tracks[2])
        vm.moveSelectedToTop()
        assertEquals(listOf("c", "a", "b"), ids(vm))
        advanceUntilIdle()
        assertEquals("reorder c,a,b", repo.calls.last())
        assertEquals(listOf("c", "a", "b"), ids(vm))

        repo.failReorder = true
        vm.moveSelectedDown()
        advanceUntilIdle()
        assertEquals(listOf("c", "a", "b"), ids(vm))
    }

    @Test
    fun dragMovesLocallyThenSaves() = runTest(dispatcher) {
        val repo = FakePlaylists(listOf("a", "b", "c").map(::track))
        val vm = loaded(repo)
        advanceUntilIdle()
        val a = vm.state.value.tracks[0]
        vm.moveTrack(a, +1)
        vm.moveTrack(a, +1)
        vm.moveTrack(a, +1) // past the end: ignored
        assertEquals(listOf("b", "c", "a"), ids(vm))
        assertFalse(repo.calls.any { it.startsWith("reorder") })
        vm.saveOrder()
        advanceUntilIdle()
        assertEquals("reorder b,c,a", repo.calls.last())
    }

    @Test
    fun bulkRemoveStopsAtTheFirstFailureAndReloads() = runTest(dispatcher) {
        val repo = FakePlaylists(listOf("a", "b", "c").map(::track)).apply { failRemoveAfter = 1 }
        val vm = loaded(repo)
        advanceUntilIdle()
        vm.setEditMode(true)
        vm.state.value.tracks.forEach(vm::toggleSelection)
        vm.removeSelected()
        advanceUntilIdle()
        assertEquals(listOf("b", "c"), ids(vm))
        // The ids that are gone drop out of the selection.
        assertEquals(setOf("b", "c"), vm.state.value.selectedIds)
    }

    @Test
    fun deleteClosesThePage() = runTest(dispatcher) {
        val repo = FakePlaylists(listOf(track("a")))
        val vm = loaded(repo)
        advanceUntilIdle()
        var closed = false
        vm.deletePlaylist { closed = true }
        advanceUntilIdle()
        assertTrue(closed)
        assertTrue(repo.calls.contains("delete p1"))
    }

    // ---- The add-songs picker ---------------------------------------------------------------

    private class PickerLibrary : FakeLibraryRepository() {
        override suspend fun search(query: String) = SearchResults(
            songs = listOf(SearchSong("$query 1", "A", "", "", "v1"), SearchSong("$query 2", "A", "", "", "v2")),
            albums = listOf(SearchAlbum("Album", "A", "", "art", "b1"), SearchAlbum("No id", "A", "", "", "")),
        )
        override suspend fun searchArtists(query: String, limit: Int): List<SearchArtist> = throw HelixHttpException(500)
        override suspend fun album(browseId: String) =
            AlbumView("", "", "", "", listOf(AlbumTrack(1, "Track", "", 100, "t1")))
        override suspend fun artistPopular(browseId: String, limit: Int) = listOf(SearchSong("Hit", "", "", "", "h1"))
    }

    @Test
    fun pickerSearchesDrillsInAndAddsUntilAFailure() = runTest(dispatcher) {
        val repo = FakePlaylists(emptyList()).apply { failAddAfter = 1 }
        val vm = PlaylistPickerViewModel("p1", PickerLibrary(), repo)
        vm.setQuery("dirty")
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(2, s.songs.size)
        assertEquals(listOf("Album"), s.albums.map { it.title }) // albums need a browse id
        assertTrue(s.artists.isEmpty()) // artist search failed: songs still show

        vm.openAlbum(s.albums.single())
        advanceUntilIdle()
        val drilled = vm.state.value.drillSongs.single()
        assertEquals("Album", vm.state.value.drillTitle)
        assertEquals("A", drilled.artist)
        assertEquals("art", drilled.thumbnailUrl)
        vm.closeDrill()
        assertEquals(2, vm.state.value.displayedSongs.size)

        vm.openArtist(SearchArtist("Jessie Murph", "face", "UC1"))
        advanceUntilIdle()
        assertEquals("Jessie Murph", vm.state.value.drillSongs.single().artist)
        assertEquals("face", vm.state.value.drillSongs.single().thumbnailUrl)
        vm.closeDrill()

        vm.state.value.songs.forEach(vm::toggle)
        assertEquals(2, vm.state.value.selected.size)
        vm.addSelected()
        advanceUntilIdle()
        assertEquals(1, repo.added.size)
        assertEquals(setOf("v1"), vm.state.value.addedKeys)
        assertEquals(setOf("v2"), vm.state.value.selected.keys) // the failed one stays selected
        assertFalse(vm.state.value.adding)
    }
}
