package com.example.helixapp

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.helixapp.data.HelixLibraryRepository
import com.example.helixapp.data.HelixStationRepository
import com.example.helixapp.data.HelixSubsonicRepository
import com.example.helixapp.data.LibraryRepository
import com.example.helixapp.data.StationRepository
import com.example.helixapp.data.SubsonicRepository
import com.example.helixapp.data.SubsonicTrackRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SimilarState { Idle, Loading, Ready, Empty }

data class ArtistUiState(
    val artist: ArtistDetailUi,
    val loading: Boolean = true,
    /** A short note under the header ("Artist not found", an error…); "" for none. */
    val status: String = "",
    val popular: List<SearchSong> = emptyList(),
    val albums: List<SearchAlbum> = emptyList(),
    val similar: List<SimilarArtistUi> = emptyList(),
    val similarState: SimilarState = SimilarState.Idle,
)

/**
 * An artist page: details, popular songs and albums, then similar artists, which the server
 * may still be resolving (via MusicBrainz) when the page opens, so they're polled for a while.
 */
class ArtistViewModel(
    private val browseId: String,
    private val library: LibraryRepository,
    private val stations: StationRepository,
    private val subsonic: SubsonicRepository,
) : ViewModel() {

    constructor(app: Application, browseId: String) : this(
        browseId,
        HelixLibraryRepository(app),
        HelixStationRepository(app),
        HelixSubsonicRepository(app),
    )

    private val _state = MutableStateFlow(
        ArtistUiState(ArtistDetailUi(browseId, name = "", thumbnailUrl = "", mbArtistId = "", resolutionStatus = "unresolved"))
    )
    val state: StateFlow<ArtistUiState> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        if (browseId.isBlank()) {
            _state.update { it.copy(loading = false, status = "Artist is missing a browse id") }
            return
        }
        viewModelScope.launch {
            try {
                var artist = library.artist(browseId)
                _state.update { it.copy(artist = artist) }

                // The detail endpoint's image is often missing or small; search has a better one.
                val searchThumb = runCatching { library.searchArtists(artist.name.ifBlank { browseId }) }
                    .getOrDefault(emptyList())
                    .firstOrNull { it.browseId == browseId || it.name.equals(artist.name, ignoreCase = true) }
                    ?.thumbnailUrl.orEmpty()
                if (searchThumb.isNotBlank()) {
                    artist = artist.copy(thumbnailUrl = searchThumb)
                    _state.update { it.copy(artist = artist) }
                }

                val popular = async { runCatching { library.artistPopular(browseId) }.getOrDefault(emptyList()) }
                val albums = async { runCatching { library.artistAlbums(browseId) }.getOrDefault(emptyList()) }
                _state.update { it.copy(popular = popular.await(), albums = albums.await(), similarState = SimilarState.Loading) }

                pollSimilar()
                if (artist.name.isBlank()) _state.update { it.copy(status = "Artist not found") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(status = e.toUserMessage("Loading artist")) }
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    private suspend fun pollSimilar() {
        repeat(SIMILAR_ATTEMPTS) { attempt ->
            val result = runCatching { library.similarArtists(browseId) }.getOrNull()
            if (result != null && result.artists.isNotEmpty()) {
                _state.update { it.copy(similar = result.artists, similarState = SimilarState.Ready) }
                return
            }
            val next = when (result?.resolutionStatus) {
                null, "failed", "ambiguous", "resolved" -> SimilarState.Empty
                else -> SimilarState.Loading // "resolving", "unresolved", or unknown
            }
            _state.update { it.copy(similarState = next) }
            if (attempt < SIMILAR_ATTEMPTS - 1) delay(SIMILAR_POLL_MS)
        }
        if (_state.value.similarState == SimilarState.Loading) {
            _state.update { it.copy(status = "Similar artists are still loading") }
        }
    }

    fun createStation() {
        val name = _state.value.artist.name.ifBlank { return }
        viewModelScope.launchPlaybackAction(
            failureAction = "Create station",
            successMessage = "Created station: $name Radio",
        ) { stations.createArtistStation(name) }
    }

    fun addToSubsonic(song: SearchSong, artUrl: String) {
        viewModelScope.launchPlaybackAction(
            failureAction = "Add to Subsonic",
            successMessage = "Added to Subsonic: ${song.title}",
        ) {
            subsonic.addTrack(SubsonicTrackRequest(song.videoId, song.title, song.artist, album = song.album, artUrl = artUrl))
        }
    }

    companion object {
        const val SIMILAR_ATTEMPTS = 15
        const val SIMILAR_POLL_MS = 1_500L
    }
}
