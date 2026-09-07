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
import com.example.helixapp.playback.PlayerCommandCoordinator
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
    var activeStationName by remember { mutableStateOf<String?>(null) }

    var metaTitle by remember { mutableStateOf<String?>(null) }
    var metaArtist by remember { mutableStateOf<String?>(null) }
    var metaAlbum by remember { mutableStateOf<String?>(null) }
    var metaArtUri by remember { mutableStateOf<String?>(null) }
    var metaMediaId by remember { mutableStateOf<String?>(null) }

    var currentYtVideoId by remember { mutableStateOf<String?>(null) }
    var currentSubsonicSongId by remember { mutableStateOf<String?>(null) }

    var isPlaying by remember { mutableStateOf(false) }
    var playPauseInFlight by remember { mutableStateOf(false) }

    var isLiked by remember { mutableStateOf(false) }
    var isDisliked by remember { mutableStateOf(false) }
    var ratingInFlight by remember { mutableStateOf(false) }

    var isInSubsonic by remember { mutableStateOf(false) }
    var subsonicAvailabilityKnown by remember { mutableStateOf(false) }
    var addToSubsonicPending by remember { mutableStateOf(false) }

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

                currentYtVideoId = nowUi?.ytVideoId?.takeIf { it.isNotBlank() }
                currentSubsonicSongId = nowUi?.subsonicSongId?.takeIf { it.isNotBlank() }

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
                                if (
                                    norm(item.optString("title", "")) == wantedTitle &&
                                    norm(item.optString("artist", "")) == wantedArtist
                                ) {
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
            isLiked = false
            isDisliked = false
        }
    }

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
                if (playPauseInFlight) playPauseInFlight = false
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                val md = mediaItem?.mediaMetadata
                metaMediaId = mediaItem?.mediaId
                metaTitle = md?.title?.toString()
                metaArtist = md?.artist?.toString()
                metaAlbum = md?.albumTitle?.toString()
                metaArtUri = md?.artworkUri?.toString()

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
                    if (playPauseInFlight) playPauseInFlight = false
                }
            }
        }

        PlaybackController.get(ctx) { c ->
            installedOn = c
            controller = c
            isPlaying = c.isPlaying
            seedFromController(c)
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

    // Only local Media3 metadata for the same backend queue item may drive the UI.
    // During close/reopen or cross-client transitions, Media3 can briefly still contain
    // the previous item while the backend queue/current item has already changed.
    val mediaMatchesBackend =
        !metaMediaId.isNullOrBlank() &&
        !now?.queueItemId.isNullOrBlank() &&
        metaMediaId == now?.queueItemId

    LaunchedEffect(controller, now?.queueItemId, metaMediaId) {
        val c = controller ?: return@LaunchedEffect

        while (true) {
            if (!userSeeking) {
                val stillMatchesBackend =
                    !c.currentMediaItem?.mediaId.isNullOrBlank() &&
                    !now?.queueItemId.isNullOrBlank() &&
                    c.currentMediaItem?.mediaId == now?.queueItemId

                if (stillMatchesBackend) {
                    val d = runCatching { c.duration }.getOrDefault(0L)
                    val p = runCatching { c.currentPosition }.getOrDefault(0L)
                    val backendDur = now?.durationMs ?: 0L
                    durationMs = if (d > 0) d else backendDur
                    positionMs = if (p > 0) p else 0L
                } else {
                    durationMs = now?.durationMs ?: 0L
                    positionMs = 0L
                }
            }

            delay(500)
        }
    }

    val baseUrl = HelixPrefs.getBaseUrl(ctx)
    val art = when {
        mediaMatchesBackend && !metaArtUri.isNullOrBlank() -> metaArtUri.orEmpty()
        else -> HelixImages.absoluteUrl(baseUrl, now?.artUrl.orEmpty())
    }

    fun seekRelative(deltaMs: Long) {
        val c = controller ?: return
        if (!mediaMatchesBackend) return

        val d = durationMs.takeIf { it > 0 } ?: (now?.durationMs ?: 0L)
        val target = (c.currentPosition + deltaMs).coerceAtLeast(0L)
            .let { if (d > 0) it.coerceAtMost(d) else it }

        c.seekTo(target)
        positionMs = target
    }

    fun togglePlayPause() {
        if (playPauseInFlight) return
        playPauseInFlight = true

        scope.launch {
            try {
                // Use the same serialized command path as the notification/lockscreen controls.
                // The old in-app implementation duplicated pause/resume logic and could race
                // PlayerRealtime, leaving the tap apparently ignored while the system controls
                // still worked.
                //
                // Decide from the actual MediaController state at tap time, not the Compose
                // mirror, because the UI state can lag a Media3 transition by a frame.
                val actuallyPlaying = controller?.isPlaying ?: isPlaying

                if (actuallyPlaying) {
                    // Pause locally immediately for responsive UI/audio, then let the coordinator
                    // commit backend truth and re-sync Media3.
                    controller?.pause()
                    isPlaying = false
                    PlayerCommandCoordinator.pause(ctx)
                } else {
                    PlayerCommandCoordinator.resume(ctx)
                }

                // Re-seed the visible state from the real controller after the serialized command.
                controller?.let { c ->
                    isPlaying = c.isPlaying
                }
            } catch (_: Exception) {
                // A failed command can leave our optimistic pause state wrong. Re-read backend /
                // Media3 truth instead of requiring the user to recover via the system controls.
                runCatching {
                    PlayerCommandCoordinator.syncFromBackend(ctx, forceLoadStream = true)
                }
                controller?.let { c ->
                    isPlaying = c.isPlaying
                }
            } finally {
                playPauseInFlight = false
            }
        }
    }

    fun rateLike() {
        if (currentYtVideoId.isNullOrBlank() && currentSubsonicSongId.isNullOrBlank()) return

        val title = (
            (if (mediaMatchesBackend) metaTitle else now?.title)
                ?: now?.title
                ?: ""
        ).trim()

        val artist = (
            (if (mediaMatchesBackend) metaArtist else now?.artist)
                ?: now?.artist
                ?: ""
        ).trim()

        val album = (
            (if (mediaMatchesBackend) metaAlbum else now?.album)
                ?: now?.album
                ?: ""
        ).trim()

        val artUrl = (
            (if (mediaMatchesBackend) metaArtUri else now?.artUrl)
                ?: now?.artUrl
                ?: ""
        ).trim()

        val src = (now?.source ?: "").trim()
        val dur = if (mediaMatchesBackend && durationMs > 0) {
            durationMs
        } else {
            now?.durationMs ?: 0L
        }

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

        val title = (
            (if (mediaMatchesBackend) metaTitle else now?.title)
                ?: now?.title
                ?: ""
        ).trim()

        val artist = (
            (if (mediaMatchesBackend) metaArtist else now?.artist)
                ?: now?.artist
                ?: ""
        ).trim()

        val album = (
            (if (mediaMatchesBackend) metaAlbum else now?.album)
                ?: now?.album
                ?: ""
        ).trim()

        val artUrl = (
            (if (mediaMatchesBackend) metaArtUri else now?.artUrl)
                ?: now?.artUrl
                ?: ""
        ).trim()

        val src = (now?.source ?: "").trim()
        val dur = if (mediaMatchesBackend && durationMs > 0) {
            durationMs
        } else {
            now?.durationMs ?: 0L
        }

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

    val safeDur = durationMs.coerceAtLeast(0L)
    val safePos = (if (userSeeking) seekTargetMs else positionMs)
        .coerceIn(0L, if (safeDur > 0) safeDur else Long.MAX_VALUE)
    val remainingMs = if (safeDur > 0) (safeDur - safePos).coerceAtLeast(0L) else 0L

    Box(modifier = Modifier.fillMaxSize()) {
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

                val displayTitle = if (mediaMatchesBackend) {
                    metaTitle?.takeIf { it.isNotBlank() } ?: now?.title ?: "Nothing playing"
                } else {
                    now?.title ?: "Nothing playing"
                }

                val displayArtist = if (mediaMatchesBackend) {
                    metaArtist?.takeIf { it.isNotBlank() } ?: now?.artist
                } else {
                    now?.artist
                }

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
                                color = if (isInSubsonic) {
                                    androidx.compose.ui.graphics.Color(0xFF35C759)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        enabled = (now != null) && !ratingInFlight,
                        onClick = { rateDislike() },
                        modifier = Modifier.size(54.dp),
                    ) {
                        Icon(
                            imageVector = if (isDisliked) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
                            contentDescription = "Dislike",
                            tint = if (isDisliked) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }

                    IconButton(
                        enabled = (now != null) && !ratingInFlight,
                        onClick = { rateLike() },
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
                        if (c != null && safeDur > 0 && mediaMatchesBackend) {
                            val target = seekTargetMs.coerceIn(0L, safeDur)
                            c.seekTo(target)
                            positionMs = target
                        }
                        userSeeking = false
                    },
                    valueRange = if (safeDur > 0) 0f..safeDur.toFloat() else 0f..0f,
                    enabled = safeDur > 0 && mediaMatchesBackend,
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
                                if (!mediaMatchesBackend) {
                                    scope.launch {
                                        runCatching {
                                            HelixTransport.refreshAndSync(ctx, forceLoadStream = true)
                                            refresh()
                                        }
                                    }
                                    return@get
                                }

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
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = "Previous",
                            modifier = Modifier.size(34.dp)
                        )
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surface,
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
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = "Next",
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else if (
                    status.startsWith("Error") ||
                    status.startsWith("Failed") ||
                    status.startsWith("Not logged")
                ) {
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
