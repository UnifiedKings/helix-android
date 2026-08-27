package com.example.helixapp

// Shared models for Search results (avoid duplicate declarations across files).

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
