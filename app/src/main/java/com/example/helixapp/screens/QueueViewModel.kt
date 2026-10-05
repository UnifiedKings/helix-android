package com.example.helixapp

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.helixapp.playback.NowPlayingUi
import com.example.helixapp.playback.PlaybackActions
import com.example.helixapp.playback.PlayerStateSnapshot
import com.example.helixapp.playback.PlayerStateStore
import com.example.helixapp.playback.QueueItemUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class QueueUiState(
    val nowPlaying: NowPlayingUi? = null,
    val queue: List<QueueItemUi> = emptyList(),
    val loading: Boolean = false,
    /** Shown in place of an empty list: "Queue is empty", a sign-in hint or an error. */
    val emptyMessage: String = "",
    val draggingId: String? = null,
    val reorderError: String? = null,
    val clearing: Boolean = false,
    /** Bumped when the list should scroll back to the current song. */
    val recenterRequests: Int = 0,
) {
    /** Clear only makes sense when there's something besides the current song. */
    val canClear: Boolean get() = queue.any { it.queueItemId != nowPlaying?.queueItemId }
}

/** What the queue sheet asks of the shared queue (PlaybackActions on the device). */
interface QueueActions {
    suspend fun refresh()
    suspend fun jumpTo(index: Int)
    suspend fun moveToNext(queueItemId: String)
    suspend fun remove(queueItemId: String, isCurrent: Boolean)
    suspend fun clearKeepingCurrent()
    suspend fun reorder(orderedIds: List<String>)
}

/**
 * The queue sheet. The list follows the shared [PlayerStateStore] snapshot, except while the
 * user drags a song or a new order is being saved: then the local order is ahead of the shared
 * state, and it catches up afterwards.
 */
class QueueViewModel(
    private val playerState: StateFlow<PlayerStateSnapshot?>,
    private val actions: QueueActions,
    private val isSignedIn: () -> Boolean,
) : ViewModel() {

    constructor(app: Application) : this(
        PlayerStateStore.state,
        object : QueueActions {
            override suspend fun refresh() = PlayerStateStore.refresh(app)
            override suspend fun jumpTo(index: Int) = PlaybackActions.jumpTo(app, index)
            override suspend fun moveToNext(queueItemId: String) = PlaybackActions.moveToNext(app, queueItemId)
            override suspend fun remove(queueItemId: String, isCurrent: Boolean) =
                PlaybackActions.removeFromQueue(app, queueItemId, isCurrent)
            override suspend fun clearKeepingCurrent() = PlaybackActions.clearQueueKeepingCurrent(app)
            override suspend fun reorder(orderedIds: List<String>) = PlaybackActions.reorderQueue(app, orderedIds)
        },
        { !HelixPrefs.getSessionToken(app).isNullOrBlank() },
    )

    private val _state = MutableStateFlow(
        QueueUiState(nowPlaying = playerState.value?.now, queue = playerState.value?.queue.orEmpty())
    )
    val state: StateFlow<QueueUiState> = _state.asStateFlow()

    private var savingOrder = false
    private var dragStartOrder: List<String> = emptyList()

    init {
        refresh()
        viewModelScope.launch { playerState.collect { applySharedState() } }
    }

    /** Take the shared snapshot, unless a drag or save is ahead of it. */
    private fun applySharedState() {
        val snapshot = playerState.value ?: return
        if (_state.value.draggingId != null || savingOrder) return
        _state.update {
            it.copy(
                nowPlaying = snapshot.now,
                queue = snapshot.queue,
                emptyMessage = if (!it.loading && it.reorderError == null) EMPTY else it.emptyMessage,
            )
        }
    }

    fun refresh() {
        if (!isSignedIn()) {
            _state.update { it.copy(nowPlaying = null, queue = emptyList(), emptyMessage = "Not logged in — go to Settings") }
            return
        }
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                actions.refresh()
                _state.update { it.copy(loading = false, emptyMessage = EMPTY) }
                applySharedState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, emptyMessage = e.toUserMessage("Loading the queue")) }
            }
        }
    }

    fun jumpTo(item: QueueItemUi) {
        if (_state.value.draggingId != null) return
        val index = _state.value.queue.indexOfFirst { it.queueItemId == item.queueItemId }.takeIf { it >= 0 } ?: item.index
        viewModelScope.launchPlaybackAction(
            failureAction = "Jump",
            onSuccess = { _state.update { it.copy(recenterRequests = it.recenterRequests + 1) } },
        ) { actions.jumpTo(index) }
    }

    fun playNext(item: QueueItemUi) {
        viewModelScope.launchPlaybackAction(failureAction = "Move", successMessage = "Playing next: ${item.title}") {
            actions.moveToNext(item.queueItemId)
        }
    }

    fun remove(item: QueueItemUi) {
        val isCurrent = item.queueItemId == _state.value.nowPlaying?.queueItemId
        viewModelScope.launchPlaybackAction(failureAction = "Remove", successMessage = "Removed: ${item.title}") {
            actions.remove(item.queueItemId, isCurrent)
        }
    }

    fun clear() {
        _state.update { it.copy(clearing = true) }
        viewModelScope.launchPlaybackAction(failureAction = "Clear", successMessage = "Queue cleared") {
            try {
                actions.clearKeepingCurrent()
            } finally {
                _state.update { it.copy(clearing = false) }
            }
        }
    }

    // ---- Drag to reorder ------------------------------------------------------------------

    fun startDrag(queueItemId: String) {
        dragStartOrder = _state.value.queue.map { it.queueItemId }
        _state.update { it.copy(draggingId = queueItemId, reorderError = null) }
    }

    /** Move the dragged song one place down (+1) or up (-1); returns false at either end. */
    fun moveDragged(step: Int): Boolean {
        val id = _state.value.draggingId ?: return false
        val queue = _state.value.queue
        val from = queue.indexOfFirst { it.queueItemId == id }
        val to = from + step
        if (from < 0 || to !in queue.indices) return false
        val updated = queue.toMutableList().apply { add(to, removeAt(from)) }
        _state.update { it.copy(queue = updated) }
        return true
    }

    fun finishDrag() {
        if (_state.value.draggingId == null) return
        val order = _state.value.queue.map { it.queueItemId }
        _state.update { it.copy(draggingId = null) }
        if (dragStartOrder.isNotEmpty() && order != dragStartOrder) saveOrder(order) else applySharedState()
    }

    private fun saveOrder(orderedIds: List<String>) {
        savingOrder = true
        viewModelScope.launch {
            try {
                actions.reorder(orderedIds)
                savingOrder = false
                applySharedState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                savingOrder = false
                val reason = (e as? HelixHttpException)?.let { "HTTP ${it.code}" } ?: e.message ?: e.javaClass.simpleName
                _state.update { it.copy(reorderError = reason) }
                refresh()
            }
        }
    }

    private companion object {
        const val EMPTY = "Queue is empty"
    }
}
