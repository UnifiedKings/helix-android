package com.example.helixapp

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.helixapp.data.HelixLibraryRepository
import com.example.helixapp.data.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class HistoryFilter(val label: String, val event: String?) {
    All("All", null),
    Played("Played", "completed"),
    Skipped("Skipped", "skipped"),
}

data class HistoryUiState(
    val filter: HistoryFilter = HistoryFilter.All,
    val items: List<HistoryItemUi> = emptyList(),
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

/** The Library tab's listening history: filtered, paged, refreshed when it gets stale. */
class HistoryViewModel(
    private val repository: LibraryRepository,
    private val isSignedIn: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    constructor(app: Application) : this(
        HelixLibraryRepository(app),
        { !HelixPrefs.getSessionToken(app).isNullOrBlank() },
    )

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var lastRefreshMs = 0L

    init {
        load()
    }

    fun setFilter(filter: HistoryFilter) {
        if (filter == _state.value.filter) return
        _state.update { it.copy(filter = filter, items = emptyList(), hasMore = false) }
        load()
    }

    fun loadMore() {
        if (!_state.value.loading) load(append = true)
    }

    /** Reload when the list is older than [STALE_MS] (more has probably been played since). */
    fun refreshIfStale() {
        if (clock() - lastRefreshMs > STALE_MS && !_state.value.loading) load()
    }

    /** Load the first page (replacing the list), or the next page when [append]. */
    private fun load(append: Boolean = false) {
        if (!isSignedIn()) {
            _state.update { it.copy(items = emptyList(), error = "Log in from Settings to see your listening history.") }
            return
        }
        val filter = _state.value.filter
        val offset = if (append) _state.value.items.size else 0
        if (!append) lastRefreshMs = clock()
        loadJob?.cancel()
        _state.update { it.copy(loading = true) }
        loadJob = viewModelScope.launch {
            try {
                val page = repository.history(filter.event, offset, PAGE_SIZE)
                _state.update {
                    it.copy(
                        items = if (append) it.items + page.items else page.items,
                        hasMore = page.hasMore,
                        error = null,
                        loading = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toUserMessage("Loading history"), loading = false) }
            }
        }
    }

    companion object {
        const val PAGE_SIZE = 50
        private const val STALE_MS = 30_000L
    }
}
