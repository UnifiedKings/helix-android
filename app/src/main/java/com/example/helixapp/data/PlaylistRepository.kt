package com.example.helixapp.data

import android.content.Context
import com.example.helixapp.HelixPrefs
import com.example.helixapp.PlaylistDetail
import com.example.helixapp.PlaylistUi
import com.example.helixapp.SearchSong
import com.example.helixapp.helix.HelixTrackRequests
import com.example.helixapp.parsePlaylistDetail
import com.example.helixapp.parsePlaylists
import org.json.JSONArray
import org.json.JSONObject

/** The user's playlists. */
interface PlaylistRepository {
    suspend fun list(): List<PlaylistUi>
    suspend fun create(name: String)
    suspend fun delete(playlistId: String)

    suspend fun detail(playlistId: String): PlaylistDetail

    /** Save a new track order (every track id, in order); returns the updated playlist. */
    suspend fun reorderTracks(playlistId: String, trackIds: List<String>): PlaylistDetail

    suspend fun removeTrack(playlistId: String, trackId: String)

    suspend fun addTrack(playlistId: String, song: SearchSong)
}

class HelixPlaylistRepository(context: Context) : PlaylistRepository {
    private val ctx = context.applicationContext

    override suspend fun list(): List<PlaylistUi> = parsePlaylists(helixCall(ctx) { it.listPlaylists() })

    override suspend fun create(name: String) {
        helixCall(ctx) { it.createPlaylist(JSONObject().put("name", name).toJsonBody()) }
    }

    override suspend fun delete(playlistId: String) {
        helixCall(ctx) { it.deletePlaylist(playlistId) }
    }

    override suspend fun detail(playlistId: String): PlaylistDetail =
        parsePlaylistDetail(helixCall(ctx) { it.playlistDetail(playlistId) })

    override suspend fun reorderTracks(playlistId: String, trackIds: List<String>): PlaylistDetail {
        val body = JSONObject().put("track_ids", JSONArray(trackIds))
        return parsePlaylistDetail(helixCall(ctx) { it.playlistReorderTracks(playlistId, body.toJsonBody()) })
    }

    override suspend fun removeTrack(playlistId: String, trackId: String) {
        helixCall(ctx) { it.playlistRemoveTrack(playlistId, trackId) }
    }

    override suspend fun addTrack(playlistId: String, song: SearchSong) {
        val body = HelixTrackRequests.playOrQueueBodyFromSearchSong(HelixPrefs.getBaseUrl(ctx), song)
            .put("playlist_id", playlistId)
        helixCall(ctx) { it.playlistAddTrack(playlistId, body.toJsonBody()) }
    }
}
