package com.example.helixapp

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

// Parsers for Helix server responses, shared by the screens, Android Auto browsing and voice
// search. Each turns a response body into the typed models in HelixModels.kt.

// ---- Search, artists and albums ---------------------------------------------------------

/** Song results of /api/ytmusic/search. */
internal fun parseSongs(json: String): List<SearchSong> =
    parseSongArray(JSONObject(json).optJSONArray("songs") ?: JSONArray())

/** An artist's popular songs (/api/ytmusic/artists/{id}/popular). */
internal fun parsePopularTracks(json: String, fallbackArtist: String = ""): List<SearchSong> {
    val root = JSONObject(json)
    val arr = root.optJSONArray("tracks") ?: root.optJSONArray("songs") ?: root.optJSONArray("popular") ?: JSONArray()
    return parseSongArray(arr, fallbackArtist = fallbackArtist.ifBlank { root.optString("artist_name", "") })
}

/** Album results of /api/ytmusic/search (Subsonic-only albums may have no browse id). */
internal fun parseAlbums(json: String): List<SearchAlbum> {
    val albums = JSONObject(json).optJSONArray("albums") ?: JSONArray()
    return (0 until albums.length()).mapNotNull { i ->
        val o = albums.optJSONObject(i) ?: return@mapNotNull null
        val title = o.optString("title", o.optString("name", "")).trim()
        if (title.isBlank()) return@mapNotNull null
        SearchAlbum(
            title = title,
            artist = o.optString("artist", o.optString("artists", "")),
            year = o.optString("year", o.optString("release_year", "")),
            thumbnailUrl = o.thumbnail(),
            browseId = o.optString("browse_id", o.optString("browseId", "")).trim(),
            source = o.optString("source", "ytmusic"),
            subsonicAlbumId = o.optString("subsonic_album_id", o.optString("subsonicAlbumId", "")),
        )
    }
}

/** Results of /api/ytmusic/search/artists; empty for an empty body. */
internal fun parseArtists(json: String): List<SearchArtist> {
    if (json.isBlank()) return emptyList()
    val artists = JSONObject(json).optJSONArray("artists") ?: JSONArray()
    return (0 until artists.length()).mapNotNull { i ->
        val o = artists.optJSONObject(i) ?: return@mapNotNull null
        val name = o.optString("name", o.optString("artist", "")).trim()
        if (name.isBlank()) return@mapNotNull null
        SearchArtist(
            name = name,
            thumbnailUrl = o.thumbnail(),
            browseId = o.optString("browse_id", o.optString("browseId", o.optString("artist_id", ""))).trim(),
            subscriberCount = o.optString("subscriber_count", o.optString("subscribers", "")),
            monthlyListeners = o.optString("monthly_listeners", o.optString("monthlyListeners", "")),
        )
    }
}

private fun JSONObject.thumbnail(): String =
    listOf("thumbnail_url", "thumbnail", "thumb").map { optString(it, "").trim() }.firstOrNull { it.isNotBlank() }.orEmpty()

/** Songs from a search, album or artist response array; fallbacks fill fields a track lacks. */
internal fun parseSongArray(
    arr: JSONArray,
    fallbackAlbum: String = "",
    fallbackArtist: String = "",
    fallbackArt: String = "",
): List<SearchSong> {
    val out = ArrayList<SearchSong>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val title = o.optString("title", o.optString("name", "")).trim()
        if (title.isBlank()) continue
        val artist = listOf(
            o.optString("artist", ""),
            o.optString("artist_name", ""),
            o.optString("artists", ""),
            fallbackArtist,
        ).map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
        val album = o.optString("album", fallbackAlbum)
        val videoId = listOf(
            o.optString("video_id", ""),
            o.optString("videoId", ""),
        ).map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
        val source = o.optString("source", "ytmusic")
        val subId = o.optString(
            "subsonic_song_id",
            o.optString("subsonicSongId", ""),
        )
        val thumb = listOf(
            o.optString("thumbnail_url", ""),
            o.optString("thumbnail", ""),
            o.optString("thumb", ""),
            fallbackArt,
        ).map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()

        out += SearchSong(
            title = title,
            artist = artist,
            album = album,
            thumbnailUrl = thumb,
            videoId = videoId,
            source = source,
            subsonicSongId = subId,
        )
    }
    return out
}

internal fun parseArtistDetail(json: String, browseId: String): ArtistDetailUi {
    val root = JSONObject(json)
    return ArtistDetailUi(
        browseId = root.optString("browse_id", root.optString("artist_id", browseId)).ifBlank { browseId },
        name = root.optString("name", root.optString("artist", "")),
        thumbnailUrl = root.optString("thumbnail_url", root.optString("thumbnail", "")),
        mbArtistId = root.optString("mb_artist_id", ""),
        resolutionStatus = root.optString("mb_resolution_status", "unresolved"),
    )
}

internal fun parseArtistAlbums(json: String): List<SearchAlbum> {
    val root = JSONObject(json)
    val arr = root.optJSONArray("albums") ?: JSONArray()
    val out = ArrayList<SearchAlbum>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        out.add(
            SearchAlbum(
                title = o.optString("title", ""),
                artist = o.optString("artist", root.optString("artist_name", "")),
                year = o.optString("year", ""),
                thumbnailUrl = o.optString("thumbnail_url", o.optString("thumbnail", "")),
                browseId = o.optString("browse_id", o.optString("browseId", "")),
            )
        )
    }
    return out
}

internal fun parseSimilarArtists(json: String): List<SimilarArtistUi> {
    val root = JSONObject(json)
    val arr = root.optJSONArray("similar_artists") ?: JSONArray()
    val out = ArrayList<SimilarArtistUi>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val name = o.optString("name")
            .ifBlank { o.optString("artist_name") }
            .ifBlank { o.optString("artist") }
        out.add(
            SimilarArtistUi(
                name = name,
                mbArtistId = o.optString("mb_artist_id", o.optString("artist_mbid", "")),
                browseId = o.optString("browse_id", o.optString("yt_browse_id", "")),
                thumbnailUrl = o.optString("thumbnail_url", o.optString("thumbnail", "")),
            )
        )
    }
    return out.filter { it.name.isNotBlank() || it.browseId.isNotBlank() || it.mbArtistId.isNotBlank() }
}

/** An album's details and tracks (/api/album/{browse_id}). */
internal fun parseAlbumView(json: String): AlbumView {
    val root = JSONObject(json)
    fun JSONObject.artistName(fallback: String = ""): String =
        listOf("artist", "artist_name", "artists", "album_artist", "albumArtist")
            .map { optString(it, "").trim() }.firstOrNull { it.isNotBlank() } ?: fallback
    val albumArtist = root.artistName()
    val arr = root.optJSONArray("tracks") ?: JSONArray()
    val tracks = (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val title = o.optString("title", "")
        if (title.isBlank()) return@mapNotNull null
        AlbumTrack(
            pos = o.optInt("pos", i + 1),
            title = title,
            artist = o.artistName(albumArtist),
            durationSeconds = o.optInt("duration_seconds", 0),
            videoId = listOf("video_id", "videoId").map { o.optString(it, "").trim() }.firstOrNull { it.isNotBlank() }.orEmpty(),
        )
    }
    return AlbumView(
        title = root.optString("title", ""),
        artist = albumArtist,
        year = root.optString("year", ""),
        thumbnailUrl = root.optString("thumbnail_url", ""),
        tracks = tracks,
    )
}

// ---- Stations ---------------------------------------------------------------------------

internal fun parseStations(json: String): List<StationUi> {
    val arr = JSONArray(json)
    val out = ArrayList<StationUi>(arr.length())
    for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val config = obj.optJSONObject("config") ?: JSONObject()
        out.add(
            StationUi(
                id = obj.optString("id", ""),
                name = obj.optString("name", ""),
                stationType = obj.optString("station_type", "listenbrainz_similar_artist"),
                config = config,
                seedType = config.optString("seed_type", obj.optString("seed_type", "")),
                seedTitle = config.optString("seed_title", obj.optString("seed_title", "")),
                seedArtist = config.optString("seed_artist", obj.optString("seed_artist", "")),
                discovery = config.optDouble("discovery", obj.optDouble("discovery", 0.35)).toFloat(),
                seedInfluence = config.optDouble("seed_influence", obj.optDouble("seed_influence", 0.75)).toFloat(),
                thumbnailUrl = obj.optString("thumbnail_url", ""),
            )
        )
    }
    return out
}

internal fun parseStationProviders(json: String): List<StationProviderUi> {
    val arr = JSONArray(json)
    val out = ArrayList<StationProviderUi>(arr.length())
    for (i in 0 until arr.length()) {
        val providerObj = arr.optJSONObject(i) ?: continue
        val optionsArr = providerObj.optJSONArray("config_options") ?: JSONArray()
        val options = ArrayList<StationConfigOptionUi>(optionsArr.length())
        for (j in 0 until optionsArr.length()) {
            val opt = optionsArr.optJSONObject(j) ?: continue
            val choicesArr = opt.optJSONArray("choices") ?: JSONArray()
            val choices = ArrayList<StationChoiceUi>(choicesArr.length())
            for (k in 0 until choicesArr.length()) {
                val choice = choicesArr.optJSONObject(k) ?: continue
                val value = choice.optString("value", "")
                choices.add(StationChoiceUi(value = value, label = choice.optString("label", value)))
            }
            options.add(
                StationConfigOptionUi(
                    key = opt.optString("key", ""),
                    label = opt.optString("label", opt.optString("key", "")),
                    type = opt.optString("type", "string"),
                    description = opt.optString("description", ""),
                    required = opt.optBoolean("required", false),
                    defaultValue = opt.opt("default"),
                    min = opt.optNullableDouble("min"),
                    max = opt.optNullableDouble("max"),
                    step = opt.optNullableDouble("step"),
                    choices = choices,
                    minItems = opt.optNullableInt("min_items"),
                    maxItems = opt.optNullableInt("max_items"),
                    category = opt.optString("category", "").ifBlank { "options" },
                    categoryLabel = opt.optString("category_label", ""),
                    categoryOrder = opt.optNullableInt("category_order") ?: 999,
                    order = opt.optNullableInt("order") ?: 999,
                )
            )
        }
        out.add(
            StationProviderUi(
                stationType = providerObj.optString("station_type", ""),
                displayName = providerObj.optString("display_name", providerObj.optString("station_type", "")),
                description = providerObj.optString("description", ""),
                configOptions = options.filter { it.key.isNotBlank() },
            )
        )
    }
    return out.filter { it.stationType.isNotBlank() }
}

internal fun JSONObject.optNullableDouble(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return optDouble(key)
}

internal fun JSONObject.optNullableInt(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return optInt(key)
}

// ---- Playlists --------------------------------------------------------------------------

internal fun parsePlaylists(json: String): List<PlaylistUi> {
    val arr = JSONArray(json)
    val out = ArrayList<PlaylistUi>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val systemKey = o.optString("system_key", "")
        out.add(
            PlaylistUi(
                id = o.optString("id", ""),
                name = o.optString("name", ""),
                systemKey = systemKey,
                kind = o.optString("kind", ""),
                trackCount = o.optInt("track_count", 0),
                thumbnailUrl = o.optString("thumbnail_url", ""),
            )
        )
    }
    return out
}

internal fun parsePlaylistDetail(json: String): PlaylistDetailParsed {
    val root = JSONObject(json)
    val pl = root.optJSONObject("playlist") ?: JSONObject()
    val name = pl.optString("name", "Playlist")
    val thumb = pl.optString("thumbnail_url", "")
    val systemKey = pl.optString("system_key", "")
    val arr = root.optJSONArray("tracks") ?: JSONArray()
    val tracks = ArrayList<PlaylistTrackUi>(arr.length())

    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        tracks.add(
            PlaylistTrackUi(
                id = o.optString("id", ""),
                title = o.optString("title", ""),
                artist = o.optString("artist", ""),
                album = o.optString("album", ""),
                artUrl = o.optString("art_url", ""),
                durationMs = o.optLong("duration_ms", 0L),
                source = o.optString("source", ""),
                subsonicSongId = o.optString("subsonic_song_id", ""),
                ytVideoId = o.optString("yt_video_id", ""),
                ytBrowseId = o.optString("yt_browse_id", ""),
                mbRecordingId = o.optString("mb_recording_id", ""),
                mbArtistId = o.optString("mb_artist_id", ""),
            )
        )
    }

    return PlaylistDetailParsed(
        name = name,
        thumbnailUrl = thumb,
        systemKey = systemKey,
        tracks = tracks,
    )
}

// ---- History ----------------------------------------------------------------------------

internal fun parseHistoryPage(json: String): HistoryPage {
    val root = JSONObject(json)
    val arr = root.optJSONArray("items") ?: JSONArray()
    val items = (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        HistoryItemUi(
            id = o.optString("id", ""),
            title = o.optString("title", ""),
            artist = o.optString("artist", ""),
            album = o.optString("album", ""),
            event = o.optString("event", ""),
            playedAtMs = parseServerTimestamp(o.optString("created_at", "")),
            artUrl = o.optString("art_url", ""),
            durationMs = o.optLong("duration_ms", 0L),
            source = o.optString("source", ""),
            ytVideoId = o.optString("yt_video_id", ""),
            ytBrowseId = o.optString("yt_browse_id", ""),
            subsonicSongId = o.optString("subsonic_song_id", ""),
            mbRecordingId = o.optString("mb_recording_id", ""),
            mbArtistId = o.optString("mb_artist_id", ""),
        )
    }
    return HistoryPage(items, hasMore = root.optBoolean("has_more", false))
}

/**
 * Parse the server's ISO-8601 UTC timestamps ("2026-10-05T06:28:54.382477Z", with or without
 * fractional seconds or a "Z"/"+00:00" suffix) to epoch millis; 0 if unreadable. Uses
 * SimpleDateFormat because java.time needs API 26 and the app supports 23.
 */
internal fun parseServerTimestamp(value: String): Long {
    val base = value.trim().take(19)
    if (base.length < 19) return 0L
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }
    val seconds = runCatching { format.parse(base)?.time }.getOrNull() ?: return 0L
    val fraction = Regex("""^\.(\d{1,3})""").find(value.trim().drop(19))?.groupValues?.get(1)
    val millis = fraction?.padEnd(3, '0')?.toLongOrNull() ?: 0L
    return seconds + millis
}
