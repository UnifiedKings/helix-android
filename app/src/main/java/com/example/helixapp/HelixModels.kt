package com.example.helixapp

import org.json.JSONObject

// Typed models for Helix server responses, parsed in HelixJson.kt.

// ---- Search -----------------------------------------------------------------------------

data class SearchSong(
    val title: String,
    val artist: String,
    val album: String,
    val thumbnailUrl: String,
    val videoId: String,
    val source: String = "ytmusic",
    val subsonicSongId: String = "",
) {
    val isFromSubsonic: Boolean get() = source.equals("subsonic", ignoreCase = true)
}

data class SearchAlbum(
    val title: String,
    val artist: String,
    val year: String,
    val thumbnailUrl: String,
    /** YouTube Music browse id (used for album view). */
    val browseId: String,
    val source: String = "ytmusic",
    val subsonicAlbumId: String = "",
) {
    val isFromSubsonic: Boolean get() = source.equals("subsonic", ignoreCase = true)
}

data class SearchArtist(
    val name: String,
    val thumbnailUrl: String,
    val browseId: String,
    val subscriberCount: String = "",
    val monthlyListeners: String = "",
)

data class StationUi(
    val id: String,
    val name: String,
    val stationType: String,
    val config: JSONObject,
    val seedType: String,
    val seedTitle: String,
    val seedArtist: String,
    val discovery: Float,
    val seedInfluence: Float,
    val thumbnailUrl: String,
)

data class StationChoiceUi(
    val value: String,
    val label: String,
)

data class StationConfigOptionUi(
    val key: String,
    val label: String,
    val type: String,
    val description: String,
    val required: Boolean,
    val defaultValue: Any?,
    val min: Double?,
    val max: Double?,
    val step: Double?,
    val choices: List<StationChoiceUi>,
    val minItems: Int?,
    val maxItems: Int?,
    val category: String,
    val categoryLabel: String,
    val categoryOrder: Int,
    val order: Int,
)

data class StationProviderUi(
    val stationType: String,
    val displayName: String,
    val description: String,
    val configOptions: List<StationConfigOptionUi>,
)

data class PlaylistUi(
    val id: String,
    val name: String,
    val systemKey: String,
    val kind: String,
    val trackCount: Int,
    val thumbnailUrl: String,
) {
    /** The id to play it by: system playlists (e.g. Liked Songs) are played by their key. */
    val playId: String get() = systemKey.ifBlank { id }
}

data class PlaylistTrackUi(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val artUrl: String,
    val durationMs: Long,
    val source: String,
    val subsonicSongId: String,
    val ytVideoId: String,
    val ytBrowseId: String,
    val mbRecordingId: String,
    val mbArtistId: String,
)

data class PlaylistDetail(
    val name: String,
    val thumbnailUrl: String,
    val systemKey: String,
    val tracks: List<PlaylistTrackUi>,
)

data class HistoryItemUi(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    /** "completed" or "skipped". */
    val event: String,
    /** When it was played, in epoch millis; 0 if the server's timestamp couldn't be read. */
    val playedAtMs: Long,
    val artUrl: String,
    val durationMs: Long,
    val source: String,
    val ytVideoId: String,
    val ytBrowseId: String,
    val subsonicSongId: String,
    val mbRecordingId: String,
    val mbArtistId: String,
) {
    val skipped: Boolean get() = event.equals("skipped", ignoreCase = true)
}

data class HistoryPage(val items: List<HistoryItemUi>, val hasMore: Boolean)

data class ArtistDetailUi(
    val browseId: String,
    val name: String,
    val thumbnailUrl: String,
    val mbArtistId: String,
    val resolutionStatus: String,
)

data class SimilarArtistUi(
    val name: String,
    val mbArtistId: String = "",
    val browseId: String = "",
    val thumbnailUrl: String = "",
)

/** /api/album/{browse_id}. */
data class AlbumView(
    val title: String,
    val artist: String,
    val year: String,
    val thumbnailUrl: String,
    val tracks: List<AlbumTrack>,
)

data class AlbumTrack(
    val pos: Int,
    val title: String,
    val artist: String,
    val durationSeconds: Int,
    val videoId: String,
)

// ---- Account ----------------------------------------------------------------------------

/** /auth/me. */
data class AccountInfo(val username: String, val role: String) {
    val isAdmin: Boolean get() = role == "admin"
}

/** The account-wide playback settings (/api/user/settings). */
data class PlaybackSettings(
    /** "append" or "next". */
    val queueAddPosition: String = "append",
    val stationQueueAhead: Int = 3,
    val stationQueueAheadMax: Int = 10,
    /** 0..1, used by the web player. */
    val defaultVolume: Float = 1f,
)

/** A user as the admin endpoints describe them. */
data class AdminUser(
    val id: String,
    val username: String,
    val role: String,
    val isActive: Boolean,
    val subsonicImportOverride: Boolean,
    val canImportSubsonic: Boolean,
)
