package com.example.helixapp.data

import android.content.Context
import com.example.helixapp.PlaylistUi
import com.example.helixapp.parsePlaylists
import org.json.JSONObject

/** The user's playlists. */
interface PlaylistRepository {
    suspend fun list(): List<PlaylistUi>
    suspend fun create(name: String)
    suspend fun delete(playlistId: String)
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
}
