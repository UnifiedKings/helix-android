package com.example.helixapp

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.Image
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.helixapp.helix.HelixTrackRequests
import com.example.helixapp.playback.HelixTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import com.example.helixapp.ui.theme.HelixAccent
import com.example.helixapp.ui.theme.HelixBackground
import com.example.helixapp.ui.theme.HelixBorder
import com.example.helixapp.ui.theme.HelixMuted

private data class AlbumTrack(
    val pos: Int,
    val title: String,
    val artist: String,
    val durationSeconds: Int,
    val videoId: String,
)

@Composable
fun AlbumScreen(
    browseId: String,
    onNavigateToNowPlaying: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val snack = remember { SnackbarHostState() }

    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }

    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    var thumbUrl by remember { mutableStateOf("") }
    var tracks by remember { mutableStateOf(emptyList<AlbumTrack>()) }
    val subsonicSongAvailable = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }

    fun absoluteThumb(baseUrl: String): String = HelixImages.absoluteUrl(baseUrl, thumbUrl)

    fun resolveSubsonicAvailability(currentTracks: List<AlbumTrack>, albumTitle: String, albumArtist: String) {
        subsonicSongAvailable.clear()
        if (currentTracks.isEmpty()) return
        if (HelixPrefs.getSessionToken(ctx).isNullOrBlank()) return

        scope.launch {
            try {
                val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                val payload = JSONObject().apply {
                    put("songs", JSONArray().apply {
                        for (track in currentTracks) {
                            val keyId = track.videoId.trim()
                            if (keyId.isBlank()) continue
                            put(JSONObject().apply {
                                put("key", "song:" + keyId)
                                put("title", track.title)
                                put("artist", track.artist.trim().ifBlank { albumArtist.trim() })
                                if (albumTitle.isNotBlank()) put("album", albumTitle)
                            })
                        }
                    })
                    put("albums", JSONArray())
                }

                val rb = payload.toString().toRequestBody("application/json".toMediaType())
                val resp = withContext(Dispatchers.IO) { api.subsonicResolve(rb) }
                if (!resp.isSuccessful) return@launch

                val body = resp.body().orEmpty()
                val obj = JSONObject(body)
                val songsObj = obj.optJSONObject("songs") ?: return@launch
                songsObj.keys().forEach { key ->
                    val entry = songsObj.optJSONObject(key)
                    subsonicSongAvailable[key] = entry?.optBoolean("available", false) ?: false
                }
            } catch (_: Exception) {
                // Best-effort only. If resolve fails, we just don't show badges.
            }
        }
    }

    fun loadAlbum() {
        if (browseId.isBlank()) {
            err = "Missing album id"
            loading = false
            return
        }
        loading = true
        err = null
        scope.launch {
            try {
                val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                val resp = withContext(Dispatchers.IO) { api.albumView(browseId) }
                if (!resp.isSuccessful) {
                    err = "Failed (HTTP ${resp.code()})"
                    return@launch
                }
                val body = resp.body().orEmpty()
                val root = JSONObject(body)

                title = root.optString("title", "")
                artist = listOf(
                    root.optString("artist", ""),
                    root.optString("artist_name", ""),
                    root.optString("artists", ""),
                    root.optString("album_artist", ""),
                    root.optString("albumArtist", ""),
                ).map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
                year = root.optString("year", "")
                thumbUrl = root.optString("thumbnail_url", "")

                val t = root.optJSONArray("tracks") ?: JSONArray()
                val out = ArrayList<AlbumTrack>(t.length())
                for (i in 0 until t.length()) {
                    val o = t.optJSONObject(i) ?: continue
                    val pos = o.optInt("pos", i + 1)
                    val tt = o.optString("title", "")
                    if (tt.isBlank()) continue
                    val ta = listOf(
                        o.optString("artist", ""),
                        o.optString("artist_name", ""),
                        o.optString("artists", ""),
                        o.optString("album_artist", ""),
                        o.optString("albumArtist", ""),
                        artist,
                    ).map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
                    val dur = o.optInt("duration_seconds", 0)
                    val vid = listOf(
                        o.optString("video_id", ""),
                        o.optString("videoId", "")
                    ).map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
                    out.add(AlbumTrack(pos = pos, title = tt, artist = ta, durationSeconds = dur, videoId = vid))
                }
                tracks = out
                resolveSubsonicAvailability(out, title, artist)
            } catch (e: Exception) {
                err = "Error: ${e.javaClass.simpleName}: ${e.message}"
            } finally {
                loading = false
            }
        }
    }

    // Initial load / refresh when browseId changes
    androidx.compose.runtime.LaunchedEffect(browseId) {
        loadAlbum()
    }

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

    fun playSingle(track: AlbumTrack) {
        showLoadingOverlay("Starting track…")

        scope.launch {
            try {
                // Leaving station mode (if any) when directly starting a track.
                HelixPrefs.setLastStationName(ctx, null)
                val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                val bodyJson = HelixTrackRequests.playOrQueueBodyFromSearchSong(
                    HelixPrefs.getBaseUrl(ctx),
                    trackToSearchSong(track)
                )
                val body = bodyJson.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())

                val resp = withContext(Dispatchers.IO) { api.playTrack(body) }
                if (!resp.isSuccessful) {
                    snack.showNonBlocking(scope, "Play failed (HTTP ${resp.code()})")
                    return@launch
                }

                showLoadingOverlay("Loading now playing…")
                HelixTransport.refreshAndPlayCurrent(ctx)
            } catch (t: Throwable) {
                snack.showNonBlocking(scope, "Play error: ${t.javaClass.simpleName}")
            } finally {
                onNavigateToNowPlaying()
                hideLoadingOverlay()
            }
        }
    }

    fun queueSingle(track: AlbumTrack) {
        scope.launch {
            runCatching {
                val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                val bodyJson = HelixTrackRequests.playOrQueueBodyFromSearchSong(HelixPrefs.getBaseUrl(ctx), trackToSearchSong(track))
                val body = bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val resp = withContext(Dispatchers.IO) { api.queueAppendTrack(body) }
                if (!resp.isSuccessful) {
                    snack.showNonBlocking(scope, "Queue failed (HTTP ${resp.code()})")
                    return@launch
                }
                snack.showNonBlocking(scope, "Queued: ${track.title}")
            }.onFailure {
                snack.showNonBlocking(scope, "Queue error: ${it.javaClass.simpleName}")
            }
        }
    }

    fun addSingleToSubsonic(track: AlbumTrack) {
        scope.launch {
            try {
                val trackArtist = track.artist.trim()
                val albumArtist = artist.trim().ifBlank { trackArtist }
                if (trackArtist.isBlank() && albumArtist.isBlank()) {
                    snack.showNonBlocking(scope, "Missing artist metadata for ${track.title}")
                    return@launch
                }
                if (track.videoId.isBlank()) {
                    snack.showNonBlocking(scope, "Missing video id for ${track.title}")
                    return@launch
                }

                val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                val payload = JSONObject().apply {
                    put("yt_video_id", track.videoId)
                    put("title", track.title)
                    put("artist", trackArtist.ifBlank { albumArtist })
                    put("album_artist", albumArtist.ifBlank { trackArtist })
                    if (title.isNotBlank()) put("album", title)
                    val art = absoluteThumb(HelixPrefs.getBaseUrl(ctx))
                    if (art.isNotBlank()) put("art_url", art)
                }
                val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val resp = withContext(Dispatchers.IO) { api.subsonicAddTrack(body) }
                if (!resp.isSuccessful) {
                    val errorText = runCatching { resp.errorBody()?.string().orEmpty() }.getOrDefault("")
                    val suffix = errorText.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""
                    snack.showNonBlocking(scope, "Add to Subsonic failed (HTTP ${resp.code()})$suffix")
                    return@launch
                }
                snack.showNonBlocking(scope, "Added to Subsonic: ${track.title}")
            } catch (e: Exception) {
                snack.showNonBlocking(scope, "Add to Subsonic error: ${e.javaClass.simpleName}")
            }
        }
    }

    fun playAlbum() {
        if (tracks.isEmpty()) return

        showLoadingOverlay("Starting album…")

        scope.launch {
            try {
                HelixPrefs.setLastStationName(ctx, null)
                val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                val baseUrl = HelixPrefs.getBaseUrl(ctx)
                val payloadJson = JSONObject().apply {
                    put("browse_id", browseId)
                    if (title.isNotBlank()) put("title", title)
                    if (artist.isNotBlank()) put("artist", artist)
                    val art = absoluteThumb(baseUrl)
                    if (art.isNotBlank()) put("art_url", art)
                }
                val body = payloadJson.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())

                val resp = withContext(Dispatchers.IO) { api.playAlbum(body) }
                if (!resp.isSuccessful) {
                    snack.showNonBlocking(scope, "Play failed (HTTP ${resp.code()})")
                    return@launch
                }

                showLoadingOverlay("Loading now playing…")
                HelixTransport.refreshAndPlayCurrent(ctx)
            } catch (t: Throwable) {
                snack.showNonBlocking(scope, "Play error: ${t.javaClass.simpleName}")
            } finally {
                onNavigateToNowPlaying()
                hideLoadingOverlay()
            }
        }
    }

    fun queueAlbum() {
        if (tracks.isEmpty()) return
        scope.launch {
            runCatching {
                val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                val baseUrl = HelixPrefs.getBaseUrl(ctx)
                val payloadJson = JSONObject().apply {
                    put("browse_id", browseId)
                    if (title.isNotBlank()) put("title", title)
                    if (artist.isNotBlank()) put("artist", artist)
                    val art = absoluteThumb(baseUrl)
                    if (art.isNotBlank()) put("art_url", art)
                }
                val body = payloadJson.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())

                val resp = withContext(Dispatchers.IO) { api.queueAppendAlbum(body) }
                if (!resp.isSuccessful) {
                    snack.showNonBlocking(scope, "Queue failed (HTTP ${resp.code()})")
                    return@launch
                }
                snack.showNonBlocking(scope, "Album queued")
            }.onFailure {
                snack.showNonBlocking(scope, "Queue error: ${it.javaClass.simpleName}")
            }
        }
    }

    val baseUrl = HelixPrefs.getBaseUrl(ctx)
    val albumDurationSeconds = tracks.sumOf { it.durationSeconds.coerceAtLeast(0) }
    val identifiableTracks = tracks.filter { it.videoId.isNotBlank() }
    val albumFullyInSubsonic = identifiableTracks.isNotEmpty() &&
        identifiableTracks.all { subsonicSongAvailable["song:" + it.videoId] == true }

    Scaffold(
        containerColor = HelixBackground,
        snackbarHost = { SnackbarHost(snack) },
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
                        onQueue = { queueSingle(t) },
                        onAddToSubsonic = { addSingleToSubsonic(t) },
                        subsonicAvailable = subsonicSongAvailable["song:" + t.videoId] == true,
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
                    DropdownMenuItem(
                        text = { Text("Add to Queue") },
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


