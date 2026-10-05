package com.example.helixapp

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.helixapp.data.HelixLibraryRepository
import com.example.helixapp.data.HelixSubsonicRepository
import com.example.helixapp.data.LibraryRepository
import com.example.helixapp.data.SubsonicLookup
import com.example.helixapp.data.SubsonicRepository
import com.example.helixapp.data.SubsonicTrackRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class SearchFilter(val label: String) {
    Songs("Songs"),
    Artists("Artists"),
    Albums("Albums"),
    All("All"),
}

data class SearchUiState(
    val query: String = "",
    val filter: SearchFilter = SearchFilter.Songs,
    val loading: Boolean = false,
    /** "No results", an error, or a sign-in hint; null when there's nothing to say. */
    val message: String? = null,
    val songs: List<SearchSong> = emptyList(),
    val albums: List<SearchAlbum> = emptyList(),
    val artists: List<SearchArtist> = emptyList(),
    val recents: List<RecentSearchPlay.Item> = emptyList(),
    /** Subsonic availability by "song:<videoId>" / "album:<browseId>". */
    val inSubsonic: Map<String, Boolean> = emptyMap(),
) {
    fun songInSubsonic(song: SearchSong) = inSubsonic["song:" + song.videoId] == true
    fun albumInSubsonic(album: SearchAlbum) = inSubsonic["album:" + album.browseId] == true

    /** Album artist by lowercased album title, for songs whose own artist field is junk. */
    val albumArtistByTitle: Map<String, String>
        get() = albums.filter { it.title.isNotBlank() && it.artist.isNotBlank() }
            .associate { it.title.trim().lowercase() to it.artist.trim() }
}

/** Where recently played search results are kept (RecentSearchPlay on the device). */
interface RecentsStore {
    fun get(): List<RecentSearchPlay.Item>
    fun clear()
}

/**
 * The Search tab: debounced search across songs, albums and artists, "In Subsonic" badges, and
 * recently played results while the query is empty.
 */
@OptIn(FlowPreview::class)
class SearchViewModel(
    private val library: LibraryRepository,
    private val subsonic: SubsonicRepository,
    private val recentsStore: RecentsStore,
    private val isSignedIn: () -> Boolean,
) : ViewModel() {

    constructor(app: Application) : this(
        HelixLibraryRepository(app),
        HelixSubsonicRepository(app),
        object : RecentsStore {
            override fun get() = RecentSearchPlay.get(app)
            override fun clear() = RecentSearchPlay.clear(app)
        },
        { !HelixPrefs.getSessionToken(app).isNullOrBlank() },
    )

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.map { it.query.trim() }
                .distinctUntilChanged()
                .debounce(DEBOUNCE_MS)
                .collectLatest { q -> if (q.isBlank()) showRecents() else search(q) }
        }
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun setFilter(filter: SearchFilter) = _state.update { it.copy(filter = filter) }

    /** Recents can change while the screen is away (something was played): reload them. */
    fun refreshRecents() {
        if (_state.value.query.isBlank()) viewModelScope.launch { showRecents() }
    }

    fun clearRecents() {
        recentsStore.clear()
        _state.update { it.copy(recents = emptyList()) }
    }

    private suspend fun showRecents() {
        val recents = recentsStore.get()
        _state.update {
            it.copy(loading = false, message = null, songs = emptyList(), albums = emptyList(), artists = emptyList(),
                recents = recents, inSubsonic = emptyMap())
        }
        resolveSubsonic(
            songs = recents.filter { it.kind == RecentSearchPlay.Kind.SONG }.map { it.toSearchSong() },
            albums = recents.filter { it.kind == RecentSearchPlay.Kind.ALBUM }.map { it.toSearchAlbum() },
        )
    }

    /** Runs inside collectLatest: a newer query cancels this one, Subsonic lookup included. */
    private suspend fun search(q: String) {
        if (!isSignedIn()) {
            _state.update { it.copy(message = "Not logged in — go to Settings") }
            return
        }
        _state.update {
            it.copy(loading = true, message = null, songs = emptyList(), albums = emptyList(), artists = emptyList(), inSubsonic = emptyMap())
        }
        try {
            val (results, artists) = coroutineScope {
                val results = async { library.search(q) }
                val artists = async { library.searchArtists(q) }
                results.await() to artists.await()
            }
            val empty = results.songs.isEmpty() && results.albums.isEmpty() && artists.isEmpty()
            _state.update {
                it.copy(loading = false, songs = results.songs, albums = results.albums, artists = artists,
                    message = if (empty) "No results" else null)
            }
            resolveSubsonic(results.songs, results.albums)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, message = e.toUserMessage("Search")) }
        }
    }

    /** Best effort: badges just don't show if this fails. */
    private suspend fun resolveSubsonic(songs: List<SearchSong>, albums: List<SearchAlbum>) {
        if (!isSignedIn() || (songs.isEmpty() && albums.isEmpty())) return
        val available = runCatching {
            subsonic.resolve(
                songs = songs.take(RESOLVE_LIMIT).map { SubsonicLookup("song:" + it.videoId, it.title, it.artist) },
                albums = albums.take(RESOLVE_LIMIT).map { SubsonicLookup("album:" + it.browseId, it.title, it.artist) },
            )
        }.getOrNull() ?: return
        _state.update { it.copy(inSubsonic = available) }
    }

    fun addSongToSubsonic(song: SearchSong, artUrl: String) {
        val albumArtist = _state.value.albumArtistByTitle[song.album.trim().lowercase()] ?: song.artist
        // YouTube Music sometimes puts "View"/"Play" labels in a song's artist field.
        val trackArtist = song.artist.trim().let { artist ->
            val lower = artist.lowercase()
            if (artist.isBlank() || lower.contains("view") || lower.contains("play")) albumArtist else artist
        }
        viewModelScope.launchPlaybackAction(failureAction = "Add to Subsonic", successMessage = "Added to Subsonic: ${song.title}") {
            subsonic.addTrack(SubsonicTrackRequest(song.videoId, song.title, trackArtist, albumArtist, song.album, artUrl))
        }
    }

    fun addAlbumToSubsonic(album: SearchAlbum, request: JSONObject) {
        viewModelScope.launchPlaybackAction(failureAction = "Add to Subsonic", successMessage = "Added to Subsonic: ${album.title}") {
            subsonic.addAlbum(request)
        }
    }

    companion object {
        const val DEBOUNCE_MS = 400L
        private const val RESOLVE_LIMIT = 25
    }
}
