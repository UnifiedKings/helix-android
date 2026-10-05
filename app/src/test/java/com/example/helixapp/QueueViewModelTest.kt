package com.example.helixapp

import com.example.helixapp.playback.NowPlayingUi
import com.example.helixapp.playback.PlayerStateSnapshot
import com.example.helixapp.playback.QueueItemUi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
class QueueViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun item(id: String, index: Int) = QueueItemUi(index, id, id, "", "", "", 0, "", null, null)

    private fun now(id: String) = NowPlayingUi(id, id, "", "", "", 0, "", null, null)

    private fun snapshot(ids: List<String>, current: String) =
        PlayerStateSnapshot(now(current), ids.mapIndexed { i, id -> item(id, i) }, isPlaying = true, activeStationName = null)

    private class FakeActions(val store: MutableStateFlow<PlayerStateSnapshot?>) : QueueActions {
        val calls = mutableListOf<String>()
        var reorderGate: CompletableDeferred<Unit>? = null
        var reorderFails = false

        override suspend fun refresh() { calls += "refresh" }
        override suspend fun jumpTo(index: Int) { calls += "jump $index" }
        override suspend fun moveToNext(queueItemId: String) { calls += "next $queueItemId" }
        override suspend fun remove(queueItemId: String, isCurrent: Boolean) { calls += "remove $queueItemId $isCurrent" }
        override suspend fun clearKeepingCurrent() { calls += "clear" }
        override suspend fun reorder(orderedIds: List<String>) {
            calls += "reorder ${orderedIds.joinToString(",")}"
            reorderGate?.await()
            if (reorderFails) throw HelixHttpException(500)
            val current = store.value?.now?.queueItemId.orEmpty()
            store.value = PlayerStateSnapshot(store.value?.now, orderedIds.mapIndexed { i, id -> QueueItemUi(i, id, id, "", "", "", 0, "", null, null) }, true, null)
            check(current.isNotEmpty())
        }
    }

    private val store = MutableStateFlow<PlayerStateSnapshot?>(null)

    private fun vm(actions: FakeActions = FakeActions(store), signedIn: Boolean = true) =
        QueueViewModel(store, actions, isSignedIn = { signedIn })

    private fun ids(vm: QueueViewModel) = vm.state.value.queue.map { it.queueItemId }

    @Test
    fun followsTheSharedState() = runTest(dispatcher) {
        store.value = snapshot(listOf("a", "b", "c"), current = "a")
        val vm = vm()
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "c"), ids(vm))
        assertTrue(vm.state.value.canClear)
        store.value = snapshot(listOf("a"), current = "a")
        advanceUntilIdle()
        assertEquals(listOf("a"), ids(vm))
        assertFalse(vm.state.value.canClear)
        assertEquals("Queue is empty", vm.state.value.emptyMessage)
    }

    @Test
    fun signedOutShowsAHint() = runTest(dispatcher) {
        val actions = FakeActions(store)
        val vm = vm(actions, signedIn = false)
        advanceUntilIdle()
        assertTrue(actions.calls.isEmpty())
        assertEquals("Not logged in — go to Settings", vm.state.value.emptyMessage)
    }

    @Test
    fun dragMovesOnePlacePerStepAndStopsAtTheEnds() = runTest(dispatcher) {
        store.value = snapshot(listOf("a", "b", "c"), current = "a")
        val actions = FakeActions(store)
        val vm = vm(actions)
        advanceUntilIdle()
        vm.startDrag("a")
        assertFalse(vm.moveDragged(-1))
        assertTrue(vm.moveDragged(+1))
        assertTrue(vm.moveDragged(+1))
        assertFalse(vm.moveDragged(+1))
        assertEquals(listOf("b", "c", "a"), ids(vm))
        vm.finishDrag()
        advanceUntilIdle()
        assertTrue(actions.calls.contains("reorder b,c,a"))
        assertEquals(listOf("b", "c", "a"), ids(vm))
        assertNull(vm.state.value.draggingId)
    }

    @Test
    fun sharedUpdatesWaitForTheDragAndTheSave() = runTest(dispatcher) {
        store.value = snapshot(listOf("a", "b", "c"), current = "a")
        val actions = FakeActions(store).apply { reorderGate = CompletableDeferred() }
        val vm = vm(actions)
        advanceUntilIdle()
        vm.startDrag("c")
        vm.moveDragged(-1)
        // Another device changes the queue mid-drag: the local order stays put.
        store.value = snapshot(listOf("a", "b", "c", "d"), current = "a")
        advanceUntilIdle()
        assertEquals(listOf("a", "c", "b"), ids(vm))
        vm.finishDrag()
        advanceUntilIdle()
        assertEquals(listOf("a", "c", "b"), ids(vm)) // still saving
        actions.reorderGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("a", "c", "b"), ids(vm))
    }

    @Test
    fun aDragThatEndsWhereItStartedSavesNothing() = runTest(dispatcher) {
        store.value = snapshot(listOf("a", "b"), current = "a")
        val actions = FakeActions(store)
        val vm = vm(actions)
        advanceUntilIdle()
        vm.startDrag("b")
        vm.moveDragged(-1)
        vm.moveDragged(+1)
        vm.finishDrag()
        advanceUntilIdle()
        assertTrue(actions.calls.none { it.startsWith("reorder") })
    }

    @Test
    fun aFailedSaveShowsTheErrorAndReloads() = runTest(dispatcher) {
        store.value = snapshot(listOf("a", "b"), current = "a")
        val actions = FakeActions(store).apply { reorderFails = true }
        val vm = vm(actions)
        advanceUntilIdle()
        vm.startDrag("b")
        vm.moveDragged(-1)
        vm.finishDrag()
        advanceUntilIdle()
        assertEquals("HTTP 500", vm.state.value.reorderError)
        assertEquals(2, actions.calls.count { it == "refresh" })
        assertEquals(listOf("a", "b"), ids(vm))
    }

    @Test
    fun rowActions() = runTest(dispatcher) {
        store.value = snapshot(listOf("a", "b", "c"), current = "a")
        val actions = FakeActions(store)
        val vm = vm(actions)
        advanceUntilIdle()
        val recenter = vm.state.value.recenterRequests
        vm.jumpTo(vm.state.value.queue[2])
        vm.playNext(vm.state.value.queue[2])
        vm.remove(vm.state.value.queue[0])
        vm.clear()
        advanceUntilIdle()
        assertEquals(listOf("refresh", "jump 2", "next c", "remove a true", "clear"), actions.calls)
        assertEquals(recenter + 1, vm.state.value.recenterRequests)
        assertFalse(vm.state.value.clearing)
    }
}
