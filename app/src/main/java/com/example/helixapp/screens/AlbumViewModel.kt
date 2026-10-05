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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AlbumUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val album: AlbumView? = null,
    /** Subsonic availability by "song:<videoId>". */
    val inSubsonic: Map<String, Boolean> = emptyMap(),
) {
    val tracks: List<AlbumTrack> get() = album?.tracks.orEmpty()

    fun trackInSubsonic(track: AlbumTrack): Boolean = inSubsonic[subsonicKey(track)] == true

    /** Every track that can be identified is already in Subsonic. */
    val fullyInSubsonic: Boolean
        get() = tracks.filter { it.videoId.isNotBlank() }.let { ids -> ids.isNotEmpty() && ids.all(::trackInSubsonic) }

    companion object {
        fun subsonicKey(track: AlbumTrack) = "song:" + track.videoId.trim()
    }
}

/** An album page: its tracks, and which of them are already in Subsonic. */
class AlbumViewModel(
    private val browseId: String,
    private val library: LibraryRepository,
    private val subsonic: SubsonicRepository,
    private val isSignedIn: () -> Boolean,
) : ViewModel() {

    constructor(app: Application, browseId: String) : this(
        browseId,
        HelixLibraryRepository(app),
        HelixSubsonicRepository(app),
        { !HelixPrefs.getSessionToken(app).isNullOrBlank() },
    )

    private val _state = MutableStateFlow(AlbumUiState())
    val state: StateFlow<AlbumUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        if (browseId.isBlank()) {
            _state.update { it.copy(loading = false, error = "Missing album id") }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val album = library.album(browseId)
                _state.update { it.copy(loading = false, album = album, inSubsonic = emptyMap()) }
                resolveSubsonic(album)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.toUserMessage("Loading album")) }
            }
        }
    }

    /** Best effort: without it, the "In Subsonic" badges just don't show. */
    private suspend fun resolveSubsonic(album: AlbumView) {
        if (!isSignedIn()) return
        val lookups = album.tracks.filter { it.videoId.isNotBlank() }.map { track ->
            SubsonicLookup(
                key = AlbumUiState.subsonicKey(track),
                title = track.title,
                artist = track.artist.trim().ifBlank { album.artist.trim() },
                album = album.title,
            )
        }
        val available = runCatching { subsonic.resolve(lookups) }.getOrNull() ?: return
        _state.update { it.copy(inSubsonic = available) }
    }

    fun addToSubsonic(track: AlbumTrack, artUrl: String) {
        val album = _state.value.album ?: return
        val trackArtist = track.artist.trim()
        val albumArtist = album.artist.trim().ifBlank { trackArtist }
        when {
            trackArtist.isBlank() && albumArtist.isBlank() -> UserMessages.show("Missing artist metadata for ${track.title}")
            track.videoId.isBlank() -> UserMessages.show("Missing video id for ${track.title}")
            else -> viewModelScope.launchPlaybackAction(
                failureAction = "Add to Subsonic",
                successMessage = "Added to Subsonic: ${track.title}",
            ) {
                subsonic.addTrack(
                    SubsonicTrackRequest(
                        ytVideoId = track.videoId,
                        title = track.title,
                        artist = trackArtist.ifBlank { albumArtist },
                        albumArtist = albumArtist,
                        album = album.title,
                        artUrl = artUrl,
                    )
                )
            }
        }
    }
}
