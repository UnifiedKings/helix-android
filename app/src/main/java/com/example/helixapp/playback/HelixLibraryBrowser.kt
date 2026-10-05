package com.example.helixapp.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.example.helixapp.HelixClient
import com.example.helixapp.HelixHttpException
import com.example.helixapp.HelixPrefs
import com.example.helixapp.HistoryItemUi
import com.example.helixapp.SearchSong
import com.example.helixapp.helix.HelixTrackRequests
import com.example.helixapp.parseHistoryPage
import com.example.helixapp.parseSongs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import retrofit2.Response

/**
 * The browse tree Android Auto (and other media browsers) shows for Helix:
 *
 *   root
 *   ├── Queue      the current queue; tapping a song jumps to it
 *   ├── Stations   tapping a station starts it
 *   ├── Playlists  tapping a playlist plays it
 *   └── Recent     listening history; tapping a song plays it again
 *
 * Auto shows the root's children as tabs. Item ids encode what to do when one is played
 * (see [play]); every playback goes through [PlaybackActions].
 */
object HelixLibraryBrowser {
    const val ROOT_ID = "root"
    const val QUEUE_ID = "queue"
    const val STATIONS_ID = "stations"
    const val PLAYLISTS_ID = "playlists"
    const val RECENT_ID = "recent"

    private const val RECENT_LIMIT = 50

    // Played items need details the id can't carry (a history track's metadata, a station's
    // name); remember them from the last listing.
    private val recentById = HashMap<String, HistoryItemUi>()
    private val songsById = HashMap<String, SearchSong>()
    private val stationNames = HashMap<String, String>()

    fun root(): MediaItem = folder(ROOT_ID, "Helix")

    fun topLevel(): List<MediaItem> = listOf(
        folder(QUEUE_ID, "Queue"),
        folder(STATIONS_ID, "Stations"),
        folder(PLAYLISTS_ID, "Playlists"),
        folder(RECENT_ID, "Recent"),
    )

    /** Children of a folder; throws on network/server errors. */
    suspend fun children(ctx: Context, parentId: String): List<MediaItem> = when (parentId) {
        ROOT_ID -> topLevel()
        QUEUE_ID -> queueItems(ctx)
        STATIONS_ID -> stationItems(ctx, fetch(ctx) { it.listStations() })
        PLAYLISTS_ID -> playlistItems(fetch(ctx) { it.listPlaylists() })
        RECENT_ID -> recentItems(fetch(ctx) { it.history(limit = RECENT_LIMIT) })
        else -> emptyList()
    }

    /**
     * Start whatever a played browse item stands for. Returns false for an id this browser
     * doesn't know (e.g. something cached from an older listing).
     */
    suspend fun play(ctx: Context, mediaId: String): Boolean {
        val (kind, rest) = mediaId.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        when (kind) {
            "queue" -> {
                val index = rest.substringBefore(":").toIntOrNull() ?: return false
                PlaybackActions.jumpTo(ctx, index)
            }
            "station" -> PlaybackActions.playStation(ctx, rest, stationNames[rest].orEmpty())
            "playlist" -> PlaybackActions.playPlaylist(ctx, rest, shuffle = false)
            "recent" -> {
                val item = recentById[rest] ?: return false
                PlaybackActions.playTrack(ctx, item.toTrackRequest())
            }
            "song" -> {
                val song = songsById[rest] ?: return false
                PlaybackActions.playTrack(ctx, HelixTrackRequests.playOrQueueBodyFromSearchSong(HelixPrefs.getBaseUrl(ctx), song))
            }
            else -> return false
        }
        return true
    }

    /** Whether [mediaId] is a playable item from this browser (as opposed to a queue item id). */
    fun isPlayableId(mediaId: String): Boolean =
        mediaId.substringBefore(":", "") in setOf("queue", "station", "playlist", "recent", "song")

    /**
     * Search results for Android Auto's search button: the user's playlists and stations whose
     * names contain the query, then songs from Helix's search.
     */
    suspend fun search(ctx: Context, query: String): List<MediaItem> {
        val q = VoiceSearch.normalize(query)
        if (q.isBlank()) return emptyList()
        val named = (playlistItems(fetch(ctx) { it.listPlaylists() }) + stationItems(ctx, fetch(ctx) { it.listStations() }))
            .filter { VoiceSearch.normalize(it.mediaMetadata.title.toString()).contains(q) }
        return named + songItems(ctx, fetch(ctx) { it.ytmusicSearch(query, songLimit = 15, albumLimit = 1) })
    }

    internal fun songItems(ctx: Context?, json: String): List<MediaItem> =
        parseSongs(json).filter { it.videoId.isNotBlank() || it.subsonicSongId.isNotBlank() }.map { song ->
            val key = song.videoId.ifBlank { "subsonic-${song.subsonicSongId}" }
            songsById[key] = song
            val subtitle = listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" • ")
            playable("song:$key", song.title, subtitle, ctx?.let { artUrl(HelixPrefs.getBaseUrl(it), song.thumbnailUrl) })
        }

    private suspend fun queueItems(ctx: Context): List<MediaItem> {
        PlayerStateStore.refresh(ctx)
        val state = PlayerStateStore.state.value ?: return emptyList()
        val base = HelixPrefs.getBaseUrl(ctx)
        return state.queue.mapIndexed { index, q ->
            val title = if (q.queueItemId == state.now?.queueItemId) "▶ ${q.title}" else q.title
            playable("queue:$index:${q.queueItemId}", title, q.artist, artUrl(base, q.artUrl))
        }
    }

    internal fun stationItems(ctx: Context?, json: String): List<MediaItem> {
        val arr = JSONArray(json)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").ifBlank { return@mapNotNull null }
            val name = o.optString("name").ifBlank { "Station" }
            stationNames[id] = name
            playable("station:$id", name, "Station", ctx?.let { artUrl(HelixPrefs.getBaseUrl(it), o.optString("thumbnail_url")) })
        }
    }

    internal fun playlistItems(json: String): List<MediaItem> {
        val arr = JSONArray(json)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            // System playlists (e.g. Liked songs) are played by their key.
            val id = o.optString("system_key").ifBlank { o.optString("id") }.ifBlank { return@mapNotNull null }
            val count = o.optInt("track_count", 0)
            playable("playlist:$id", o.optString("name").ifBlank { "Playlist" }, if (count == 1) "1 song" else "$count songs", null)
        }
    }

    internal fun recentItems(json: String): List<MediaItem> =
        parseHistoryPage(json).items.map { h ->
            val key = h.id.ifBlank { "${h.playedAtMs}-${h.title}" }
            recentById[key] = h
            playable("recent:$key", h.title, h.artist, null)
        }

    private suspend fun fetch(ctx: Context, call: suspend (com.example.helixapp.HelixApi) -> Response<String>): String {
        val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
        val resp = withContext(Dispatchers.IO) { call(api) }
        if (!resp.isSuccessful) throw HelixHttpException(resp.code())
        return resp.body().orEmpty()
    }

    private fun artUrl(base: String, url: String?): String? =
        url?.takeIf { it.isNotBlank() }?.let { com.example.helixapp.HelixImages.absoluteUrl(base, it) }

    private fun folder(id: String, title: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build()
        )
        .build()

    private fun playable(id: String, title: String, subtitle: String?, artworkUrl: String?): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(subtitle)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .apply { artworkUrl?.let { setArtworkUri(android.net.Uri.parse(it)) } }
                    .build()
            )
            .build()

    private fun HistoryItemUi.toTrackRequest() = HelixTrackRequests.playOrQueueBodyFromPlaylistTrack(
        title = title, artist = artist, album = album, artUrl = artUrl, durationMs = durationMs,
        source = source, subsonicSongId = subsonicSongId, ytVideoId = ytVideoId, ytBrowseId = ytBrowseId,
        mbRecordingId = mbRecordingId, mbArtistId = mbArtistId,
    )
}
