package com.example.helixapp

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.helixapp.data.HelixPlaylistRepository
import com.example.helixapp.data.PlaylistRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val LIKED_SYSTEM_KEY = "liked"

data class PlaylistDetailUiState(
    val playlistId: String,
    val loading: Boolean = false,
    val title: String = "Playlist",
    val coverUrl: String = "",
    val tracks: List<PlaylistTrackUi> = emptyList(),
    val systemKey: String? = null,
    val editMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
) {
    /** Liked Songs can't be reordered, edited or deleted. */
    val canEdit: Boolean get() = playlistId != LIKED_SYSTEM_KEY && systemKey != LIKED_SYSTEM_KEY

    /** The id to play it by: system playlists by their key. */
    val playId: String get() = systemKey?.takeIf { it.isNotBlank() } ?: playlistId

    val selectedTracks: List<PlaylistTrackUi> get() = tracks.filter { it.id in selectedIds }
}

/** A playlist page: its tracks, edit mode with multi-select, reordering and removing. */
class PlaylistDetailViewModel(
    playlistId: String,
    private val repository: PlaylistRepository,
    private val isSignedIn: () -> Boolean,
) : ViewModel() {

    constructor(app: Application, playlistId: String) : this(
        playlistId,
        HelixPlaylistRepository(app),
        { !HelixPrefs.getSessionToken(app).isNullOrBlank() },
    )

    private val _state = MutableStateFlow(PlaylistDetailUiState(playlistId))
    val state: StateFlow<PlaylistDetailUiState> = _state.asStateFlow()

    private val playlistId get() = _state.value.playlistId

    /** Called whenever the page is shown: the playlist may have changed elsewhere meanwhile. */
    fun refresh() {
        if (!isSignedIn()) {
            _state.update { it.copy(tracks = emptyList()) }
            return
        }
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                var detail = repository.detail(playlistId)
                // Opened by id but it's Liked Songs: load it by key, which has the full list.
                if (detail.systemKey == LIKED_SYSTEM_KEY && playlistId != LIKED_SYSTEM_KEY) {
                    detail = runCatching { repository.detail(LIKED_SYSTEM_KEY) }.getOrDefault(detail)
                }
                apply(detail)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(tracks = emptyList()) }
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    private fun apply(detail: PlaylistDetail) = _state.update { s ->
        s.copy(
            title = detail.name,
            coverUrl = detail.thumbnailUrl,
            tracks = detail.tracks,
            systemKey = detail.systemKey,
            selectedIds = s.selectedIds.filterTo(mutableSetOf()) { id -> detail.tracks.any { it.id == id } },
        )
    }

    // ---- Edit mode ------------------------------------------------------------------------

    fun setEditMode(enabled: Boolean) = _state.update {
        val on = enabled && it.canEdit
        it.copy(editMode = on, selectedIds = if (on) it.selectedIds else emptySet())
    }

    fun toggleSelection(track: PlaylistTrackUi) {
        if (track.id.isBlank()) return
        _state.update { s -> s.copy(selectedIds = if (track.id in s.selectedIds) s.selectedIds - track.id else s.selectedIds + track.id) }
    }

    fun clearSelection() = _state.update { it.copy(selectedIds = emptySet()) }

    /** While dragging: move a track one place locally; [saveOrder] when the drag ends. */
    fun moveTrack(track: PlaylistTrackUi, step: Int) = _state.update { s ->
        val from = s.tracks.indexOfFirst { it.id == track.id }
        val to = from + step
        if (from < 0 || to !in s.tracks.indices) s
        else s.copy(tracks = s.tracks.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun saveOrder() = reorder(_state.value.tracks)

    fun moveSelectedUp() = reorder(moveSelectedUp(_state.value.tracks, _state.value.selectedIds))

    fun moveSelectedDown() = reorder(moveSelectedDown(_state.value.tracks, _state.value.selectedIds))

    fun moveSelectedToTop() = reorder(moveSelectedToTop(_state.value.tracks, _state.value.selectedIds))

    private fun reorder(newOrder: List<PlaylistTrackUi>) {
        val before = _state.value.tracks
        when {
            !_state.value.canEdit -> UserMessages.show("This playlist order cannot be edited")
            newOrder.size != before.size || newOrder.any { it.id.isBlank() } -> UserMessages.show("Cannot reorder this playlist yet")
            else -> {
                _state.update { it.copy(tracks = newOrder) }
                viewModelScope.launch {
                    try {
                        apply(repository.reorderTracks(playlistId, newOrder.map { it.id }))
                        RefreshSignals.bumpPlaylists()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _state.update { it.copy(tracks = before) }
                        UserMessages.show(e.toUserMessage("Reorder"))
                    }
                }
            }
        }
    }

    // ---- Removing -------------------------------------------------------------------------

    fun removeTrack(track: PlaylistTrackUi) = edit("Remove", successMessage = "Removed") {
        repository.removeTrack(playlistId, track.id)
        _state.update { it.copy(selectedIds = it.selectedIds - track.id) }
    }

    fun removeSelected() {
        val toRemove = _state.value.selectedTracks
        if (toRemove.isEmpty()) return
        if (!_state.value.canEdit) {
            UserMessages.show("This playlist cannot be edited")
            return
        }
        edit("Remove", successMessage = "Removed ${toRemove.size} track${if (toRemove.size == 1) "" else "s"}") {
            toRemove.forEach { repository.removeTrack(playlistId, it.id) }
            _state.update { it.copy(selectedIds = emptySet()) }
        }
    }

    /** Remove tracks, then reload whatever happened (a bulk remove can stop partway). */
    private fun edit(action: String, successMessage: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                RefreshSignals.bumpPlaylists()
                UserMessages.show(successMessage)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UserMessages.show(e.toUserMessage(action))
            }
            refresh()
        }
    }

    fun deletePlaylist(onDeleted: () -> Unit) {
        if (!_state.value.canEdit) {
            UserMessages.show("This playlist cannot be deleted")
            return
        }
        viewModelScope.launchPlaybackAction(
            failureAction = "Delete",
            successMessage = "Playlist deleted",
            onSuccess = {
                RefreshSignals.bumpPlaylists()
                onDeleted()
            },
        ) { repository.delete(playlistId) }
    }
}

/** Each selected track that follows an unselected one moves up one place. */
internal fun moveSelectedUp(tracks: List<PlaylistTrackUi>, selected: Set<String>): List<PlaylistTrackUi> {
    val moved = tracks.toMutableList()
    for (i in 1 until moved.size) {
        if (moved[i].id in selected && moved[i - 1].id !in selected) moved[i - 1] = moved[i].also { moved[i] = moved[i - 1] }
    }
    return moved
}

/** Each selected track followed by an unselected one moves down one place. */
internal fun moveSelectedDown(tracks: List<PlaylistTrackUi>, selected: Set<String>): List<PlaylistTrackUi> {
    val moved = tracks.toMutableList()
    for (i in moved.lastIndex - 1 downTo 0) {
        if (moved[i].id in selected && moved[i + 1].id !in selected) moved[i + 1] = moved[i].also { moved[i] = moved[i + 1] }
    }
    return moved
}

/** Selected tracks first (keeping their order), then the rest. */
internal fun moveSelectedToTop(tracks: List<PlaylistTrackUi>, selected: Set<String>): List<PlaylistTrackUi> =
    tracks.filter { it.id in selected } + tracks.filterNot { it.id in selected }
