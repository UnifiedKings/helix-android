package com.example.helixapp.data

import android.content.Context
import com.example.helixapp.parseSubsonicResolve
import org.json.JSONArray
import org.json.JSONObject

/** Something to look up in Subsonic; [key] is "song:…" or "album:…" and comes back in the result. */
data class SubsonicLookup(
    val key: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0,
    val ytVideoId: String = "",
)

/** A YouTube Music track to import into Subsonic. */
data class SubsonicTrackRequest(
    val ytVideoId: String,
    val title: String,
    val artist: String,
    val albumArtist: String = "",
    val album: String = "",
    val artUrl: String = "",
)

/** The Subsonic library behind Helix: what's in it, and importing into it. */
interface SubsonicRepository {
    /** Availability by key for each lookup. */
    suspend fun resolve(songs: List<SubsonicLookup>, albums: List<SubsonicLookup> = emptyList()): Map<String, Boolean>

    suspend fun addTrack(track: SubsonicTrackRequest)

    /** [album] is the album request body (browse_id, title, artist, art_url). */
    suspend fun addAlbum(album: JSONObject)
}

class HelixSubsonicRepository(context: Context) : SubsonicRepository {
    private val ctx = context.applicationContext

    override suspend fun resolve(songs: List<SubsonicLookup>, albums: List<SubsonicLookup>): Map<String, Boolean> {
        if (songs.isEmpty() && albums.isEmpty()) return emptyMap()
        val body = JSONObject()
            .put("songs", JSONArray().apply { songs.forEach { put(it.toJson()) } })
            .put("albums", JSONArray().apply { albums.forEach { put(it.toJson()) } })
        return parseSubsonicResolve(helixCall(ctx) { it.subsonicResolve(body.toJsonBody()) })
    }

    override suspend fun addTrack(track: SubsonicTrackRequest) {
        val body = JSONObject().apply {
            if (track.ytVideoId.isNotBlank()) put("yt_video_id", track.ytVideoId)
            put("title", track.title)
            put("artist", track.artist)
            if (track.albumArtist.isNotBlank()) put("album_artist", track.albumArtist)
            if (track.album.isNotBlank()) put("album", track.album)
            if (track.artUrl.isNotBlank()) put("art_url", track.artUrl)
        }
        helixCall(ctx) { it.subsonicAddTrack(body.toJsonBody()) }
    }

    override suspend fun addAlbum(album: JSONObject) {
        helixCall(ctx) { it.subsonicAddAlbum(album.toJsonBody()) }
    }

    private fun SubsonicLookup.toJson() = JSONObject().apply {
        put("key", key)
        put("title", title)
        put("artist", artist)
        if (album.isNotBlank()) put("album", album)
        if (durationMs > 0) put("duration_ms", durationMs)
        if (ytVideoId.isNotBlank()) put("yt_video_id", ytVideoId)
    }
}
