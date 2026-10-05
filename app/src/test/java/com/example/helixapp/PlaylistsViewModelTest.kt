package com.example.helixapp

import com.example.helixapp.data.PlaylistRepository
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeRepo : PlaylistRepository {
        val playlists = mutableListOf(playlist("p1", "Gym"), playlist("p2", "Chill"))
        var listCalls = 0
        var failWith: Exception? = null

        override suspend fun list(): List<PlaylistUi> {
            listCalls++
            failWith?.let { throw it }
            return playlists.toList()
        }

        override suspend fun create(name: String) {
            failWith?.let { throw it }
            playlists += playlist("p${playlists.size + 1}", name)
        }

        override suspend fun delete(playlistId: String) {
            failWith?.let { throw it }
            playlists.removeAll { it.id == playlistId }
        }
    }

    private var now = 1_000_000L

    private fun vm(repo: FakeRepo, signedIn: Boolean = true) =
        PlaylistsViewModel(repo, isSignedIn = { signedIn }, clock = { now })

    @Test
    fun loadsOnStart() = runTest(dispatcher) {
        val vm = vm(FakeRepo())
        advanceUntilIdle()
        assertEquals(listOf("Gym", "Chill"), vm.state.value.playlists.map { it.name })
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.error)
    }

    @Test
    fun signedOutSkipsTheServer() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = vm(repo, signedIn = false)
        advanceUntilIdle()
        assertEquals(0, repo.listCalls)
        assertFalse(vm.state.value.signedIn)
    }

    @Test
    fun loadErrorsAreShown() = runTest(dispatcher) {
        val repo = FakeRepo().apply { failWith = HelixHttpException(502) }
        val vm = vm(repo)
        advanceUntilIdle()
        assertTrue(vm.state.value.error!!.isNotBlank())
        assertTrue(vm.state.value.playlists.isEmpty())
    }

    @Test
    fun deleteRemovesItAndCreateTriggersARefresh() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = vm(repo)
        advanceUntilIdle()
        vm.delete(vm.state.value.playlists.first())
        advanceUntilIdle()
        assertEquals(listOf("Chill"), vm.state.value.playlists.map { it.name })

        var closed = false
        vm.create("Road Trip") { closed = true }
        advanceUntilIdle()
        assertTrue(closed)
        // The refresh signal reloads the list.
        assertEquals(listOf("Chill", "Road Trip"), vm.state.value.playlists.map { it.name })
    }

    @Test
    fun failedCreateLeavesTheDialogOpen() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = vm(repo)
        advanceUntilIdle()
        repo.failWith = HelixHttpException(500)
        var closed = false
        vm.create("Nope") { closed = true }
        advanceUntilIdle()
        assertFalse(closed)
    }

    @Test
    fun refreshOnlyWhenStale() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = vm(repo)
        advanceUntilIdle()
        vm.refreshIfStale()
        advanceUntilIdle()
        assertEquals(1, repo.listCalls)
        now += 31_000
        vm.refreshIfStale()
        advanceUntilIdle()
        assertEquals(2, repo.listCalls)
    }

    private companion object {
        fun playlist(id: String, name: String) = PlaylistUi(id, name, systemKey = "", kind = "", trackCount = 0, thumbnailUrl = "")
    }
}
