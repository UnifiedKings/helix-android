package com.example.helixapp.helix

import com.example.helixapp.HelixImages
import com.example.helixapp.SearchSong
import com.example.helixapp.SearchAlbum
import org.json.JSONObject

object HelixTrackRequests {

    fun playOrQueueBodyFromSearchSong(baseUrl: String, song: SearchSong): JSONObject {
        val art = HelixImages.absoluteUrl(baseUrl, song.thumbnailUrl)
        val body = JSONObject()
            .put("title", song.title)
            .put("artist", song.artist)

        if (song.album.isNotBlank()) body.put("album", song.album)
        if (art.isNotBlank()) body.put("art_url", art)
        if (song.subsonicSongId.isNotBlank()) body.put("subsonic_song_id", song.subsonicSongId)
        if (song.videoId.isNotBlank()) {
            body.put("yt_video_id", song.videoId)
            body.put("ytmusic_url", "https://music.youtube.com/watch?v=${song.videoId}")
        }

        return body
    }


fun playOrQueueBodyFromSearchAlbum(baseUrl: String, album: SearchAlbum): JSONObject {
    val art = HelixImages.absoluteUrl(baseUrl, album.thumbnailUrl)
    val body = JSONObject()
        .put("browse_id", album.browseId)

    if (album.subsonicAlbumId.isNotBlank()) body.put("subsonic_album_id", album.subsonicAlbumId)
    if (album.title.isNotBlank()) body.put("title", album.title)
    if (album.artist.isNotBlank()) body.put("artist", album.artist)
    if (album.year.isNotBlank()) body.put("year", album.year)
    if (art.isNotBlank()) body.put("art_url", art)

    return body
}
    fun playOrQueueBodyFromPlaylistTrack(
        title: String,
        artist: String,
        album: String,
        artUrl: String,
        durationMs: Long,
        source: String,
        subsonicSongId: String,
        ytVideoId: String,
        ytBrowseId: String,
        mbRecordingId: String,
        mbArtistId: String,
    ): JSONObject {
        val body = JSONObject()
            .put("title", title)
            .put("artist", artist)

        if (album.isNotBlank()) body.put("album", album)
        if (artUrl.isNotBlank()) body.put("art_url", artUrl)
        if (durationMs > 0L) body.put("duration_ms", durationMs)
        if (source.isNotBlank()) body.put("source", source)
        if (subsonicSongId.isNotBlank()) body.put("subsonic_song_id", subsonicSongId)
        if (ytVideoId.isNotBlank()) {
            body.put("yt_video_id", ytVideoId)
            body.put("ytmusic_url", "https://music.youtube.com/watch?v=$ytVideoId")
        }
        if (ytBrowseId.isNotBlank()) body.put("yt_browse_id", ytBrowseId)
        if (mbRecordingId.isNotBlank()) body.put("recording_id", mbRecordingId)
        if (mbRecordingId.isNotBlank()) body.put("mb_recording_id", mbRecordingId)
        if (mbArtistId.isNotBlank()) body.put("mb_artist_id", mbArtistId)

        return body
    }
}