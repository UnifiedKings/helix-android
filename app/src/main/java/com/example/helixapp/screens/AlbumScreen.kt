package com.example.helixapp

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.helixapp.helix.HelixTrackRequests
import com.example.helixapp.playback.PlaybackActions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.json.JSONObject
import com.example.helixapp.ui.theme.HelixAccent
import com.example.helixapp.ui.theme.HelixBackground
import com.example.helixapp.ui.theme.HelixBorder
import com.example.helixapp.ui.theme.HelixMuted

@Composable
fun AlbumScreen(
    browseId: String,
    onNavigateToNowPlaying: () -> Unit = {},
    viewModel: AlbumViewModel = helixViewModel(key = "album:$browseId") { AlbumViewModel(it, browseId) },
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val state by viewModel.state.collectAsStateWithLifecycle()
    val loading = state.loading
    val err = state.error
    val title = state.album?.title.orEmpty()
    val artist = state.album?.artist.orEmpty()
    val year = state.album?.year.orEmpty()
    val thumbUrl = state.album?.thumbnailUrl.orEmpty()
    val tracks = state.tracks

    fun absoluteThumb(baseUrl: String): String = HelixImages.absoluteUrl(baseUrl, thumbUrl)

    fun trackToSearchSong(track: AlbumTrack): SearchSong {
        return SearchSong(
            title = track.title,
            // For album track actions, prefer the album-level artist for consistency.
            artist = artist.ifBlank { track.artist },
            album = title,
            thumbnailUrl = thumbUrl,
            videoId = track.videoId,
        )
    }

    fun trackPayload(track: AlbumTrack): JSONObject =
        HelixTrackRequests.playOrQueueBodyFromSearchSong(HelixPrefs.getBaseUrl(ctx), trackToSearchSong(track))

    fun albumPayload(): JSONObject = JSONObject().apply {
        put("browse_id", browseId)
        if (title.isNotBlank()) put("title", title)
        if (artist.isNotBlank()) put("artist", artist)
        val art = absoluteThumb(HelixPrefs.getBaseUrl(ctx))
        if (art.isNotBlank()) put("art_url", art)
    }

    fun playSingle(track: AlbumTrack) {
        scope.launchPlaybackAction(
            failureAction = "Play",
            overlayMessage = "Starting track…",
            onSuccess = onNavigateToNowPlaying,
        ) {
            PlaybackActions.playTrack(ctx, trackPayload(track))
        }
    }

    fun playNextSingle(track: AlbumTrack) {
        scope.launchPlaybackAction(failureAction = "Play next", successMessage = "Playing next: ${track.title}") {
            PlaybackActions.playNext(ctx, trackPayload(track))
        }
    }

    fun queueSingle(track: AlbumTrack) {
        scope.launchPlaybackAction(failureAction = "Queue", successMessage = "Queued: ${track.title}") {
            PlaybackActions.queueTrack(ctx, trackPayload(track))
        }
    }

    fun addSingleToSubsonic(track: AlbumTrack) {
        viewModel.addToSubsonic(track, artUrl = absoluteThumb(HelixPrefs.getBaseUrl(ctx)))
    }

    fun playAlbum() {
        if (tracks.isEmpty()) return
        scope.launchPlaybackAction(
            failureAction = "Play",
            overlayMessage = "Starting album…",
            onSuccess = onNavigateToNowPlaying,
        ) {
            PlaybackActions.playAlbum(ctx, albumPayload())
        }
    }

    fun queueAlbum() {
        if (tracks.isEmpty()) return
        scope.launchPlaybackAction(failureAction = "Queue", successMessage = "Album queued") {
            PlaybackActions.queueAlbum(ctx, albumPayload())
        }
    }

    val baseUrl = HelixPrefs.getBaseUrl(ctx)
    val albumDurationSeconds = tracks.sumOf { it.durationSeconds.coerceAtLeast(0) }
    val albumFullyInSubsonic = state.fullyInSubsonic

    Scaffold(
        containerColor = HelixBackground,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            item {
                AlbumHero(
                    title = title.ifBlank { "Album" },
                    artist = artist,
                    year = year,
                    trackCount = tracks.size,
                    durationSeconds = albumDurationSeconds,
                    artworkUrl = HelixImages.absoluteUrl(baseUrl, thumbUrl),
                    inSubsonic = albumFullyInSubsonic,
                    loading = loading,
                    error = err,
                    onBack = { back?.onBackPressed() },
                    onPlay = { playAlbum() },
                    onQueue = { queueAlbum() },
                    actionsEnabled = tracks.isNotEmpty() && !loading,
                )
            }

            if (tracks.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "TRACKS",
                            style = MaterialTheme.typography.labelMedium,
                            color = HelixMuted,
                        )
                        Text(
                            text = buildString {
                                append(tracks.size)
                                append(if (tracks.size == 1) " TRACK" else " TRACKS")
                                if (albumDurationSeconds > 0) {
                                    append(" • ")
                                    append(formatDuration(albumDurationSeconds))
                                }
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = HelixMuted,
                        )
                    }
                    HorizontalDivider(color = HelixBorder)
                }

                items(tracks) { t ->
                    AlbumTrackRow(
                        track = t,
                        onPlay = { playSingle(t) },
                        onPlayNext = { playNextSingle(t) },
                        onQueue = { queueSingle(t) },
                        onAddToSubsonic = { addSingleToSubsonic(t) },
                        subsonicAvailable = state.trackInSubsonic(t),
                    )
                }
            } else if (!loading && err == null) {
                item {
                    Text(
                        text = "No tracks",
                        modifier = Modifier.padding(18.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

@Composable
private fun AlbumHero(
    title: String,
    artist: String,
    year: String,
    trackCount: Int,
    durationSeconds: Int,
    artworkUrl: String,
    inSubsonic: Boolean,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onQueue: () -> Unit,
    actionsEnabled: Boolean,
) {
    val ctx = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(500.dp),
    ) {
        if (artworkUrl.isNotBlank()) {
            AsyncImage(
                model = HelixImages.request(ctx, artworkUrl),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.28f),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.22f),
                            HelixBackground.copy(alpha = 0.56f),
                            HelixBackground,
                        )
                    )
                )
        )

        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 8.dp, top = 8.dp),
        ) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (artworkUrl.isNotBlank()) {
                AsyncImage(
                    model = HelixImages.request(ctx, artworkUrl),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(194.dp)
                        .clip(RoundedCornerShape(14.dp)),
                )
            } else {
                Spacer(Modifier.size(194.dp))
            }

            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }

            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error)
            }

            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (artist.isNotBlank()) {
                Text(
                    text = artist,
                    style = MaterialTheme.typography.titleMedium,
                    color = HelixAccent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val meta = buildList {
                if (year.isNotBlank()) add(year)
                if (trackCount > 0) add("$trackCount ${if (trackCount == 1) "track" else "tracks"}")
                if (durationSeconds > 0) add(formatDuration(durationSeconds))
            }.joinToString(" • ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (inSubsonic) {
                Text(
                    text = "In Subsonic",
                    style = MaterialTheme.typography.labelMedium,
                    color = HelixAccent,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onPlay,
                    enabled = actionsEnabled,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = HelixAccent),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text("Play", modifier = Modifier.padding(start = 6.dp))
                }
                OutlinedButton(
                    onClick = onQueue,
                    enabled = actionsEnabled,
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, HelixBorder),
                ) {
                    Icon(Icons.Default.QueueMusic, contentDescription = null)
                    Text("+ Queue", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun AlbumTrackRow(
    track: AlbumTrack,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onQueue: () -> Unit,
    onAddToSubsonic: () -> Unit,
    subsonicAvailable: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPlay() }
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = track.pos.toString(),
                modifier = Modifier.size(24.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = track.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (track.durationSeconds > 0) {
                Text(
                    text = formatDuration(track.durationSeconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Box {
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = HelixMuted)
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    shape = RoundedCornerShape(12.dp),
                ) {
                    DropdownMenuItem(
                        text = { Text("Play") },
                        leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                        onClick = {
                            expanded = false
                            onPlay()
                        }
                    )
                    PlayNextMenuItem(onClick = {
                        expanded = false
                        onPlayNext()
                    })
                    DropdownMenuItem(
                        text = { Text("Add to queue") },
                        leadingIcon = { Icon(Icons.Default.QueueMusic, contentDescription = null) },
                        onClick = {
                            expanded = false
                            onQueue()
                        }
                    )
                    if (!subsonicAvailable) {
                        DropdownMenuItem(
                            text = { Text("Add to Subsonic") },
                            leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                            onClick = {
                                expanded = false
                                onAddToSubsonic()
                            }
                        )
                    }
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 54.dp),
            color = HelixBorder,
        )
    }
}

private fun formatDuration(seconds: Int): String {
    val s = if (seconds < 0) 0 else seconds
    val m = s / 60
    val r = s % 60
    return "%d:%02d".format(m, r)
}


