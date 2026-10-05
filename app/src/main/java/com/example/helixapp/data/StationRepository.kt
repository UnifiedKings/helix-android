package com.example.helixapp.data

import android.content.Context
import com.example.helixapp.StationUi
import com.example.helixapp.parseStations
import org.json.JSONObject

/** The user's stations. */
interface StationRepository {
    suspend fun list(): List<StationUi>

    /**
     * Create an artist radio station named "<artist> Radio" (as the Artist screen's Create
     * Station button does). Returns the new station's id, or "" if the server didn't say.
     */
    suspend fun createArtistStation(artist: String): String
}

class HelixStationRepository(context: Context) : StationRepository {
    private val ctx = context.applicationContext

    override suspend fun list(): List<StationUi> = parseStations(helixCall(ctx) { it.listStations() })

    override suspend fun createArtistStation(artist: String): String {
        val body = JSONObject()
            .put("name", "$artist Radio")
            .put("seed_type", "artist")
            .put("seed_title", "")
            .put("seed_artist", artist)
            .put("discovery", 0.35)
            .put("seed_influence", 0.75)
        val created = helixCall(ctx) { it.createStation(body.toJsonBody()) }
        return runCatching { JSONObject(created).optString("id") }.getOrDefault("")
    }
}
