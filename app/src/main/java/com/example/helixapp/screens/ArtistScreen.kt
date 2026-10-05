package com.example.helixapp

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.helixapp.helix.HelixTrackRequests
import com.example.helixapp.playback.PlaybackActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ArtistScreen(
    browseId: String,
    onOpenAlbum: (SearchAlbum) -> Unit,
    onOpenArtist: (SearchArtist) -> Unit,
    onNavigateToNowPlaying: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }

    var loading by remember(browseId) { mutableStateOf(true) }
    var status by remember(browseId) { mutableStateOf("") }
    var artist by remember(browseId) {
        mutableStateOf(
            ArtistDetailUi(
                browseId = browseId,
                name = "",
                thumbnailUrl = "",
                mbArtistId = "",
                resolutionStatus = "unresolved",
            )
        )
    }
    var popularTracks by remember(browseId) { mutableStateOf(emptyList<SearchSong>()) }
    var albums by remember(browseId) { mutableStateOf(emptyList<SearchAlbum>()) }
    var similarArtists by remember(browseId) { mutableStateOf(emptyList<SimilarArtistUi>()) }
    var similarState by remember(browseId) { mutableStateOf("idle") }

    fun refresh() {
        if (browseId.isBlank()) {
            status = "Artist is missing a browse id"
            loading = false
            return
        }
        loading = true
        status = ""
        scope.launch {
            try {
                val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                val detailResp = withContext(Dispatchers.IO) { api.artistDetail(browseId) }
                if (!detailResp.isSuccessful) {
                    status = "Artist load failed (HTTP ${detailResp.code()})"
                    return@launch
                }
                artist = parseArtistDetail(detailResp.body().orEmpty(), browseId)

                val searchThumb = runCatching {
                    withContext(Dispatchers.IO) { api.ytmusicSearchArtists(artist.name.ifBlank { browseId }) }
                }.getOrNull()?.takeIf { it.isSuccessful }?.body().orEmpty().let { body ->
                    runCatching { parseArtists(body) }.getOrDefault(emptyList()).firstOrNull {
                        it.browseId == browseId || it.name.equals(artist.name, ignoreCase = true)
                    }?.thumbnailUrl.orEmpty()
                }
                if (searchThumb.isNotBlank()) {
                    artist = artist.copy(thumbnailUrl = searchThumb)
                }

                val (popularResp, albumsResp) = withContext(Dispatchers.IO) {
                    val a = async { api.artistPopular(browseId) }
                    val b = async { api.artistAlbums(browseId) }
                    arrayOf(a.await(), b.await())
                }
                popularTracks = if (popularResp.isSuccessful) parsePopularTracks(popularResp.body().orEmpty()) else emptyList()
                albums = if (albumsResp.isSuccessful) parseArtistAlbums(albumsResp.body().orEmpty()) else emptyList()
                similarArtists = emptyList()
                similarState = "loading"
                var similarLoaded = false
                var lastSimilarStatus = ""
                for (attempt in 0 until 15) {
                    val similarResp = withContext(Dispatchers.IO) { api.artistSimilar(browseId) }
                    if (similarResp.isSuccessful) {
                        val body = similarResp.body().orEmpty()
                        lastSimilarStatus = runCatching { JSONObject(body).optString("mb_resolution_status", "") }.getOrDefault("")
                        val parsed = parseSimilarArtists(body)
                        if (parsed.isNotEmpty()) {
                            similarArtists = parsed
                            similarState = "ready"
                            similarLoaded = true
                            break
                        }
                        similarState = when (lastSimilarStatus) {
                            "resolving", "unresolved" -> "loading"
                            "failed", "ambiguous" -> "empty"
                            "resolved" -> "empty"
                            else -> "loading"
                        }
                    } else {
                        similarState = "empty"
                    }
                    if (attempt < 14) {
                        kotlinx.coroutines.delay(1500L)
                    }
                }
                if (!similarLoaded) {
                    if (similarState == "loading") {
                        status = "Similar artists are still loading"
                    }
                }
                if (artist.name.isBlank()) {
                    status = "Artist not found"
                }
            } catch (e: Exception) {
                status = "Artist load error: ${e.javaClass.simpleName}"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(browseId) {
        refresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(artist.name.ifBlank { "Artist" }) },
                navigationIcon = {},
            )
        },
        snackbarHost = { SnackbarHost(snack) }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                ArtistHeader(
                    artist = artist,
                    loading = loading,
                    status = status,
                    onCreateStation = {
                        scope.launch {
                            try {
                                val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                                val payload = JSONObject()
                                    .put("name", "${artist.name} Radio")
                                    .put("seed_type", "artist")
                                    .put("seed_title", "")
                                    .put("seed_artist", artist.name)
                                    .put("discovery", 0.35)
                                    .put("seed_influence", 0.75)
                                    .toString()
                                    .toRequestBody("application/json; charset=utf-8".toMediaType())
                                val resp = withContext(Dispatchers.IO) { api.createStation(payload) }
                                if (!resp.isSuccessful) {
                                    snack.showNonBlocking(scope, "Create station failed (HTTP ${resp.code()})")
                                } else {
                                    snack.showNonBlocking(scope, "Created station: ${artist.name} Radio")
                                }
                            } catch (e: Exception) {
                                snack.showNonBlocking(scope, "Create station error: ${e.javaClass.simpleName}")
                            }
                        }
                    }
                )
            }

            if (popularTracks.isNotEmpty()) {
                item {
                    Text("Popular", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                items(popularTracks, key = { it.videoId.ifBlank { it.title } }) { song ->
                    ArtistPopularRow(
                        song = song,
                        onNavigateToNowPlaying = onNavigateToNowPlaying,
                    )
                }
            }

            if (albums.isNotEmpty()) {
                item {
                    Text("Albums", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(albums, key = { it.browseId.ifBlank { it.title } }) { album ->
                            AlbumCard(album = album, onOpenAlbum = onOpenAlbum)
                        }
                    }
                }
            }

            if (similarArtists.isNotEmpty() || similarState != "idle") {
                item {
                    Text("Fans also like", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                item {
                    if (similarArtists.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(similarArtists, key = { it.mbArtistId.ifBlank { it.name } }) { similar ->
                                SimilarArtistCard(
                                    artist = similar,
                                    onOpenArtist = {
                                        if (similar.browseId.isNotBlank()) {
                                            onOpenArtist(
                                                SearchArtist(
                                                    name = similar.name,
                                                    thumbnailUrl = similar.thumbnailUrl,
                                                    browseId = similar.browseId,
                                                )
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    } else {
                        Text(
                            if (similarState == "loading") "Finding similar artists…" else "No similar artists available yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistAvatarImage(
    imageUrl: String,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    AsyncImage(
        model = HelixImages.request(ctx, imageUrl),
        contentDescription = null,
        modifier = modifier
            .size(size)
            .clip(CircleShape),
    )
}

@Composable
private fun ArtistHeader(
    artist: ArtistDetailUi,
    loading: Boolean,
    status: String,
    onCreateStation: () -> Unit,
) {
    val ctx = LocalContext.current
    val baseUrl = HelixPrefs.getBaseUrl(ctx)
    val thumb = HelixImages.absoluteUrl(baseUrl, artist.thumbnailUrl)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(170.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb.isNotBlank()) {
                ArtistAvatarImage(imageUrl = thumb, size = 170.dp)
            } else if (loading) {
                CircularProgressIndicator()
            } else {
                Text(
                    text = artist.name.take(1).ifBlank { "?" },
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            artist.name.ifBlank { if (loading) "Loading artist…" else "Artist" },
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )

        Button(onClick = onCreateStation, enabled = artist.name.isNotBlank()) {
            Text("Create Station")
        }

        if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ArtistPopularRow(
    song: SearchSong,
    onNavigateToNowPlaying: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val baseUrl = HelixPrefs.getBaseUrl(ctx)
    val thumb = HelixImages.absoluteUrl(baseUrl, song.thumbnailUrl)

    fun playSong() {
        scope.launchPlaybackAction(
            failureAction = "Play",
            overlayMessage = "Starting track…",
            onSuccess = onNavigateToNowPlaying,
        ) {
            PlaybackActions.playTrack(
                ctx,
                HelixTrackRequests.playOrQueueBodyFromSearchSong(HelixPrefs.getBaseUrl(ctx), song),
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                playSong()
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = HelixImages.request(ctx, thumb),
            contentDescription = null,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = song.album.ifBlank { song.artist }
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box {
            var expanded by remember(song.videoId) { mutableStateOf(false) }
            IconButton(onClick = { expanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More")
            }
            HelixTrackOverflowMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                onPlay = {
                    expanded = false
                    playSong()
                },
                onPlayNext = {
                    expanded = false
                    scope.launchPlaybackAction(failureAction = "Play next", successMessage = "Playing next: ${song.title}") {
                        PlaybackActions.playNext(
                            ctx,
                            HelixTrackRequests.playOrQueueBodyFromSearchSong(HelixPrefs.getBaseUrl(ctx), song),
                        )
                    }
                },
                onAddToQueue = {
                    expanded = false
                    scope.launchPlaybackAction(failureAction = "Queue", successMessage = "Queued: ${song.title}") {
                        PlaybackActions.queueTrack(
                            ctx,
                            HelixTrackRequests.playOrQueueBodyFromSearchSong(HelixPrefs.getBaseUrl(ctx), song),
                        )
                    }
                },
                onAddToSubsonic = {
                    expanded = false
                    scope.launch {
                        try {
                            val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                            val payload = JSONObject().apply {
                                put("yt_video_id", song.videoId)
                                put("title", song.title)
                                put("artist", song.artist)
                                if (song.album.isNotBlank()) put("album", song.album)
                                if (thumb.isNotBlank()) put("art_url", thumb)
                            }
                            val resp = withContext(Dispatchers.IO) { api.subsonicAddTrack(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())) }
                            if (!resp.isSuccessful) {
                                UserMessages.show("Add to Subsonic failed (HTTP ${resp.code()})")
                                return@launch
                            }
                            UserMessages.show("Added to Subsonic: ${song.title}")
                        } catch (e: Exception) {
                            UserMessages.show("Add to Subsonic error: ${e.javaClass.simpleName}")
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun AlbumCard(
    album: SearchAlbum,
    onOpenAlbum: (SearchAlbum) -> Unit,
) {
    val ctx = LocalContext.current
    val baseUrl = HelixPrefs.getBaseUrl(ctx)
    val thumb = HelixImages.absoluteUrl(baseUrl, album.thumbnailUrl)

    Column(
        modifier = Modifier
            .size(width = 144.dp, height = 182.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable { onOpenAlbum(album) }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AsyncImage(
            model = HelixImages.request(ctx, thumb),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .height(124.dp)
                .clip(RoundedCornerShape(14.dp)),
            contentScale = ContentScale.Crop,
        )
        Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            album.year.ifBlank { album.artist },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SimilarArtistCard(
    artist: SimilarArtistUi,
    onOpenArtist: () -> Unit,
) {
    val ctx = LocalContext.current
    val baseUrl = HelixPrefs.getBaseUrl(ctx)
    val thumb = HelixImages.absoluteUrl(baseUrl, artist.thumbnailUrl)

    Column(
        modifier = Modifier
            .size(width = 144.dp, height = 176.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable(enabled = artist.browseId.isNotBlank()) { onOpenArtist() }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(118.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb.isNotBlank()) {
                ArtistAvatarImage(
                    imageUrl = thumb,
                    size = 118.dp,
                    modifier = Modifier.matchParentSize()
                )
            } else {
                Text(
                    artist.name.take(1).ifBlank { "?" },
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            artist.name,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
