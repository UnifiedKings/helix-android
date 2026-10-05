package com.example.helixapp.data

import android.content.Context
import com.example.helixapp.AlbumView
import com.example.helixapp.ArtistDetailUi
import com.example.helixapp.HistoryPage
import com.example.helixapp.SearchAlbum
import com.example.helixapp.SearchArtist
import com.example.helixapp.SearchSong
import com.example.helixapp.SimilarArtistUi
import com.example.helixapp.parseAlbumView
import com.example.helixapp.parseArtistAlbums
import com.example.helixapp.parseArtistDetail
import com.example.helixapp.parseArtists
import com.example.helixapp.parseHistoryPage
import com.example.helixapp.parsePopularTracks
import com.example.helixapp.parseSimilarArtists
import org.json.JSONObject

/** Listening history, search, artists and albums. */
interface LibraryRepository {
    /** A page of history, newest first; [event] is "completed", "skipped" or null for both. */
    suspend fun history(event: String?, offset: Int, limit: Int): HistoryPage

    suspend fun album(browseId: String): AlbumView

    suspend fun searchArtists(query: String, limit: Int = 15): List<SearchArtist>

    suspend fun artist(browseId: String): ArtistDetailUi

    suspend fun artistPopular(browseId: String, limit: Int = 10): List<SearchSong>

    suspend fun artistAlbums(browseId: String): List<SearchAlbum>

    /** Similar artists, plus the server's MusicBrainz resolution status while it's still working. */
    suspend fun similarArtists(browseId: String): SimilarArtists
}

data class SimilarArtists(val artists: List<SimilarArtistUi>, val resolutionStatus: String)

class HelixLibraryRepository(context: Context) : LibraryRepository {
    private val ctx = context.applicationContext

    override suspend fun history(event: String?, offset: Int, limit: Int): HistoryPage =
        parseHistoryPage(helixCall(ctx) { it.history(event = event, limit = limit, offset = offset) })

    override suspend fun album(browseId: String): AlbumView = parseAlbumView(helixCall(ctx) { it.albumView(browseId) })

    override suspend fun searchArtists(query: String, limit: Int): List<SearchArtist> =
        parseArtists(helixCall(ctx) { it.ytmusicSearchArtists(query, artistLimit = limit) })

    override suspend fun artist(browseId: String): ArtistDetailUi =
        parseArtistDetail(helixCall(ctx) { it.artistDetail(browseId) }, browseId)

    override suspend fun artistPopular(browseId: String, limit: Int): List<SearchSong> =
        parsePopularTracks(helixCall(ctx) { it.artistPopular(browseId, limit) })

    override suspend fun artistAlbums(browseId: String): List<SearchAlbum> =
        parseArtistAlbums(helixCall(ctx) { it.artistAlbums(browseId) })

    override suspend fun similarArtists(browseId: String): SimilarArtists {
        val body = helixCall(ctx) { it.artistSimilar(browseId) }
        val status = runCatching { JSONObject(body).optString("mb_resolution_status", "") }.getOrDefault("")
        return SimilarArtists(parseSimilarArtists(body), status)
    }
}
