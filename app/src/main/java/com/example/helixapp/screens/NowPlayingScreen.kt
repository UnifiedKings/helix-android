package com.example.helixapp

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import coil.compose.AsyncImage
import com.example.helixapp.ui.theme.HelixAccent
import com.example.helixapp.ui.theme.HelixBorder
import com.example.helixapp.ui.theme.HelixMuted
import com.example.helixapp.ui.theme.HelixSurfaceRaised
import com.example.helixapp.playback.HelixTransport
import com.example.helixapp.playback.NowPlayingUi
import com.example.helixapp.playback.PlaybackController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun NowPlayingScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf("Idle") }
    var loading by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf<NowPlayingUi?>(null) }
    // Station identity must come from backend playback state, not device-local prefs.
    // Multiple Helix clients can change the active station at any time.
    var activeStationName by remember { mutableStateOf<String?>(null) }

    // Player (Media3) metadata - same truth source as the lockscreen.
    // Backend state is still fetched for IDs (likes/dislikes) and queue context.
    var metaTitle by remember { mutableStateOf<String?>(null) }
    var metaArtist by remember { mutableStateOf<String?>(null) }
    var metaAlbum by remember { mutableStateOf<String?>(null) }
    var metaArtUri by remember { mutableStateOf<String?>(null) }
    var metaMediaId by remember { mutableStateOf<String?>(null) }
    // IDs used for likes/dislikes come directly from backend now_playing, which is authoritative across clients.
    var currentYtVideoId by remember { mutableStateOf<String?>(null) }
    var currentSubsonicSongId by remember { mutableStateOf<String?>(null) }
    // IMPORTANT:
    // The play/pause icon should reflect LOCAL playback state (Media3), not backend state.
    // Backend state can lag behind a tap, which makes it feel like the button "didn't work"
    // and forces multiple taps.
    var isPlaying by remember { mutableStateOf(false) }
    var playPauseInFlight by remember { mutableStateOf(false) }

    // Likes / Dislikes (thumb up/down)
    var isLiked by remember { mutableStateOf(false) }
    var isDisliked by remember { mutableStateOf(false) }
    var ratingInFlight by remember { mutableStateOf(false) }

    // Subsonic availability for the current backend track. A queue item can originate
    // outside Subsonic and still already exist in the library, so do not infer this
    // solely from source/subsonic_song_id; resolve it against Helix as well.
    var isInSubsonic by remember { mutableStateOf(false) }
    var subsonicAvailabilityKnown by remember { mutableStateOf(false) }
    var addToSubsonicPending by remember { mutableStateOf(false) }

    // Seeking (local player only, just like the web frontend).
    // We intentionally do NOT involve the backend for seek; the backend doesn't track position.
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var userSeeking by remember { mutableStateOf(false) }
    var seekTargetMs by remember { mutableStateOf(0L) }

    fun fmt(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0L) / 1000L).toInt()
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    fun refresh() {
        if (HelixPrefs.getSessionToken(ctx).isNullOrBlank()) {
            status = "Not logged in — go to Login"
            now = null
            activeStationName = null
            return
        }

        loading = true
        status = "Loading…"
        scope.launch {
            try {
                val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                val resp = withContext(Dispatchers.IO) { api.playerState() }
                if (!resp.isSuccessful) {
                    status = "Failed (HTTP ${resp.code()})"
                    now = null
                    activeStationName = null
                    return@launch
                }
                val body = resp.body().orEmpty()
                val root = JSONObject(body)
                val (nowUi, _) = HelixTransport.parseQueueFromState(body)
                now = nowUi
                activeStationName = root
                    .optJSONObject("active_station")
                    ?.optString("name", "")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }

                // The backend current track is authoritative for Helix identity.
                // Do not require the local Media3 mediaId to match: playback may have been
                // started or changed by the web frontend, a lobby, or another Helix client.
                currentYtVideoId = nowUi?.ytVideoId?.takeIf { it.isNotBlank() }
                currentSubsonicSongId = nowUi?.subsonicSongId?.takeIf { it.isNotBlank() }

                // We still parse backend is_playing for initial display, but we do NOT treat it as the source of truth.
                // Media3 listener below will keep isPlaying updated to the actual local player state.
                if (!isPlaying) {
                    isPlaying = runCatching { JSONObject(body).optBoolean("is_playing", false) }.getOrDefault(false)
                }
                status = if (nowUi == null) "Nothing playing" else "Done"
            } catch (e: Exception) {
                status = "Error: ${e.javaClass.simpleName}: ${e.message}"
                now = null
                activeStationName = null
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    suspend fun resolveCurrentSubsonicAvailability(): Boolean {
        val current = now ?: return false
        if (!current.subsonicSongId.isNullOrBlank() || current.source.equals("subsonic", ignoreCase = true)) {
            return true
        }
        val title = current.title.trim()
        val artist = current.artist.trim()
        if (title.isBlank() || artist.isBlank()) return false

        val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))

        // /api/subsonic/resolve caches by the caller-provided key. Never use a constant
        // key such as "now-playing" here: a successful lookup for one song would then be
        // reused for every later song until the backend cache expires.
        // Use the same song:<youtube-id> key that Helix invalidates after an import when
        // possible, and a stable text identity only as a fallback for tracks without YT IDs.
        val ytId = current.ytVideoId?.trim().orEmpty()
        val album = current.album.trim()
        val resolveKey = if (ytId.isNotBlank()) {
            "song:$ytId"
        } else {
            val normalizedTitle = title.lowercase().replace(Regex("\\s+"), " ")
            val normalizedArtist = artist.lowercase().replace(Regex("\\s+"), " ")
            val normalizedAlbum = album.lowercase().replace(Regex("\\s+"), " ")
            "song:text:$normalizedTitle|$normalizedArtist|$normalizedAlbum|${current.durationMs}"
        }

        val payload = JSONObject().apply {
            put("songs", JSONArray().apply {
                put(JSONObject().apply {
                    put("key", resolveKey)
                    put("title", title)
                    put("artist", artist)
                    if (album.isNotBlank()) put("album", album)
                    if (current.durationMs > 0L) put("duration_ms", current.durationMs)
                    if (ytId.isNotBlank()) put("yt_video_id", ytId)
                })
            })
            put("albums", JSONArray())
        }
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val resp = withContext(Dispatchers.IO) { api.subsonicResolve(body) }
        if (!resp.isSuccessful) return false
        return JSONObject(resp.body().orEmpty())
            .optJSONObject("songs")
            ?.optJSONObject(resolveKey)
            ?.optBoolean("available", false) == true
    }

    // Reset pending import state whenever the backend advances to a different queue item,
    // then determine whether the new current track already exists in Subsonic.
    LaunchedEffect(now?.queueItemId, now?.title, now?.artist, now?.subsonicSongId) {
        if (now == null) {
            addToSubsonicPending = false
            isInSubsonic = false
            subsonicAvailabilityKnown = false
            return@LaunchedEffect
        }
        addToSubsonicPending = false
        subsonicAvailabilityKnown = false
        isInSubsonic = runCatching { resolveCurrentSubsonicAvailability() }.getOrDefault(false)
        subsonicAvailabilityKnown = true
    }

    // After an import request is accepted, keep the action disabled and periodically ask
    // Helix whether the current song has appeared in Subsonic. The button is only restored
    // if the track changes (effect above) or the request itself fails.
    LaunchedEffect(addToSubsonicPending, now?.queueItemId) {
        if (!addToSubsonicPending || now == null) return@LaunchedEffect
        while (addToSubsonicPending) {
            delay(2_000)
            val available = runCatching { resolveCurrentSubsonicAvailability() }.getOrDefault(false)
            if (available) {
                isInSubsonic = true
                subsonicAvailabilityKnown = true
                addToSubsonicPending = false
                refresh()
                break
            }
        }
    }

    fun addCurrentToSubsonic() {
        val current = now ?: return
        if (isInSubsonic || addToSubsonicPending) return

        val ytId = current.ytVideoId?.trim().orEmpty()
        val title = current.title.trim()
        val artist = current.artist.trim()
        if (title.isBlank() || artist.isBlank()) return

        addToSubsonicPending = true
        scope.launch {
            try {
                val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                val payload = JSONObject().apply {
                    if (ytId.isNotBlank()) put("yt_video_id", ytId)
                    put("title", title)
                    put("artist", artist)
                    if (current.album.isNotBlank()) put("album", current.album)
                    if (current.artUrl.isNotBlank()) put("art_url", current.artUrl)
                }
                val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val resp = withContext(Dispatchers.IO) { api.subsonicAddTrack(body) }
                if (!resp.isSuccessful) {
                    addToSubsonicPending = false
                    status = "Add to Subsonic failed (HTTP ${resp.code()})"
                }
            } catch (e: Exception) {
                addToSubsonicPending = false
                status = "Add to Subsonic error: ${e.javaClass.simpleName}"
            }
        }
    }

    // Fetch like/dislike state from the backend current-track identity. This intentionally
    // does not depend on metaMediaId: Media3 can be stale when another Helix client
    // (for example the web frontend) changed the current queue item.
    LaunchedEffect(
        currentYtVideoId,
        currentSubsonicSongId,
        now?.queueItemId,
        now?.title,
        now?.artist
    ) {
        val hasStableId = !currentYtVideoId.isNullOrBlank() || !currentSubsonicSongId.isNullOrBlank()
        val hasTextIdentity = !now?.title.isNullOrBlank() && !now?.artist.isNullOrBlank()
        if (!hasStableId && !hasTextIdentity) {
            isLiked = false
            isDisliked = false
            return@LaunchedEffect
        }

        runCatching {
            val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))

            fun parseRating(body: String, keys: List<String>): Boolean {
                if (body.trim().equals("true", ignoreCase = true)) return true
                val obj = runCatching { JSONObject(body) }.getOrNull() ?: return false
                return keys.any { obj.optBoolean(it, false) }
            }

            suspend fun fetchLiked(): Boolean {
                // Older Android clients could create a like keyed only by yt_video_id.
                // Current playback often knows both identities, while the backend endpoint
                // gives subsonic_song_id precedence when both are sent. Check each stable
                // identity independently so those older likes still resolve correctly.
                val subId = currentSubsonicSongId?.takeIf { it.isNotBlank() }
                if (subId != null) {
                    val resp = withContext(Dispatchers.IO) {
                        api.likesIsLiked(ytVideoId = null, subsonicSongId = subId)
                    }
                    if (resp.isSuccessful && parseRating(resp.body().orEmpty(), listOf("liked", "is_liked", "isLiked"))) {
                        return true
                    }
                }

                val ytId = currentYtVideoId?.takeIf { it.isNotBlank() }
                if (ytId != null) {
                    val resp = withContext(Dispatchers.IO) {
                        api.likesIsLiked(ytVideoId = ytId, subsonicSongId = null)
                    }
                    if (resp.isSuccessful && parseRating(resp.body().orEmpty(), listOf("liked", "is_liked", "isLiked"))) {
                        return true
                    }
                }

                // Legacy fallback: older app versions could create likes whose stable key
                // was title+artist because neither media ID was available at like time.
                // /is-liked cannot query those keys, so compare against the user's liked list.
                val currentTitle = (now?.title ?: metaTitle ?: "").trim()
                val currentArtist = (now?.artist ?: metaArtist ?: "").trim()
                if (currentTitle.isNotBlank() && currentArtist.isNotBlank()) {
                    val resp = withContext(Dispatchers.IO) { api.likesList() }
                    if (resp.isSuccessful) {
                        val root = runCatching { JSONObject(resp.body().orEmpty()) }.getOrNull()
                        val items = root?.optJSONArray("items")
                        if (items != null) {
                            fun norm(v: String): String = v.trim().lowercase()
                            val wantedTitle = norm(currentTitle)
                            val wantedArtist = norm(currentArtist)
                            for (i in 0 until items.length()) {
                                val item = items.optJSONObject(i) ?: continue
                                if (norm(item.optString("title", "")) == wantedTitle &&
                                    norm(item.optString("artist", "")) == wantedArtist) {
                                    return true
                                }
                            }
                        }
                    }
                }
                return false
            }

            suspend fun fetchDisliked(): Boolean {
                val subId = currentSubsonicSongId?.takeIf { it.isNotBlank() }
                if (subId != null) {
                    val resp = withContext(Dispatchers.IO) {
                        api.dislikesIsDisliked(ytVideoId = null, subsonicSongId = subId)
                    }
                    if (resp.isSuccessful && parseRating(resp.body().orEmpty(), listOf("disliked", "is_disliked", "isDisliked"))) {
                        return true
                    }
                }

                val ytId = currentYtVideoId?.takeIf { it.isNotBlank() }
                if (ytId != null) {
                    val resp = withContext(Dispatchers.IO) {
                        api.dislikesIsDisliked(ytVideoId = ytId, subsonicSongId = null)
                    }
                    if (resp.isSuccessful && parseRating(resp.body().orEmpty(), listOf("disliked", "is_disliked", "isDisliked"))) {
                        return true
                    }
                }
                return false
            }

            isLiked = fetchLiked()
            isDisliked = fetchDisliked()
        }.onFailure {
            // If a request fails, don't leave stale "liked" UI on screen.
            isLiked = false
            isDisliked = false
        }
    }

// Keep UI play/pause state in sync with the local Media3 player.
    // This matches the behavior you see on the lockscreen controls (which already work correctly).
    DisposableEffect(Unit) {
        var installedOn: MediaController? = null

        fun seedFromController(c: MediaController) {
            val item = c.currentMediaItem
            val md = item?.mediaMetadata
            metaMediaId = item?.mediaId
            metaTitle = md?.title?.toString()
            metaArtist = md?.artist?.toString()
            metaAlbum = md?.albumTitle?.toString()
            metaArtUri = md?.artworkUri?.toString()
        }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                isPlaying = isPlayingNow
                // If a tap initiated a change, consider it "done" once Media3 reports the result.
                if (playPauseInFlight) playPauseInFlight = false
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                // Update UI from the same truth source as the lockscreen: the player.
                val md = mediaItem?.mediaMetadata
                metaMediaId = mediaItem?.mediaId
                metaTitle = md?.title?.toString()
                metaArtist = md?.artist?.toString()
                metaAlbum = md?.albumTitle?.toString()
                metaArtUri = md?.artworkUri?.toString()

                // Still refresh backend state (likes/dislikes IDs, queue context).
                scope.launch { refresh() }
            }

            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                metaTitle = mediaMetadata.title?.toString()
                metaArtist = mediaMetadata.artist?.toString()
                metaAlbum = mediaMetadata.albumTitle?.toString()
                metaArtUri = mediaMetadata.artworkUri?.toString()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                    // Clear any stuck in-flight state.
                    if (playPauseInFlight) playPauseInFlight = false
                }
            }
        }

        PlaybackController.get(ctx) { c ->
            installedOn = c
            controller = c
            // Seed state immediately.
            isPlaying = c.isPlaying
            seedFromController(c)
            // Seed seek state.
            positionMs = runCatching { c.currentPosition }.getOrDefault(0L)
            val seedDur = runCatching { c.duration }.getOrDefault(0L)
            durationMs = if (seedDur > 0) seedDur else (now?.durationMs ?: 0L)
            c.addListener(listener)
        }

        onDispose {
            installedOn?.removeListener(listener)
            if (controller === installedOn) controller = null
        }
    }

    // Update seek bar periodically from the local player.
    //
    // Important: ExoPlayer/Media3 can report TIME_UNSET (negative) for duration when the
    // stream isn't fully seekable (e.g., missing HTTP Range/Content-Length). When that
    // happens, duration becomes "unknown" and the seek bar appears broken.
    //
    // Helix already provides a reliable per-track duration via /api/playback/state, so we
    // fall back to that duration to keep the seek UI functional even if the stream is
    // temporarily non-seekable.
    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        while (true) {
            // Don't fight the user's finger while scrubbing.
            if (!userSeeking) {
                val d = runCatching { c.duration }.getOrDefault(0L)
                val p = runCatching { c.currentPosition }.getOrDefault(0L)
                // Media3 returns TIME_UNSET as a very negative number if duration is unknown.
                val backendDur = now?.durationMs ?: 0L
                durationMs = if (d > 0) d else backendDur
                positionMs = if (p > 0) p else 0L
            }
            delay(500)
        }
    }

    val baseUrl = HelixPrefs.getBaseUrl(ctx)
    val art = when {
        !metaArtUri.isNullOrBlank() -> metaArtUri.orEmpty()
        else -> HelixImages.absoluteUrl(baseUrl, now?.artUrl.orEmpty())
    }

    android.util.Log.d("HelixArtDebug", "metaArtUri=" + (metaArtUri ?: "null") +
        " now.artUrl=" + (now?.artUrl ?: "null") +
        " resolvedArt=" + art)


    fun seekRelative(deltaMs: Long) {
        val c = controller ?: return
        val d = durationMs.takeIf { it > 0 } ?: (now?.durationMs ?: 0L)
        val target = (c.currentPosition + deltaMs).coerceAtLeast(0L)
            .let { if (d > 0) it.coerceAtMost(d) else it }
        c.seekTo(target)
        positionMs = target
    }

    fun togglePlayPause() {
        scope.launch {
            try {
                val api = HelixClient.create(ctx, baseUrl)
                if (playPauseInFlight) return@launch
                playPauseInFlight = true

                if (isPlaying) {
                    PlaybackController.pause(ctx)
                    withContext(Dispatchers.IO) { api.pause() }
                } else {
                    // Cold-start fix:
                    // On a fresh process start, the Media3 timeline may be empty. If we call
                    // PlaybackController.resume() before we've loaded a horizon playlist,
                    // the subsequent refreshAndSync() can overwrite the timeline and PAUSE,
                    // making the first Play tap appear to do nothing.
                    //
                    // Solution: ask the backend to resume first, then refreshAndSync() so
                    // the horizon playlist loads with autoplay=true (backend is_playing).
                    // This ensures the first tap reliably starts playback.

                    val resumeOk = runCatching {
                        withContext(Dispatchers.IO) { api.resume() }
                    }.isSuccess

                    HelixTransport.refreshAndSync(ctx, forceLoadStream = true)

                    // Best-effort: if the backend resume failed or state lagged, force local play.
                    if (!resumeOk) {
                        PlaybackController.resume(ctx)
                    }

                    if (com.example.helixapp.playback.HelixTransport.needsInitialSync) {
                        com.example.helixapp.playback.HelixTransport.markInitialSynced()
                    }
                }
            } catch (_: Exception) {
                // ignore
            } finally {
                playPauseInFlight = false
            }
        }
    }

    fun rateLike() {
        // Use backend IDs resolved for the currently playing mediaId.
        if (currentYtVideoId.isNullOrBlank() && currentSubsonicSongId.isNullOrBlank()) return

        // Prefer player metadata (same truth source as lockscreen) for display fields.
        val title = (metaTitle ?: now?.title ?: "").trim()
        val artist = (metaArtist ?: now?.artist ?: "").trim()
        val album = (metaAlbum ?: now?.album ?: "").trim()
        val artUrl = (now?.artUrl ?: metaArtUri ?: "").trim()
        val src = (now?.source ?: "").trim()
        val dur = (if (durationMs > 0) durationMs else (now?.durationMs ?: 0L))

        scope.launch {
            val prevLiked = isLiked
            val prevDisliked = isDisliked
            ratingInFlight = true
            isLiked = !prevLiked
            if (!prevLiked) isDisliked = false
            try {
                val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                val mt = "application/json; charset=utf-8".toMediaType()
                val payload = JSONObject()
                    .put("title", title)
                    .put("artist", artist)
                    .put("album", album)
                    .put("duration_ms", dur)
                    .put("art_url", artUrl)
                    .put("source", src)
                    .put("yt_video_id", currentYtVideoId)
                    .put("subsonic_song_id", currentSubsonicSongId)
                    .toString()
                    .toRequestBody(mt)
                withContext(Dispatchers.IO) { api.likesToggle(payload) }
            } catch (_: Exception) {
                isLiked = prevLiked
                isDisliked = prevDisliked
            } finally {
                ratingInFlight = false
            }
        }
    }

    fun rateDislike() {
        if (currentYtVideoId.isNullOrBlank() && currentSubsonicSongId.isNullOrBlank()) return

        val title = (metaTitle ?: now?.title ?: "").trim()
        val artist = (metaArtist ?: now?.artist ?: "").trim()
        val album = (metaAlbum ?: now?.album ?: "").trim()
        val artUrl = (now?.artUrl ?: metaArtUri ?: "").trim()
        val src = (now?.source ?: "").trim()
        val dur = (if (durationMs > 0) durationMs else (now?.durationMs ?: 0L))

        scope.launch {
            val prevLiked = isLiked
            val prevDisliked = isDisliked
            ratingInFlight = true
            isDisliked = !prevDisliked
            if (!prevDisliked) isLiked = false
            try {
                val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                val mt = "application/json; charset=utf-8".toMediaType()
                val payload = JSONObject()
                    .put("title", title)
                    .put("artist", artist)
                    .put("album", album)
                    .put("duration_ms", dur)
                    .put("art_url", artUrl)
                    .put("source", src)
                    .put("yt_video_id", currentYtVideoId)
                    .put("subsonic_song_id", currentSubsonicSongId)
                    .toString()
                    .toRequestBody(mt)
                withContext(Dispatchers.IO) { api.dislikesToggle(payload) }
            } catch (_: Exception) {
                isLiked = prevLiked
                isDisliked = prevDisliked
            } finally {
                ratingInFlight = false
            }
        }
    }

// Seek bar (local).
    val safeDur = durationMs.coerceAtLeast(0L)
    val safePos = (if (userSeeking) seekTargetMs else positionMs)
        .coerceIn(0L, if (safeDur > 0) safeDur else Long.MAX_VALUE)
    val remainingMs = if (safeDur > 0) (safeDur - safePos).coerceAtLeast(0L) else 0L

    Box(modifier = Modifier.fillMaxSize()) {
        // Keep the Now Playing page deliberately quiet. The queue is not rendered here at all;
        // NowPlayingWithQueueSheet owns the swipe-up queue drawer.
        if (art.isNotBlank()) {
            AsyncImage(
                model = HelixImages.request(ctx, art),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(54.dp)
                    .alpha(0.12f),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.94f))
        )

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val artSize = (maxWidth - 42.dp).coerceAtMost(318.dp)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 21.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(4.dp))

                if (art.isNotBlank()) {
                    AsyncImage(
                        model = HelixImages.request(ctx, art),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(artSize)
                            .clip(RoundedCornerShape(20.dp))
                            .border(1.dp, HelixBorder, RoundedCornerShape(20.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(artSize)
                            .clip(RoundedCornerShape(20.dp))
                            .background(HelixSurfaceRaised)
                            .border(1.dp, HelixBorder, RoundedCornerShape(20.dp))
                    )
                }

                Spacer(Modifier.height(18.dp))

                val displayTitle = metaTitle?.takeIf { it.isNotBlank() } ?: now?.title ?: "Nothing playing"
                val displayArtist = metaArtist?.takeIf { it.isNotBlank() } ?: now?.artist
                val stationName = activeStationName

                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (!displayArtist.isNullOrBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = displayArtist,
                        style = MaterialTheme.typography.titleMedium,
                        color = HelixAccent,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (stationName != null) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = HelixSurfaceRaised,
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, HelixBorder),
                    ) {
                        Text(
                            text = stationName,
                            style = MaterialTheme.typography.labelMedium,
                            color = HelixMuted,
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                        )
                    }
                }

                if (now != null && subsonicAvailabilityKnown) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (isInSubsonic) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = androidx.compose.ui.graphics.Color(0xFF35C759),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            Text(
                                text = if (isInSubsonic) "In Subsonic" else "Not in Subsonic",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isInSubsonic) androidx.compose.ui.graphics.Color(0xFF35C759) else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        if (!isInSubsonic) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .height(26.dp)
                                        .background(HelixBorder.copy(alpha = 0.8f))
                                )
                                if (addToSubsonicPending) {
                                    OutlinedButton(
                                        onClick = { },
                                        enabled = false,
                                        shape = RoundedCornerShape(9.dp),
                                        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 6.dp),
                                    ) {
                                        Text(
                                            text = "Adding…",
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                    }
                                } else {
                                    Button(
                                        onClick = { addCurrentToSubsonic() },
                                        shape = RoundedCornerShape(9.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = HelixAccent,
                                            contentColor = MaterialTheme.colorScheme.onPrimary,
                                        ),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                    ) {
                                        Text(
                                            text = "+ Add",
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Deliberately put dislike and like at opposite screen edges. Rating actions are
                // destructive enough that adjacent thumb buttons are too easy to mis-tap.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        enabled = (now != null) && !ratingInFlight,
                        onClick = { rateDislike() },
                        // Keep a generous touch target without drawing a button container.
                        modifier = Modifier.size(54.dp),
                    ) {
                        Icon(
                            imageVector = if (isDisliked) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
                            contentDescription = "Dislike",
                            tint = if (isDisliked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    IconButton(
                        enabled = (now != null) && !ratingInFlight,
                        onClick = { rateLike() },
                        // Keep a generous touch target without drawing a button container.
                        modifier = Modifier.size(54.dp),
                    ) {
                        Icon(
                            imageVector = if (isLiked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                            contentDescription = "Like",
                            tint = if (isLiked) HelixAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                Slider(
                    value = if (safeDur > 0) safePos.toFloat() else 0f,
                    onValueChange = { v ->
                        if (safeDur <= 0) return@Slider
                        userSeeking = true
                        seekTargetMs = v.toLong().coerceIn(0L, safeDur)
                    },
                    onValueChangeFinished = {
                        val c = controller
                        if (c != null && safeDur > 0) {
                            val target = seekTargetMs.coerceIn(0L, safeDur)
                            c.seekTo(target)
                            positionMs = target
                        }
                        userSeeking = false
                    },
                    valueRange = if (safeDur > 0) 0f..safeDur.toFloat() else 0f..0f,
                    enabled = safeDur > 0,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(fmt(safePos), style = MaterialTheme.typography.labelMedium, color = HelixMuted)
                    Text(
                        if (safeDur > 0) "-${fmt(remainingMs)}" else "—",
                        style = MaterialTheme.typography.labelMedium,
                        color = HelixMuted,
                    )
                }

                Spacer(Modifier.height(9.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            PlaybackController.get(ctx) { c ->
                                val elapsedMs = c.currentPosition
                                if (elapsedMs > 3_000L) {
                                    c.seekTo(0)
                                    return@get
                                }

                                scope.launch {
                                    try {
                                        val api = HelixClient.create(ctx, baseUrl)
                                        withContext(Dispatchers.IO) { api.prev() }
                                        HelixTransport.refreshAndSync(ctx, forceLoadStream = true)
                                        refresh()
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                        },
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(34.dp))
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        // Make the transport state readable even before looking at the icon:
                        // paused uses a circle, while actively playing uses a rounded square.
                        shape = if (isPlaying) RoundedCornerShape(20.dp) else CircleShape,
                        border = BorderStroke(1.dp, HelixAccent),
                        shadowElevation = 8.dp,
                    ) {
                        IconButton(
                            enabled = !playPauseInFlight,
                            onClick = { togglePlayPause() },
                            modifier = Modifier.size(76.dp),
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(40.dp),
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            scope.launch {
                                try {
                                    val api = HelixClient.create(ctx, baseUrl)
                                    withContext(Dispatchers.IO) { api.next() }
                                    HelixTransport.refreshAndSync(ctx, forceLoadStream = true)
                                    refresh()
                                } catch (_: Exception) {
                                }
                            }
                        },
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(34.dp))
                    }
                }

                // Intentionally no queue button, queue preview, shuffle, output selector, equalizer,
                // or sleep timer here. The queue is a gesture-only drawer from this screen.
                Spacer(Modifier.height(8.dp))

                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else if (status.startsWith("Error") || status.startsWith("Failed") || status.startsWith("Not logged")) {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
