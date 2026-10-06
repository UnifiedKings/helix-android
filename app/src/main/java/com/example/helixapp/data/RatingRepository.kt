package com.example.helixapp.data

import android.content.Context
import com.example.helixapp.parseLikedSongs
import com.example.helixapp.parseRatingFlag
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** A song as the like/dislike endpoints identify it. */
data class RatedTrack(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artUrl: String,
    val source: String,
    val ytVideoId: String?,
    val subsonicSongId: String?,
) {
    /** The rating endpoints need a stable id. */
    val hasId: Boolean get() = !ytVideoId.isNullOrBlank() || !subsonicSongId.isNullOrBlank()
}

/** Likes (the Liked Songs playlist) and dislikes. */
interface RatingRepository {
    /** Liked by Subsonic id, YouTube id, or (failing both) title and artist in the likes list. */
    suspend fun isLiked(track: RatedTrack): Boolean
    suspend fun isDisliked(track: RatedTrack): Boolean
    suspend fun toggleLike(track: RatedTrack)
    suspend fun toggleDislike(track: RatedTrack)
}

class HelixRatingRepository(context: Context) : RatingRepository {
    private val ctx = context.applicationContext

    override suspend fun isLiked(track: RatedTrack): Boolean {
        if (byIds(track, LIKED_KEYS) { yt, sub -> likesIsLiked(ytVideoId = yt, subsonicSongId = sub) }) return true
        val title = track.title.trim()
        val artist = track.artist.trim()
        if (title.isBlank() || artist.isBlank()) return false
        val liked = runCatching { likedSongs.get { parseLikedSongs(helixCall(ctx) { it.likesList() }) } }.getOrDefault(emptyList())
        return liked.any { (t, a) -> t.trim().equals(title, ignoreCase = true) && a.trim().equals(artist, ignoreCase = true) }
    }

    override suspend fun isDisliked(track: RatedTrack): Boolean =
        byIds(track, DISLIKED_KEYS) { yt, sub -> dislikesIsDisliked(ytVideoId = yt, subsonicSongId = sub) }

    /** Ask by Subsonic id, then by YouTube id; a failed request counts as "no". */
    private suspend fun byIds(
        track: RatedTrack,
        keys: List<String>,
        request: suspend com.example.helixapp.HelixApi.(yt: String?, sub: String?) -> retrofit2.Response<String>,
    ): Boolean {
        track.subsonicSongId?.takeIf { it.isNotBlank() }?.let { sub ->
            if (runCatching { parseRatingFlag(helixCall(ctx) { it.request(null, sub) }, keys) }.getOrDefault(false)) return true
        }
        track.ytVideoId?.takeIf { it.isNotBlank() }?.let { yt ->
            if (runCatching { parseRatingFlag(helixCall(ctx) { it.request(yt, null) }, keys) }.getOrDefault(false)) return true
        }
        return false
    }

    override suspend fun toggleLike(track: RatedTrack) {
        helixCall(ctx) { it.likesToggle(track.toJson().toJsonBody()) }
        likedSongs.invalidate()
    }

    override suspend fun toggleDislike(track: RatedTrack) {
        helixCall(ctx) { it.dislikesToggle(track.toJson().toJsonBody()) }
        // Disliking a liked song unlikes it.
        likedSongs.invalidate()
    }

    private fun RatedTrack.toJson() = JSONObject()
        .put("title", title)
        .put("artist", artist)
        .put("album", album)
        .put("duration_ms", durationMs)
        .put("art_url", artUrl)
        .put("source", source)
        .put("yt_video_id", ytVideoId)
        .put("subsonic_song_id", subsonicSongId)

    private companion object {
        /** Shared by every screen: the title/artist fallback no longer downloads the list per track. */
        val likedSongs = TimedCache<List<Pair<String, String>>>(ttlMs = 10 * 60_000L)

        val LIKED_KEYS = listOf("liked", "is_liked", "isLiked")
        val DISLIKED_KEYS = listOf("disliked", "is_disliked", "isDisliked")
    }
}

/**
 * Holds one value for [ttlMs]; [get] loads it when missing or stale, one load at a time.
 * A failed load isn't cached, so the next call tries again.
 */
class TimedCache<T>(private val ttlMs: Long, private val clock: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    private var value: T? = null
    private var loadedAt = 0L

    suspend fun get(load: suspend () -> T): T = mutex.withLock {
        val cached = value
        if (cached != null && clock() - loadedAt < ttlMs) return cached
        load().also {
            value = it
            loadedAt = clock()
        }
    }

    suspend fun invalidate() = mutex.withLock { value = null }
}
