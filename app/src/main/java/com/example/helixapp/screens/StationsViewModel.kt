package com.example.helixapp

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.helixapp.data.HelixStationRepository
import com.example.helixapp.data.StationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

data class StationsUiState(
    val stations: List<StationUi> = emptyList(),
    val providers: List<StationProviderUi> = emptyList(),
    val loading: Boolean = false,
    val signedIn: Boolean = true,
    val error: String? = null,
) {
    fun providerFor(station: StationUi) = providers.firstOrNull { it.stationType == station.stationType }
}

/** The Library tab's stations: list, create, tune (edit) and delete. */
class StationsViewModel(
    private val repository: StationRepository,
    private val isSignedIn: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    constructor(app: Application) : this(HelixStationRepository(app), { !HelixPrefs.getSessionToken(app).isNullOrBlank() })

    private val _state = MutableStateFlow(StationsUiState())
    val state: StateFlow<StationsUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var lastRefreshMs = 0L

    init {
        refresh()
    }

    fun refresh() {
        lastRefreshMs = clock()
        if (!isSignedIn()) {
            _state.update { it.copy(stations = emptyList(), providers = emptyList(), signedIn = false, loading = false) }
            return
        }
        loadJob?.cancel()
        _state.update { it.copy(loading = true, signedIn = true) }
        loadJob = viewModelScope.launch {
            // Station types only label rows and drive the editor; the list still shows without them.
            runCatching { repository.providers() }.onSuccess { providers -> _state.update { it.copy(providers = providers) } }
            try {
                val stations = repository.list()
                _state.update { it.copy(stations = stations, loading = false, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.toUserMessage("Loading stations")) }
            }
        }
    }

    fun refreshIfStale() {
        if (clock() - lastRefreshMs > STALE_MS && !_state.value.loading) refresh()
    }

    /** Save a tuned station; [config] is the edited config object. */
    fun save(updated: StationUi, config: JSONObject) {
        _state.update { s -> s.copy(stations = s.stations.map { if (it.id == updated.id) updated else it }) }
        val payload = JSONObject().put("name", updated.name).put("config", config)
        addLegacyStationMirrors(payload, config)
        change("Save", "Saved") { repository.update(updated.id, payload) }
    }

    fun delete(station: StationUi) {
        _state.update { s -> s.copy(stations = s.stations.filterNot { it.id == station.id }) }
        change("Delete", "Station deleted") { repository.delete(station.id) }
    }

    fun create(payload: JSONObject) = change("Create station", "Station created") { repository.create(payload) }

    /** Apply a change, say how it went, and reload the list either way. */
    private fun change(action: String, success: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                UserMessages.show(success)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UserMessages.show(e.toUserMessage(action))
            }
            refresh()
        }
    }

    private companion object {
        const val STALE_MS = 30_000L
    }
}
