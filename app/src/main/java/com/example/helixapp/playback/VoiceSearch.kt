package com.example.helixapp.playback

import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import com.example.helixapp.HelixClient
import com.example.helixapp.HelixHttpException
import com.example.helixapp.HelixPrefs
import com.example.helixapp.SearchSong
import com.example.helixapp.helix.HelixTrackRequests
import com.example.helixapp.parseAlbums
import com.example.helixapp.parseArtists
import com.example.helixapp.parsePlaylists
import com.example.helixapp.parsePopularTracks
import com.example.helixapp.parseStations
import com.example.helixapp.parseSongs
import android.widget.Toast
import com.example.helixapp.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import retrofit2.Response

/**
 * "Play X on Helix" from Google Assistant / Gemini, Android Auto's voice button, or any app
 * sending Android's play-from-search request.
 *
 * [parse] turns the spoken phrase (plus whatever structure the assistant extracted) into a
 * [VoiceQuery]; [play] resolves that against the user's stations and playlists and Helix's
 * search, then starts it through [PlaybackActions].
 */
object VoiceSearch {

    /** What the assistant extracted from the phrase, from the request's extras. */
    data class Hints(
        val focus: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val title: String? = null,
        val playlist: String? = null,
    ) {
        companion object {
            fun from(extras: Bundle?): Hints = if (extras == null) Hints() else Hints(
                focus = extras.getString(MediaStore.EXTRA_MEDIA_FOCUS),
                artist = extras.getString(MediaStore.EXTRA_MEDIA_ARTIST),
                album = extras.getString(MediaStore.EXTRA_MEDIA_ALBUM),
                title = extras.getString(MediaStore.EXTRA_MEDIA_TITLE),
                playlist = extras.getString(EXTRA_MEDIA_PLAYLIST),
            )
        }
    }

    sealed interface VoiceQuery {
        /** "Play music on Helix": carry on with what's there. */
        data object Resume : VoiceQuery
        data class Radio(val seed: String) : VoiceQuery
        data class Playlist(val name: String) : VoiceQuery
        data class Album(val query: String) : VoiceQuery
        data class Artist(val name: String) : VoiceQuery
        data class Song(val query: String) : VoiceQuery
        /** No structure: try playlists, stations, an artist, then songs. */
        data class Anything(val query: String) : VoiceQuery
    }

    class NotFoundException(query: String) : Exception("Couldn't find \"$query\" on Helix")

    // MediaStore.EXTRA_MEDIA_PLAYLIST is API 30+ as a constant; the key itself is older.
    private const val EXTRA_MEDIA_PLAYLIST = "android.intent.extra.playlist"
    private const val FOCUS_ARTIST = "vnd.android.cursor.item/artist"
    private const val FOCUS_ALBUM = "vnd.android.cursor.item/album"
    private const val FOCUS_SONG = "vnd.android.cursor.item/audio"
    private const val FOCUS_PLAYLIST = "vnd.android.cursor.item/playlist"
    private const val ARTIST_TRACKS = 10
    private const val SERVICE_CONNECT_TIMEOUT_MS = 5_000L

    fun parse(query: String?, hints: Hints = Hints()): VoiceQuery {
        val q = query.orEmpty().trim()
        fun orQuery(vararg parts: String?): String =
            parts.filterNot { it.isNullOrBlank() }.joinToString(" ").ifBlank { q }

        when (hints.focus) {
            FOCUS_PLAYLIST -> return VoiceQuery.Playlist(stripPlaylistWords(hints.playlist ?: q))
            FOCUS_ALBUM -> return VoiceQuery.Album(orQuery(hints.album, hints.artist))
            FOCUS_ARTIST -> return VoiceQuery.Artist(hints.artist?.takeIf { it.isNotBlank() } ?: q)
            FOCUS_SONG -> return VoiceQuery.Song(orQuery(hints.title, hints.artist))
        }
        if (q.isBlank()) return VoiceQuery.Resume

        val n = normalize(q)
        RADIO_SUFFIX.find(n)?.let { return VoiceQuery.Radio(n.removeRange(it.range).trim()) }
        if (n.startsWith("my ") || n.endsWith(" playlist")) return VoiceQuery.Playlist(stripPlaylistWords(q))
        return VoiceQuery.Anything(BY.replace(q, " ").trim())
    }

    /**
     * The id of the entry whose name matches [spoken], ignoring case, punctuation and filler
     * words like "radio" or "playlist"; null if none does.
     */
    fun matchName(spoken: String, entries: List<Pair<String, String>>): String? {
        val want = core(spoken)
        if (want.isBlank()) return null
        return entries.firstOrNull { (_, name) -> core(name) == want }?.first
    }

    internal fun normalize(s: String): String =
        s.lowercase().replace(NON_WORD, " ").replace(SPACES, " ").trim()

    private fun core(s: String): String =
        normalize(s).split(" ").filterNot { it in FILLER }.joinToString(" ")

    private fun stripPlaylistWords(s: String): String =
        normalize(s).removePrefix("my ").removeSuffix(" playlist").trim()

    private val NON_WORD = Regex("[^\\p{L}\\p{N} ]")
    private val SPACES = Regex("\\s+")
    private val RADIO_SUFFIX = Regex("\\s(radio|station)$")
    private val BY = Regex("\\s+by\\s+", RegexOption.IGNORE_CASE)
    private val FILLER = setOf("the", "my", "radio", "station", "playlist", "mix")

    // ---- Playing ----------------------------------------------------------------------------

    // Outlives VoiceSearchActivity, which finishes as soon as it hands the request over.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * A play-from-search intent (VoiceSearchActivity); failures are shown as a toast. The
     * returned job completes once playback has started (or failed).
     */
    fun startFromIntent(ctx: Context, query: String?, extras: Bundle?): Job {
        val app = ctx.applicationContext
        val parsed = parse(query, Hints.from(extras))
        return appScope.launch {
            try {
                // Connect to PlaybackService first, as opening the app does. A service that
                // starts mid-request resets the "wait for Play" hold and pauses what we start.
                withTimeoutOrNull(SERVICE_CONNECT_TIMEOUT_MS) {
                    suspendCancellableCoroutine { done -> PlaybackController.get(app) { if (done.isActive) done.resume(Unit) } }
                }
                play(app, parsed, appScope)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("HELIX_PLAYER", "Voice search for $parsed failed: ${e.javaClass.simpleName}: ${e.message}", e)
                val message = (e as? NotFoundException)?.message ?: e.toUserMessage("Play")
                Toast.makeText(app, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Start what [query] asks for. Throws [NotFoundException] when nothing matches, or a
     * network error. [scope] runs follow-up work (queueing an artist's other songs) after
     * playback has started.
     */
    suspend fun play(ctx: Context, query: VoiceQuery, scope: CoroutineScope) {
        Log.i("HELIX_PLAYER", "Voice search: $query")
        when (query) {
            VoiceQuery.Resume -> resume(ctx)
            is VoiceQuery.Radio -> playRadio(ctx, query.seed)
            is VoiceQuery.Playlist -> playPlaylist(ctx, query.name) || playAnything(ctx, query.name, scope) || notFound(query.name)
            is VoiceQuery.Album -> playAlbum(ctx, query.query) || playSong(ctx, query.query) || notFound(query.query)
            is VoiceQuery.Artist -> playArtist(ctx, query.name, scope, exactOnly = false) || notFound(query.name)
            is VoiceQuery.Song -> playSong(ctx, query.query) || notFound(query.query)
            is VoiceQuery.Anything -> playAnything(ctx, query.query, scope) || notFound(query.query)
        }
    }

    private fun notFound(query: String): Boolean = throw NotFoundException(query)

    private suspend fun resume(ctx: Context) {
        PlayerStateStore.refresh(ctx)
        if (PlayerStateStore.state.value?.now != null) {
            PlayerCommandCoordinator.resume(ctx)
            return
        }
        // Nothing to resume: start the first playlist (Liked Songs on a default setup).
        val first = playlists(ctx).firstOrNull() ?: throw NotFoundException("music")
        PlaybackActions.playPlaylist(ctx, first.first, shuffle = false)
    }

    private suspend fun playAnything(ctx: Context, query: String, scope: CoroutineScope): Boolean {
        if (playPlaylist(ctx, query) || playStation(ctx, query)) return true
        val songs = searchSongs(ctx, query)
        // A song titled exactly what was said beats an artist of that name ("play Upgrade").
        songs.firstOrNull { normalize(it.title) == normalize(query) }?.let {
            PlaybackActions.playTrack(ctx, trackBody(ctx, it))
            return true
        }
        if (playArtist(ctx, query, scope, exactOnly = true)) return true
        val top = songs.firstOrNull() ?: return false
        PlaybackActions.playTrack(ctx, trackBody(ctx, top))
        return true
    }

    private suspend fun playPlaylist(ctx: Context, name: String): Boolean {
        val id = matchName(name, playlists(ctx)) ?: return false
        PlaybackActions.playPlaylist(ctx, id, shuffle = false)
        return true
    }

    private suspend fun playStation(ctx: Context, name: String): Boolean {
        val stations = stations(ctx)
        val id = matchName(name, stations) ?: return false
        PlaybackActions.playStation(ctx, id, stations.first { it.first == id }.second)
        return true
    }

    /** An existing station for [seed], or a new artist station like the Artist screen makes. */
    private suspend fun playRadio(ctx: Context, seed: String) {
        if (playStation(ctx, seed)) return
        val artist = searchArtists(ctx, seed).firstOrNull()?.name?.takeIf { it.isNotBlank() } ?: seed
        if (playStation(ctx, artist)) return
        val name = "$artist Radio"
        val body = JSONObject()
            .put("name", name)
            .put("seed_type", "artist")
            .put("seed_title", "")
            .put("seed_artist", artist)
            .put("discovery", 0.35)
            .put("seed_influence", 0.75)
        val created = call(ctx) { it.createStation(body.toString().toRequestBody(JSON)) }
        val id = runCatching { JSONObject(created).optString("id") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: matchName(name, stations(ctx))
            ?: throw NotFoundException(seed)
        Log.i("HELIX_PLAYER", "Voice search created station $name")
        PlaybackActions.playStation(ctx, id, name)
    }

    private suspend fun playAlbum(ctx: Context, query: String): Boolean {
        val album = parseAlbums(call(ctx) { it.ytmusicSearch(query, songLimit = 1, albumLimit = 1) })
            .firstOrNull { it.browseId.isNotBlank() } ?: return false
        PlaybackActions.playAlbum(ctx, HelixTrackRequests.playOrQueueBodyFromSearchAlbum(HelixPrefs.getBaseUrl(ctx), album))
        return true
    }

    /**
     * The artist's popular songs: the first plays now, the rest are queued behind it. With
     * [exactOnly], only when the top artist result is named exactly what was said (so "play
     * Dirty" doesn't pick an artist).
     */
    private suspend fun playArtist(ctx: Context, name: String, scope: CoroutineScope, exactOnly: Boolean): Boolean {
        val artist = searchArtists(ctx, name).firstOrNull { it.browseId.isNotBlank() } ?: return false
        if (exactOnly && normalize(artist.name) != normalize(name)) return false
        val tracks = parsePopularTracks(call(ctx) { it.artistPopular(artist.browseId, ARTIST_TRACKS) })
            .filter { it.videoId.isNotBlank() }
        val first = tracks.firstOrNull() ?: return false
        PlaybackActions.playTrack(ctx, trackBody(ctx, first))
        scope.launch {
            for (song in tracks.drop(1)) {
                runCatching { PlaybackActions.queueTrack(ctx, trackBody(ctx, song)) }
                    .onFailure { Log.w("HELIX_PLAYER", "Voice search: queueing ${song.title} failed", it) }
            }
        }
        return true
    }

    private suspend fun playSong(ctx: Context, query: String): Boolean {
        val song = searchSongs(ctx, query).firstOrNull() ?: return false
        PlaybackActions.playTrack(ctx, trackBody(ctx, song))
        return true
    }

    private suspend fun searchSongs(ctx: Context, query: String): List<SearchSong> =
        parseSongs(call(ctx) { it.ytmusicSearch(query, songLimit = 5, albumLimit = 1) })
            .filter { it.videoId.isNotBlank() || it.subsonicSongId.isNotBlank() }

    private fun trackBody(ctx: Context, song: SearchSong): JSONObject =
        HelixTrackRequests.playOrQueueBodyFromSearchSong(HelixPrefs.getBaseUrl(ctx), song)

    private suspend fun searchArtists(ctx: Context, query: String) =
        parseArtists(call(ctx) { it.ytmusicSearchArtists(query, artistLimit = 3) })

    /** (id to play, name) of the user's playlists. */
    private suspend fun playlists(ctx: Context): List<Pair<String, String>> =
        parsePlaylists(call(ctx) { it.listPlaylists() }).filter { it.playId.isNotBlank() }.map { it.playId to it.name }

    private suspend fun stations(ctx: Context): List<Pair<String, String>> =
        parseStations(call(ctx) { it.listStations() }).filter { it.id.isNotBlank() }.map { it.id to it.name }

    private suspend fun call(ctx: Context, request: suspend (com.example.helixapp.HelixApi) -> Response<String>): String {
        val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
        val resp = withContext(Dispatchers.IO) { request(api) }
        if (!resp.isSuccessful) throw HelixHttpException(resp.code())
        return resp.body().orEmpty()
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()
}
