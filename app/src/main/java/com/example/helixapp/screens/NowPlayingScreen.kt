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
import androidx.compose.runtime.collectAsState
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
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
import com.example.helixapp.playback.PlaybackController
import com.example.helixapp.data.RatedTrack
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.helixapp.playback.PlayerCommandCoordinator
import com.example.helixapp.playback.DevicePlayback
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How many 2-second checks to make after "Add to Subsonic" before giving up (~2 minutes). */
@Composable
fun NowPlayingScreen(viewModel: NowPlayingViewModel = helixViewModel { NowPlayingViewModel(it) }) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()
    // False when the phone is only a remote: the play state then comes from the backend,
    // since the local player stays empty.
    val playOnDevice by remember { DevicePlayback.isEnabled(ctx); DevicePlayback.enabled }.collectAsState()
    val now = state.now
    val activeStationName = state.activeStationName

    var metaTitle by remember { mutableStateOf<String?>(null) }
    var metaArtist by remember { mutableStateOf<String?>(null) }
    var metaAlbum by remember { mutableStateOf<String?>(null) }
    var metaArtUri by remember { mutableStateOf<String?>(null) }
    var metaMediaId by remember { mutableStateOf<String?>(null) }

    var isPlaying by remember { mutableStateOf(false) }
    var playPauseInFlight by remember { mutableStateOf(false) }

    val isLiked = state.liked
    val isDisliked = state.disliked
    val ratingInFlight = state.ratingInFlight
    val isInSubsonic = state.inSubsonic == true
    val subsonicAvailabilityKnown = state.inSubsonic != null
    val addToSubsonicPending = state.addToSubsonicPending

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

    // Media3's listener is the source of truth for isPlaying once connected; the backend value
    // only seeds it (or drives it entirely when this phone plays no audio).
    LaunchedEffect(state.backendPlaying, controller, playOnDevice) {
        if (controller == null || !playOnDevice) isPlaying = state.backendPlaying
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
                if (playOnDevice) isPlaying = isPlayingNow
                if (playPauseInFlight) playPauseInFlight = false
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                val md = mediaItem?.mediaMetadata
                metaMediaId = mediaItem?.mediaId
                metaTitle = md?.title?.toString()
                metaArtist = md?.artist?.toString()
                metaAlbum = md?.albumTitle?.toString()
                metaArtUri = md?.artworkUri?.toString()
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

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(controller, now?.queueItemId, metaMediaId) {
        val c = controller ?: return@LaunchedEffect

        // Only poll the playback position while the app is visible; the composition stays
        // alive in the background, and this loop would otherwise keep waking every 500 ms.
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
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
                val actuallyPlaying = if (playOnDevice) controller?.isPlaying ?: isPlaying else isPlaying

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
                if (playOnDevice) controller?.let { c -> isPlaying = c.isPlaying }
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

    /** The song as shown: Media3's metadata when it matches the backend's current song. */
    fun ratedTrack(): RatedTrack? {
        val current = now ?: return null
        fun pick(local: String?, backend: String) = ((if (mediaMatchesBackend) local else backend) ?: backend).trim()
        return RatedTrack(
            title = pick(metaTitle, current.title),
            artist = pick(metaArtist, current.artist),
            album = pick(metaAlbum, current.album),
            durationMs = if (mediaMatchesBackend && durationMs > 0) durationMs else current.durationMs,
            artUrl = pick(metaArtUri, current.artUrl),
            source = current.source.trim(),
            ytVideoId = current.ytVideoId?.takeIf { it.isNotBlank() },
            subsonicSongId = current.subsonicSongId?.takeIf { it.isNotBlank() },
        )
    }

    fun rateLike() { ratedTrack()?.let(viewModel::toggleLike) }

    fun rateDislike() { ratedTrack()?.let(viewModel::toggleDislike) }

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

                if (!playOnDevice && now != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Remote control: audio plays on your other Helix devices",
                        style = MaterialTheme.typography.labelMedium,
                        color = HelixMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
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
                                        onClick = viewModel::addToSubsonic,
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

                    SleepTimerButton()

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
                            if (!playOnDevice) {
                                scope.launchPlaybackAction(failureAction = "Previous") {
                                    PlayerCommandCoordinator.previous(ctx)
                                }
                                return@IconButton
                            }
                            PlaybackController.get(ctx) { c ->
                                if (!mediaMatchesBackend) {
                                    scope.launch {
                                        runCatching {
                                            PlayerCommandCoordinator.syncFromBackend(ctx, forceLoadStream = true)
                                            viewModel.refresh()
                                        }
                                    }
                                    return@get
                                }

                                val elapsedMs = c.currentPosition
                                if (elapsedMs > 3_000L) {
                                    c.seekTo(0)
                                    return@get
                                }

                                scope.launchPlaybackAction(failureAction = "Previous") {
                                    PlayerCommandCoordinator.previous(ctx)
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
                            scope.launchPlaybackAction(failureAction = "Next") {
                                PlayerCommandCoordinator.next(ctx)
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

                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else if (state.error != null) {
                    Text(
                        state.error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
