package com.example.helixapp

import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface HelixApi {
    @GET("health")
    suspend fun health(): Response<String>

    @POST("auth/login")
    suspend fun login(@Body payload: RequestBody): Response<String>
    @POST("auth/logout")
    suspend fun logout(): Response<String>

    @GET("auth/me")
    suspend fun me(): Response<String>

    // Admin user management
    @GET("admin/users")
    suspend fun adminUsers(): Response<String>

    @POST("admin/users")
    suspend fun adminCreateUser(@Body payload: RequestBody): Response<String>

    @PATCH("admin/users/{user_id}")
    suspend fun adminUpdateUser(
        @Path("user_id") userId: String,
        @Body payload: RequestBody,
    ): Response<String>

    @GET("api/user/settings")
    suspend fun userSettings(): Response<String>

    @PATCH("api/user/settings")
    suspend fun updateUserSettings(@Body payload: RequestBody): Response<String>
    // Helix backend 0.0.33: YT Music search endpoint.
    @GET("api/ytmusic/search")
    suspend fun ytmusicSearch(
        @Query("q") q: String,
        @Query("song_limit") songLimit: Int = 15,
        @Query("album_limit") albumLimit: Int = 15,
    ): Response<String>


    @GET("api/ytmusic/search/artists")
    suspend fun ytmusicSearchArtists(
        @Query("q") q: String,
        @Query("artist_limit") artistLimit: Int = 15,
    ): Response<String>
    @GET("api/ytmusic/artists/{browse_id}")
    suspend fun artistDetail(@Path("browse_id") browseId: String): Response<String>

    @GET("api/ytmusic/artists/{browse_id}/popular")
    suspend fun artistPopular(
        @Path("browse_id") browseId: String,
        @Query("limit") limit: Int = 10,
    ): Response<String>
    @GET("api/ytmusic/artists/{browse_id}/albums")
    suspend fun artistAlbums(
        @Path("browse_id") browseId: String,
        @Query("limit") limit: Int = 50,
    ): Response<String>

    @GET("api/ytmusic/artists/{browse_id}/similar")
    suspend fun artistSimilar(
        @Path("browse_id") browseId: String,
        @Query("limit") limit: Int = 20,
    ): Response<String>
    // Album view (YouTube Music)
    @GET("api/album/{browse_id}")
    suspend fun albumView(@Path("browse_id") browseId: String): Response<String>


    // Stations
    @GET("api/stations")
    suspend fun listStations(): Response<String>

    @GET("api/stations/types")
    suspend fun listStationTypes(): Response<String>

    @PATCH("api/stations/{station_id}")
    suspend fun updateStation(
        @Path("station_id") stationId: String,
        @Body payload: RequestBody,
    ): Response<String>
    @POST("api/stations")
    suspend fun createStation(@Body payload: RequestBody): Response<String>

    @DELETE("api/stations/{station_id}")
    suspend fun deleteStation(@Path("station_id") stationId: String): Response<String>

    // Listening history
    @GET("api/history")
    suspend fun history(@Query("station_id") stationId: String? = null): Response<String>

    // Playlists
    @GET("api/playlists")
    suspend fun listPlaylists(): Response<String>
    @GET("api/playlists/{playlist_id}")
    suspend fun playlistDetail(@Path("playlist_id") playlistId: String): Response<String>

    @POST("api/playlists")
    suspend fun createPlaylist(@Body payload: RequestBody): Response<String>

    @DELETE("api/playlists/{playlist_id}")
    suspend fun deletePlaylist(@Path("playlist_id") playlistId: String): Response<String>
    @POST("api/playlists/{playlist_id}/tracks")
    suspend fun playlistAddTrack(
        @Path("playlist_id") playlistId: String,
        @Body payload: RequestBody,
    ): Response<String>

    @DELETE("api/playlists/{playlist_id}/tracks/{track_id}")
    suspend fun playlistRemoveTrack(
        @Path("playlist_id") playlistId: String,
        @Path("track_id") trackId: String,
    ): Response<String>
    @PATCH("api/playlists/{playlist_id}/tracks/reorder")
    suspend fun playlistReorderTracks(
        @Path("playlist_id") playlistId: String,
        @Body payload: RequestBody,
    ): Response<String>

    // Player (queue + playback)
    @GET("api/playback/state")
    suspend fun playerState(): Response<String>

    @POST("api/playback/track")
    suspend fun playTrack(@Body payload: RequestBody): Response<String>
    @POST("api/playback/album")
    suspend fun playAlbum(@Body payload: RequestBody): Response<String>


    @POST("api/playback/playlist")
    suspend fun playPlaylist(@Body payload: RequestBody): Response<String>
    @POST("api/queue/album")
    suspend fun queueAppendAlbum(@Body payload: RequestBody): Response<String>

    @POST("api/queue/track")
    suspend fun queueAppendTrack(@Body payload: RequestBody): Response<String>
    @DELETE("api/queue/items/{queue_item_id}")
    suspend fun queueRemoveItem(@Path("queue_item_id") queueItemId: String): Response<String>

    @PATCH("api/queue/items/reorder")
    suspend fun reorderQueue(@Body payload: RequestBody): Response<String>

    @POST("api/playback/jump")
    suspend fun jump(@Body payload: RequestBody): Response<String>

    @POST("api/playback/next")
    suspend fun next(): Response<String>

    @POST("api/playback/previous")
    suspend fun prev(): Response<String>

    @POST("api/playback/pause")
    suspend fun pause(): Response<String>
    @POST("api/playback/resume")
    suspend fun resume(): Response<String>

    @POST("api/playback/ended")
    suspend fun ended(): Response<String>


    // Subsonic availability (batch resolver)
    @POST("api/subsonic/resolve")
    suspend fun subsonicResolve(@Body payload: RequestBody): Response<String>

    // Subsonic add (enqueue download/import)
    @POST("api/subsonic/add/track")
    suspend fun subsonicAddTrack(@Body payload: RequestBody): Response<String>
    @POST("api/subsonic/add/album")
    suspend fun subsonicAddAlbum(@Body payload: RequestBody): Response<String>


// Likes
    @GET("api/likes/is-liked")
    suspend fun likesIsLiked(
        @Query("yt_video_id") ytVideoId: String? = null,
        @Query("subsonic_song_id") subsonicSongId: String? = null,
    ): Response<String>

    @GET("api/likes")
    suspend fun likesList(): Response<String>

    @POST("api/likes/toggle")
    suspend fun likesToggle(@Body payload: RequestBody): Response<String>
    // Dislikes
    @GET("api/dislikes/is-disliked")
    suspend fun dislikesIsDisliked(
        @Query("yt_video_id") ytVideoId: String? = null,
        @Query("subsonic_song_id") subsonicSongId: String? = null,
    ): Response<String>

    @POST("api/dislikes/toggle")
    suspend fun dislikesToggle(@Body payload: RequestBody): Response<String>
    // Stations playback
    @POST("api/stations/{station_id}/play")
    suspend fun playStation(@Path("station_id") stationId: String, @Body payload: RequestBody): Response<String>
}
