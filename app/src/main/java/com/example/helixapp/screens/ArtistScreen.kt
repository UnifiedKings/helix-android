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
import com.example.helixapp.playback.HelixTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private data class ArtistDetailUi(
    val browseId: String,
    val name: String,
    val thumbnailUrl: String,
    val mbArtistId: String,
    val resolutionStatus: String,
)

private data class SimilarArtistUi(
    val name: String,
    val mbArtistId: String = "",
    val browseId: String = "",
    val thumbnailUrl: String = "",
)

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
                    parseSearchArtists(body).firstOrNull {
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
    val snack = remember { SnackbarHostState() }
    val baseUrl = HelixPrefs.getBaseUrl(ctx)
    val thumb = HelixImages.absoluteUrl(baseUrl, song.thumbnailUrl)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                scope.launch {
                    try {
                        HelixPrefs.setLastStationName(ctx, null)
                        val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                        val payload = HelixTrackRequests.playOrQueueBodyFromSearchSong(HelixPrefs.getBaseUrl(ctx), song)
                        val resp = withContext(Dispatchers.IO) { api.playTrack(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())) }
                        if (!resp.isSuccessful) {
                            snack.showNonBlocking(scope, "Play failed (HTTP ${resp.code()})")
                            return@launch
                        }
                        HelixTransport.refreshAndPlayCurrent(ctx)
                    } catch (e: Exception) {
                        snack.showNonBlocking(scope, "Play error: ${e.javaClass.simpleName}")
                    } finally {
                        onNavigateToNowPlaying()
                    }
                }
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
                    scope.launch {
                        try {
                            HelixPrefs.setLastStationName(ctx, null)
                            val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                            val payload = HelixTrackRequests.playOrQueueBodyFromSearchSong(HelixPrefs.getBaseUrl(ctx), song)
                            val resp = withContext(Dispatchers.IO) { api.playTrack(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())) }
                            if (!resp.isSuccessful) {
                                snack.showNonBlocking(scope, "Play failed (HTTP ${resp.code()})")
                                return@launch
                            }
                            HelixTransport.refreshAndPlayCurrent(ctx)
                        } catch (e: Exception) {
                            snack.showNonBlocking(scope, "Play error: ${e.javaClass.simpleName}")
                        } finally {
                            onNavigateToNowPlaying()
                        }
                    }
                },
                onAddToQueue = {
                    expanded = false
                    scope.launch {
                        try {
                            val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                            val payload = HelixTrackRequests.playOrQueueBodyFromSearchSong(HelixPrefs.getBaseUrl(ctx), song)
                            val resp = withContext(Dispatchers.IO) { api.queueAppendTrack(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())) }
                            if (!resp.isSuccessful) {
                                snack.showNonBlocking(scope, "Queue failed (HTTP ${resp.code()})")
                                return@launch
                            }
                            snack.showNonBlocking(scope, "Queued: ${song.title}")
                        } catch (e: Exception) {
                            snack.showNonBlocking(scope, "Queue error: ${e.javaClass.simpleName}")
                        }
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
                                snack.showNonBlocking(scope, "Add to Subsonic failed (HTTP ${resp.code()})")
                                return@launch
                            }
                            snack.showNonBlocking(scope, "Added to Subsonic: ${song.title}")
                        } catch (e: Exception) {
                            snack.showNonBlocking(scope, "Add to Subsonic error: ${e.javaClass.simpleName}")
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

private fun parseSearchArtists(json: String): List<SearchArtist> {
    if (json.isBlank()) return emptyList()
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
    val artists = root.optJSONArray("artists") ?: JSONArray()
    val out = ArrayList<SearchArtist>(artists.length())
    for (i in 0 until artists.length()) {
        val o = artists.optJSONObject(i) ?: continue
        val name = o.optString("name")
        val thumb = o.optString("thumbnail_url", o.optString("thumbnail", ""))
        val id = o.optString("browse_id", o.optString("artist_id", ""))
        if (name.isBlank()) continue
        out += SearchArtist(name = name, thumbnailUrl = thumb, browseId = id)
    }
    return out
}

private fun parseArtistDetail(json: String, browseId: String): ArtistDetailUi {
    val root = JSONObject(json)
    return ArtistDetailUi(
        browseId = root.optString("browse_id", root.optString("artist_id", browseId)).ifBlank { browseId },
        name = root.optString("name", root.optString("artist", "")),
        thumbnailUrl = root.optString("thumbnail_url", root.optString("thumbnail", "")),
        mbArtistId = root.optString("mb_artist_id", ""),
        resolutionStatus = root.optString("mb_resolution_status", "unresolved"),
    )
}

private fun parsePopularTracks(json: String): List<SearchSong> {
    val root = JSONObject(json)
    val arr = root.optJSONArray("tracks") ?: JSONArray()
    val out = ArrayList<SearchSong>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        out.add(
            SearchSong(
                title = o.optString("title", ""),
                artist = o.optString("artist", root.optString("artist_name", "")),
                album = o.optString("album", ""),
                thumbnailUrl = o.optString("thumbnail_url", o.optString("thumbnail", "")),
                videoId = o.optString("video_id", o.optString("videoId", "")),
            )
        )
    }
    return out
}

private fun parseArtistAlbums(json: String): List<SearchAlbum> {
    val root = JSONObject(json)
    val arr = root.optJSONArray("albums") ?: JSONArray()
    val out = ArrayList<SearchAlbum>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        out.add(
            SearchAlbum(
                title = o.optString("title", ""),
                artist = o.optString("artist", root.optString("artist_name", "")),
                year = o.optString("year", ""),
                thumbnailUrl = o.optString("thumbnail_url", o.optString("thumbnail", "")),
                browseId = o.optString("browse_id", o.optString("browseId", "")),
            )
        )
    }
    return out
}

private fun parseSimilarArtists(json: String): List<SimilarArtistUi> {
    val root = JSONObject(json)
    val arr = root.optJSONArray("similar_artists") ?: JSONArray()
    val out = ArrayList<SimilarArtistUi>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val name = o.optString("name")
            .ifBlank { o.optString("artist_name") }
            .ifBlank { o.optString("artist") }
        out.add(
            SimilarArtistUi(
                name = name,
                mbArtistId = o.optString("mb_artist_id", o.optString("artist_mbid", "")),
                browseId = o.optString("browse_id", o.optString("yt_browse_id", "")),
                thumbnailUrl = o.optString("thumbnail_url", o.optString("thumbnail", "")),
            )
        )
    }
    return out.filter { it.name.isNotBlank() || it.browseId.isNotBlank() || it.mbArtistId.isNotBlank() }
}
