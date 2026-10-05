package com.example.helixapp

import com.example.helixapp.data.LibraryRepository
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
class HistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeRepo : LibraryRepository {
        val calls = mutableListOf<Triple<String?, Int, Int>>()
        var failWith: Exception? = null
        var hasMore = true

        override suspend fun history(event: String?, offset: Int, limit: Int): HistoryPage {
            calls += Triple(event, offset, limit)
            failWith?.let { throw it }
            val items = (0 until 2).map { item("${event ?: "all"}-${offset + it}") }
            return HistoryPage(items, hasMore)
        }

        override suspend fun album(browseId: String): AlbumView = error("not used")
    }

    private var now = 1_000_000L

    private fun vm(repo: FakeRepo, signedIn: Boolean = true) =
        HistoryViewModel(repo, isSignedIn = { signedIn }, clock = { now })

    @Test
    fun loadsTheFirstPageOnStart() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = vm(repo)
        advanceUntilIdle()
        assertEquals(listOf(Triple<String?, Int, Int>(null, 0, HistoryViewModel.PAGE_SIZE)), repo.calls)
        assertEquals(listOf("all-0", "all-1"), vm.state.value.items.map { it.id })
        assertTrue(vm.state.value.hasMore)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun filterReloadsWithItsEvent() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = vm(repo)
        advanceUntilIdle()
        vm.setFilter(HistoryFilter.Skipped)
        advanceUntilIdle()
        assertEquals("skipped", repo.calls.last().first)
        assertEquals(listOf("skipped-0", "skipped-1"), vm.state.value.items.map { it.id })
        // Choosing the same filter again does nothing.
        vm.setFilter(HistoryFilter.Skipped)
        advanceUntilIdle()
        assertEquals(2, repo.calls.size)
    }

    @Test
    fun loadMoreAppendsFromTheCurrentCount() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = vm(repo)
        advanceUntilIdle()
        repo.hasMore = false
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(2, repo.calls.last().second)
        assertEquals(listOf("all-0", "all-1", "all-2", "all-3"), vm.state.value.items.map { it.id })
        assertFalse(vm.state.value.hasMore)
    }

    @Test
    fun errorsBecomeAMessageAndKeepTheList() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = vm(repo)
        advanceUntilIdle()
        repo.failWith = HelixHttpException(500)
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(2, vm.state.value.items.size)
        assertTrue(vm.state.value.error!!.isNotBlank())
        assertFalse(vm.state.value.loading)
        repo.failWith = null
        vm.loadMore()
        advanceUntilIdle()
        assertNull(vm.state.value.error)
    }

    @Test
    fun signedOutShowsAHintWithoutCallingTheServer() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = vm(repo, signedIn = false)
        advanceUntilIdle()
        assertTrue(repo.calls.isEmpty())
        assertTrue(vm.state.value.error!!.contains("Log in"))
    }

    @Test
    fun refreshOnlyWhenStale() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = vm(repo)
        advanceUntilIdle()
        now += 10_000
        vm.refreshIfStale()
        advanceUntilIdle()
        assertEquals(1, repo.calls.size)
        now += 30_000
        vm.refreshIfStale()
        advanceUntilIdle()
        assertEquals(2, repo.calls.size)
    }

    private companion object {
        fun item(id: String) = HistoryItemUi(
            id = id, title = id, artist = "", album = "", event = "completed", playedAtMs = 0,
            artUrl = "", durationMs = 0, source = "", ytVideoId = "", ytBrowseId = "",
            subsonicSongId = "", mbRecordingId = "", mbArtistId = "",
        )
    }
}
