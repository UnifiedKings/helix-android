package com.example.helixapp

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.helixapp.data.HelixRatingRepository
import com.example.helixapp.data.HelixSubsonicRepository
import com.example.helixapp.data.RatedTrack
import com.example.helixapp.data.RatingRepository
import com.example.helixapp.data.SubsonicLookup
import com.example.helixapp.data.SubsonicRepository
import com.example.helixapp.data.SubsonicTrackRequest
import com.example.helixapp.playback.NowPlayingUi
import com.example.helixapp.playback.PlayerStateSnapshot
import com.example.helixapp.playback.PlayerStateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NowPlayingUiState(
    val now: NowPlayingUi? = null,
    val activeStationName: String? = null,
    /** The backend's play state (the screen prefers Media3's when this phone plays audio). */
    val backendPlaying: Boolean = false,
    val loading: Boolean = false,
    /** A sign-in hint or a loading error; null otherwise. */
    val error: String? = null,
    val liked: Boolean = false,
    val disliked: Boolean = false,
    val ratingInFlight: Boolean = false,
    /** Null until the current song's Subsonic status is known. */
    val inSubsonic: Boolean? = null,
    val addToSubsonicPending: Boolean = false,
)

/**
 * The Now Playing screen's backend side: the current song from [PlayerStateStore], its
 * like/dislike state, and whether it's in Subsonic (polling after an import until it shows up).
 * Playback position and play/pause stay with the screen's MediaController.
 */
class NowPlayingViewModel(
    private val playerState: StateFlow<PlayerStateSnapshot?>,
    private val refreshState: suspend () -> Unit,
    private val ratings: RatingRepository,
    private val subsonic: SubsonicRepository,
    private val isSignedIn: () -> Boolean,
) : ViewModel() {

    constructor(app: Application) : this(
        PlayerStateStore.state,
        { PlayerStateStore.refresh(app) },
        HelixRatingRepository(app),
        HelixSubsonicRepository(app),
        { !HelixPrefs.getSessionToken(app).isNullOrBlank() },
    )

    private val _state = MutableStateFlow(NowPlayingUiState(now = playerState.value?.now))
    val state: StateFlow<NowPlayingUiState> = _state.asStateFlow()

    private var songJob: Job? = null
    private var pollJob: Job? = null

    init {
        refresh()
        viewModelScope.launch {
            playerState.collect { ps ->
                if (ps != null) {
                    _state.update { it.copy(now = ps.now, activeStationName = ps.activeStationName, backendPlaying = ps.isPlaying) }
                }
            }
        }
        // A different song: look up its ratings and Subsonic status again.
        viewModelScope.launch {
            playerState.map { it?.now }.distinctUntilChangedBy { it?.identity() }.collect { onSongChanged(it) }
        }
    }

    fun refresh() {
        if (!isSignedIn()) {
            _state.update { it.copy(now = null, activeStationName = null, error = "Not logged in — go to Login") }
            return
        }
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                refreshState()
                _state.update { it.copy(loading = false, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.toUserMessage("Loading the player")) }
            }
        }
    }

    private fun onSongChanged(now: NowPlayingUi?) {
        songJob?.cancel()
        pollJob?.cancel()
        _state.update { it.copy(liked = false, disliked = false, inSubsonic = null, addToSubsonicPending = false) }
        if (now == null) return
        // Both lookups are cancelled if the song changes again before they finish.
        songJob = viewModelScope.launch {
            launch {
                val inSubsonic = runCatching { resolveInSubsonic(now) }.getOrDefault(false)
                _state.update { it.copy(inSubsonic = inSubsonic) }
            }
            val track = now.toRatedTrack()
            if (track.hasId || (track.title.isNotBlank() && track.artist.isNotBlank())) {
                val liked = runCatching { ratings.isLiked(track) }.getOrDefault(false)
                val disliked = runCatching { ratings.isDisliked(track) }.getOrDefault(false)
                _state.update { it.copy(liked = liked, disliked = disliked) }
            }
        }
    }

    /** Songs played from Subsonic are in it by definition; others are looked up. */
    private suspend fun resolveInSubsonic(now: NowPlayingUi): Boolean {
        if (!now.subsonicSongId.isNullOrBlank() || now.source.equals("subsonic", ignoreCase = true)) return true
        val title = now.title.trim()
        val artist = now.artist.trim()
        if (title.isBlank() || artist.isBlank()) return false
        val key = subsonicKey(now)
        val lookup = SubsonicLookup(key, title, artist, now.album.trim(), now.durationMs, now.ytVideoId?.trim().orEmpty())
        return subsonic.resolve(listOf(lookup))[key] == true
    }

    fun addToSubsonic() {
        val now = _state.value.now ?: return
        if (_state.value.inSubsonic == true || _state.value.addToSubsonicPending) return
        val title = now.title.trim()
        val artist = now.artist.trim()
        if (title.isBlank() || artist.isBlank()) return
        _state.update { it.copy(addToSubsonicPending = true) }
        viewModelScope.launch {
            try {
                subsonic.addTrack(SubsonicTrackRequest(now.ytVideoId?.trim().orEmpty(), title, artist, album = now.album, artUrl = now.artUrl))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(addToSubsonicPending = false) }
                UserMessages.show(e.toUserMessage("Add to Subsonic"))
                return@launch
            }
            pollUntilImported(now)
        }
    }

    /**
     * The import runs on the server; poll until the song shows up, but give up after a while
     * (a failed server-side import would otherwise keep this polling forever).
     */
    private fun pollUntilImported(now: NowPlayingUi) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            repeat(SUBSONIC_POLL_ATTEMPTS) {
                delay(SUBSONIC_POLL_MS)
                if (runCatching { resolveInSubsonic(now) }.getOrDefault(false)) {
                    _state.update { it.copy(inSubsonic = true, addToSubsonicPending = false) }
                    refresh()
                    return@launch
                }
            }
            _state.update { it.copy(addToSubsonicPending = false) }
            UserMessages.show("Still not in Subsonic after 2 minutes. The import may have failed; try adding it again.")
        }
    }

    /** [track] carries what the screen shows (Media3's metadata when it matches the backend). */
    fun toggleLike(track: RatedTrack) = rate(track, like = true)

    fun toggleDislike(track: RatedTrack) = rate(track, like = false)

    private fun rate(track: RatedTrack, like: Boolean) {
        if (!track.hasId || _state.value.ratingInFlight) return
        val before = _state.value
        // Optimistic: liking clears a dislike and vice versa; roll back if the server refuses.
        _state.update {
            if (like) it.copy(liked = !before.liked, disliked = if (!before.liked) false else before.disliked, ratingInFlight = true)
            else it.copy(disliked = !before.disliked, liked = if (!before.disliked) false else before.liked, ratingInFlight = true)
        }
        viewModelScope.launch {
            try {
                if (like) ratings.toggleLike(track) else ratings.toggleDislike(track)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(liked = before.liked, disliked = before.disliked) }
            } finally {
                _state.update { it.copy(ratingInFlight = false) }
            }
        }
    }

    companion object {
        const val SUBSONIC_POLL_ATTEMPTS = 60
        const val SUBSONIC_POLL_MS = 2_000L

        private fun NowPlayingUi.identity() = listOf(queueItemId, title, artist, subsonicSongId.orEmpty(), ytVideoId.orEmpty())

        /** "song:<ytId>", or a normalized text key for songs without a YouTube id. */
        fun subsonicKey(now: NowPlayingUi): String {
            val ytId = now.ytVideoId?.trim().orEmpty()
            if (ytId.isNotBlank()) return "song:$ytId"
            fun norm(v: String) = v.trim().lowercase().replace(Regex("\\s+"), " ")
            return "song:text:${norm(now.title)}|${norm(now.artist)}|${norm(now.album)}|${now.durationMs}"
        }

        fun NowPlayingUi.toRatedTrack() = RatedTrack(
            title = title.trim(), artist = artist.trim(), album = album.trim(), durationMs = durationMs,
            artUrl = artUrl.trim(), source = source.trim(),
            ytVideoId = ytVideoId?.takeIf { it.isNotBlank() }, subsonicSongId = subsonicSongId?.takeIf { it.isNotBlank() },
        )
    }
}
