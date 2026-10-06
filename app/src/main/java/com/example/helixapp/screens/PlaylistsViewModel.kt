package com.example.helixapp

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.helixapp.data.HelixPlaylistRepository
import com.example.helixapp.data.PlaylistRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlaylistsUiState(
    val playlists: List<PlaylistUi> = emptyList(),
    val loading: Boolean = false,
    val signedIn: Boolean = true,
    val error: String? = null,
)

/** The Library tab's playlist list, with create and delete. */
class PlaylistsViewModel(
    private val repository: PlaylistRepository,
    private val isSignedIn: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    constructor(app: Application) : this(
        HelixPlaylistRepository(app),
        { !HelixPrefs.getSessionToken(app).isNullOrBlank() },
    )

    private val _state = MutableStateFlow(PlaylistsUiState())
    val state: StateFlow<PlaylistsUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var lastRefreshMs = 0L

    init {
        refresh()
        // Another screen changed playlists (e.g. created one or added a song).
        viewModelScope.launch { RefreshSignals.playlists.drop(1).collect { refresh() } }
    }

    fun refresh() {
        lastRefreshMs = clock()
        if (!isSignedIn()) {
            _state.update { it.copy(playlists = emptyList(), signedIn = false, loading = false) }
            return
        }
        loadJob?.cancel()
        _state.update { it.copy(loading = true, signedIn = true) }
        loadJob = viewModelScope.launch {
            try {
                val playlists = repository.list()
                _state.update { it.copy(playlists = playlists, loading = false, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.toUserMessage("Loading playlists")) }
            }
        }
    }

    fun refreshIfStale() {
        if (clock() - lastRefreshMs > STALE_MS && !_state.value.loading) refresh()
    }

    /** Create a playlist; [onCreated] runs (e.g. to close the dialog) once it exists. */
    fun create(name: String, onCreated: () -> Unit) {
        viewModelScope.launchPlaybackAction(
            failureAction = "Create playlist",
            successMessage = "Created playlist: $name",
            onSuccess = {
                onCreated()
                RefreshSignals.bumpPlaylists()
            },
        ) { repository.create(name) }
    }

    fun delete(playlist: PlaylistUi) {
        viewModelScope.launchPlaybackAction(
            failureAction = "Delete",
            successMessage = "Playlist deleted",
            onSuccess = {
                _state.update { s -> s.copy(playlists = s.playlists.filterNot { it.id == playlist.id }) }
                RefreshSignals.bumpPlaylists()
            },
        ) { repository.delete(playlist.id) }
    }

    private companion object {
        const val STALE_MS = 30_000L
    }
}
