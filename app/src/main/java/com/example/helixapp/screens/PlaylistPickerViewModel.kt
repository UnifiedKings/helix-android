package com.example.helixapp

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.helixapp.data.HelixLibraryRepository
import com.example.helixapp.data.HelixPlaylistRepository
import com.example.helixapp.data.LibraryRepository
import com.example.helixapp.data.PlaylistRepository
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

enum class PlaylistPickerTab(val label: String) {
    Songs("Songs"),
    Artists("Artists"),
    Albums("Albums"),
}

data class PlaylistPickerUiState(
    val query: String = "",
    val tab: PlaylistPickerTab = PlaylistPickerTab.Songs,
    val loading: Boolean = false,
    /** "No results" or an error; "" for nothing. */
    val status: String = "",
    val songs: List<SearchSong> = emptyList(),
    val artists: List<SearchArtist> = emptyList(),
    val albums: List<SearchAlbum> = emptyList(),
    /** Set while showing one album's or artist's songs. */
    val drillTitle: String? = null,
    val drillSongs: List<SearchSong> = emptyList(),
    /** Chosen songs by [songKey], in the order they were picked. */
    val selected: Map<String, SearchSong> = emptyMap(),
    val addedKeys: Set<String> = emptySet(),
    val adding: Boolean = false,
) {
    val displayedSongs: List<SearchSong> get() = if (drillTitle != null) drillSongs else songs
}

/** A song's identity in the picker: Subsonic id, YouTube id, or title/artist/album. */
fun songKey(song: SearchSong): String {
    val stable = song.subsonicSongId.trim().ifBlank { song.videoId.trim() }
    return stable.ifBlank { "${song.title.trim().lowercase()}|${song.artist.trim().lowercase()}|${song.album.trim().lowercase()}" }
}

/** Playlist → Add songs: search, drill into an album or artist, pick several, add them. */
@OptIn(FlowPreview::class)
class PlaylistPickerViewModel(
    private val playlistId: String,
    private val library: LibraryRepository,
    private val playlists: PlaylistRepository,
) : ViewModel() {

    constructor(app: Application, playlistId: String) : this(playlistId, HelixLibraryRepository(app), HelixPlaylistRepository(app))

    private val _state = MutableStateFlow(PlaylistPickerUiState())
    val state: StateFlow<PlaylistPickerUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.map { it.query.trim() }.distinctUntilChanged().debounce(DEBOUNCE_MS).collectLatest(::search)
        }
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun setTab(tab: PlaylistPickerTab) = _state.update { it.copy(tab = tab) }

    private suspend fun search(term: String) {
        if (term.isBlank()) {
            _state.update { it.copy(songs = emptyList(), artists = emptyList(), albums = emptyList(), status = "", loading = false) }
            return
        }
        _state.update { it.copy(loading = true, status = "", drillTitle = null, drillSongs = emptyList()) }
        try {
            val (results, artists) = coroutineScope {
                val results = async { library.search(term) }
                // Artists are optional: songs and albums still show if this fails.
                val artists = async { runCatching { library.searchArtists(term) }.getOrDefault(emptyList()) }
                results.await() to artists.await()
            }
            val albums = results.albums.filter { it.browseId.isNotBlank() }
            val withIds = artists.filter { it.browseId.isNotBlank() }
            _state.update {
                it.copy(
                    loading = false,
                    songs = results.songs,
                    albums = albums,
                    artists = withIds,
                    status = if (results.songs.isEmpty() && albums.isEmpty() && withIds.isEmpty()) "No results" else "",
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, status = e.toUserMessage("Search")) }
        }
    }

    fun openAlbum(album: SearchAlbum) = drill(album.title, "Album") {
        val view = library.album(album.browseId)
        val artist = view.artist.ifBlank { album.artist }
        val art = view.thumbnailUrl.ifBlank { album.thumbnailUrl }
        val title = view.title.ifBlank { album.title }
        title to view.tracks.map { track ->
            SearchSong(title = track.title, artist = track.artist.ifBlank { artist }, album = title, thumbnailUrl = art, videoId = track.videoId)
        }
    }

    fun openArtist(artist: SearchArtist) = drill(artist.name, "Artist") {
        artist.name to library.artistPopular(artist.browseId, limit = ARTIST_SONGS).map { song ->
            song.copy(
                artist = song.artist.ifBlank { artist.name },
                thumbnailUrl = song.thumbnailUrl.ifBlank { artist.thumbnailUrl },
            )
        }
    }

    private fun drill(fallbackTitle: String, action: String, load: suspend () -> Pair<String, List<SearchSong>>) {
        _state.update { it.copy(loading = true, status = "") }
        viewModelScope.launch {
            try {
                val (title, songs) = load()
                _state.update { it.copy(loading = false, drillTitle = title.ifBlank { fallbackTitle }, drillSongs = songs) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, status = e.toUserMessage(action)) }
            }
        }
    }

    fun closeDrill() = _state.update { it.copy(drillTitle = null, drillSongs = emptyList()) }

    fun toggle(song: SearchSong) {
        val key = songKey(song)
        _state.update { s -> s.copy(selected = if (key in s.selected) s.selected - key else s.selected + (key to song)) }
    }

    /** Add the chosen songs one by one; stop at the first failure and say how far it got. */
    fun addSelected() {
        val chosen = _state.value.selected
        if (chosen.isEmpty() || _state.value.adding) return
        _state.update { it.copy(adding = true) }
        viewModelScope.launch {
            var added = 0
            try {
                for ((key, song) in chosen) {
                    playlists.addTrack(playlistId, song)
                    added++
                    _state.update { it.copy(addedKeys = it.addedKeys + key, selected = it.selected - key) }
                }
                UserMessages.show("Added $added song${if (added == 1) "" else "s"}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UserMessages.show(if (added > 0) "Added $added, then: ${e.toUserMessage("Add")}" else e.toUserMessage("Add"))
            } finally {
                _state.update { it.copy(adding = false) }
            }
        }
    }

    companion object {
        const val DEBOUNCE_MS = 350L
        private const val ARTIST_SONGS = 50
    }
}
