package com.example.helixapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.helixapp.ui.theme.HelixAccent
import com.example.helixapp.ui.theme.HelixBorder
import com.example.helixapp.ui.theme.HelixSurfaceRaised
import com.example.helixapp.helix.HelixTrackRequests
import com.example.helixapp.playback.HelixTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
private sealed class SearchFilter(val label: String) {
    data object Songs : SearchFilter("Songs")
    data object Artists : SearchFilter("Artists")
    data object Albums : SearchFilter("Albums")
    data object All : SearchFilter("All")
}

/**
 * Search screen.
 *
 * - Normal tab usage: addOnlyMode=false (shows Play / +Queue actions on songs)
 * - Add-to-playlist overlay: addOnlyMode=true (songs-only, shows only "Add")
 *
 * If you pass a SnackbarHostState from the overlay, notifications will render ABOVE the overlay
 * instead of behind it.
 */
@Composable
fun SearchScreen(
    onOpenAlbum: (SearchAlbum) -> Unit,
    onOpenArtist: (SearchArtist) -> Unit = {},
    onAddToPlaylist: ((SearchSong) -> Unit)? = null,
    addOnlyMode: Boolean = false,
    snackbarHostState: SnackbarHostState? = null,
    onNavigateToNowPlaying: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Idle") }
    var loading by remember { mutableStateOf(false) }
    var songResults by remember { mutableStateOf(emptyList<SearchSong>()) }
    var albumResults by remember { mutableStateOf(emptyList<SearchAlbum>()) }
    var artistResults by remember { mutableStateOf(emptyList<SearchArtist>()) }
    var lastSearchedTerm by remember { mutableStateOf("") }
    var filter by remember {
        mutableStateOf<SearchFilter>(SearchFilter.Songs)
    }

    val albumArtistByTitle = remember(albumResults) {
        albumResults
            .filter { it.title.isNotBlank() && it.artist.isNotBlank() }
            .associate { it.title.trim().lowercase() to it.artist.trim() }
    }

    val snack = snackbarHostState ?: remember { SnackbarHostState() }
    val appCtx = ctx.applicationContext
    var recents by remember { mutableStateOf(RecentSearchPlay.get(appCtx)) }

    val subsonicSongAvailable = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    val subsonicAlbumAvailable = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    var resolveVersion by remember { mutableStateOf(0) }

    fun clearToRecents() {
        loading = false
        songResults = emptyList()
        albumResults = emptyList()
        artistResults = emptyList()
        status = "Idle"
        lastSearchedTerm = ""
        if (!addOnlyMode) recents = RecentSearchPlay.get(appCtx)
    }

    fun resolveSubsonicAvailability(
        songs: List<SearchSong>,
        albums: List<SearchAlbum>,
        baseUrl: String,
        version: Int,
        limit: Int = 25,
    ) {
        if (songs.isEmpty() && albums.isEmpty()) return
        if (HelixPrefs.getSessionToken(ctx).isNullOrBlank()) return

        val songSlice = songs.take(limit)
        val albumSlice = albums.take(limit)

        scope.launch {
            try {
                val api = HelixClient.create(ctx, baseUrl)
                val payload = JSONObject().apply {
                    put("songs", JSONArray().apply {
                        for (s in songSlice) {
                            put(JSONObject().apply {
                                put("key", "song:" + s.videoId)
                                put("title", s.title)
                                put("artist", s.artist)
                            })
                        }
                    })
                    put("albums", JSONArray().apply {
                        for (a in albumSlice) {
                            put(JSONObject().apply {
                                put("key", "album:" + a.browseId)
                                put("title", a.title)
                                put("artist", a.artist)
                            })
                        }
                    })
                }

                val rb = payload.toString().toRequestBody("application/json".toMediaType())
                val resp = withContext(Dispatchers.IO) { api.subsonicResolve(rb) }
                if (!resp.isSuccessful || version != resolveVersion) return@launch

                val obj = JSONObject(resp.body().orEmpty())
                obj.optJSONObject("songs")?.let { songsObj ->
                    songsObj.keys().forEach { key ->
                        subsonicSongAvailable[key] = songsObj.optJSONObject(key)?.optBoolean("available", false) ?: false
                    }
                }
                obj.optJSONObject("albums")?.let { albumsObj ->
                    albumsObj.keys().forEach { key ->
                        subsonicAlbumAvailable[key] = albumsObj.optJSONObject(key)?.optBoolean("available", false) ?: false
                    }
                }
            } catch (_: Exception) {
                // Availability is best-effort and should never block search.
            }
        }
    }

    fun triggerSearch(q: String) {
        if (q.isBlank()) {
            clearToRecents()
            return
        }
        if (HelixPrefs.getSessionToken(ctx).isNullOrBlank()) {
            status = "Not logged in — go to Settings"
            return
        }

        loading = true
        songResults = emptyList()
        albumResults = emptyList()
        artistResults = emptyList()
        status = "Searching…"
        lastSearchedTerm = q

        resolveVersion += 1
        val myResolveVersion = resolveVersion
        subsonicSongAvailable.clear()
        subsonicAlbumAvailable.clear()

        scope.launch {
            try {
                val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                val (resp, artistResp) = withContext(Dispatchers.IO) {
                    val searchDeferred = async { api.ytmusicSearch(q) }
                    val artistDeferred = async { api.ytmusicSearchArtists(q) }
                    searchDeferred.await() to artistDeferred.await()
                }
                val body = resp.body().orEmpty()
                val artistBody = artistResp.body().orEmpty()

                if (resp.code() == 401 || artistResp.code() == 401) {
                    status = "Session expired — log in again"
                    return@launch
                }
                if (!resp.isSuccessful) {
                    status = "Search failed (HTTP ${resp.code()})"
                    return@launch
                }
                if (!artistResp.isSuccessful) {
                    status = "Artist search failed (HTTP ${artistResp.code()})"
                    return@launch
                }

                val songs = parseSongs(body)
                val albums = parseAlbums(body)
                val artists = parseArtists(artistBody)
                songResults = songs
                albumResults = albums
                artistResults = artists

                resolveSubsonicAvailability(
                    songs = songs,
                    albums = albums,
                    baseUrl = HelixPrefs.getBaseUrl(ctx),
                    version = myResolveVersion,
                )

                status = if (songs.isEmpty() && albums.isEmpty() && artists.isEmpty()) "No results" else "Done"
            } catch (e: Exception) {
                status = "Search error: ${e.javaClass.simpleName}"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(addOnlyMode) {
        snapshotFlow { query.trim() }
            .debounce(400)
            .distinctUntilChanged()
            .collectLatest { q ->
                if (q.isBlank()) clearToRecents()
                else if (q != lastSearchedTerm) triggerSearch(q)
            }
    }

    LaunchedEffect(recents, query.trim()) {
        val q = query.trim()
        if (!addOnlyMode && q.isBlank() && recents.isNotEmpty()) {
            val songs = recents.filter { it.kind == RecentSearchPlay.Kind.SONG }.map {
                SearchSong(
                    title = it.title,
                    artist = it.artist,
                    album = it.album,
                    thumbnailUrl = it.thumbnailUrl,
                    videoId = if (it.source.equals("subsonic", ignoreCase = true)) "" else it.id,
                    source = it.source,
                    subsonicSongId = if (it.source.equals("subsonic", ignoreCase = true)) it.id else it.subsonicSongId,
                )
            }
            val albums = recents.filter { it.kind == RecentSearchPlay.Kind.ALBUM }.map {
                SearchAlbum(it.title, it.artist, it.year, it.thumbnailUrl, it.id)
            }
            resolveVersion += 1
            val version = resolveVersion
            subsonicSongAvailable.clear()
            subsonicAlbumAvailable.clear()
            resolveSubsonicAvailability(songs, albums, HelixPrefs.getBaseUrl(ctx), version)
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snack) }) { padding: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!addOnlyMode) {
                    Image(
                        painter = painterResource(id = R.drawable.helix_logo),
                        contentDescription = "Helix",
                        contentScale = ContentScale.Fit,
                        colorFilter = ColorFilter.tint(HelixAccent),
                        modifier = Modifier.size(30.dp),
                    )
                }
                Text(
                    text = if (addOnlyMode) "Add songs" else "Search",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(if (addOnlyMode) "Find a song" else "What do you want to play?") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
            )

            if (!addOnlyMode) {
                Spacer(Modifier.height(10.dp))
                SearchFilterBar(
                    selected = filter,
                    onSelected = { filter = it },
                )
            }

            if (loading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            } else {
                val errorOrEmpty = status != "Idle" && status != "Done" && status != "Searching…"
                if (errorOrEmpty) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }

            val baseUrl = HelixPrefs.getBaseUrl(ctx)
            val qTrim = query.trim()

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
            ) {
                if (!addOnlyMode && qTrim.isBlank()) {
                    if (recents.isNotEmpty()) {
                        item {
                            SearchSectionHeader(
                                title = "Recent",
                                actionLabel = "Clear",
                                onAction = {
                                    RecentSearchPlay.clear(appCtx)
                                    recents = emptyList()
                                },
                            )
                        }
                        items(recents, key = { it.kind.name + ":" + it.id }) { recent ->
                            when (recent.kind) {
                                RecentSearchPlay.Kind.SONG -> SongRow(
                                    song = SearchSong(
                                        title = recent.title,
                                        artist = recent.artist,
                                        album = recent.album,
                                        thumbnailUrl = recent.thumbnailUrl,
                                        videoId = recent.id,
                                    ),
                                    baseUrl = baseUrl,
                                    snack = snack,
                                    showOverflow = true,
                                    subsonicAvailable = subsonicSongAvailable["song:" + recent.id] == true,
                                    albumArtistByTitle = albumArtistByTitle,
                                    onNavigateToNowPlaying = onNavigateToNowPlaying,
                                )
                                RecentSearchPlay.Kind.ALBUM -> AlbumRow(
                                    album = SearchAlbum(
                                        recent.title,
                                        recent.artist,
                                        recent.year,
                                        recent.thumbnailUrl,
                                        recent.id,
                                    ),
                                    baseUrl = baseUrl,
                                    snack = snack,
                                    subsonicAvailable = subsonicAlbumAvailable["album:" + recent.id] == true,
                                    onOpen = {
                                        onOpenAlbum(SearchAlbum(recent.title, recent.artist, recent.year, recent.thumbnailUrl, recent.id))
                                    },
                                    onNavigateToNowPlaying = onNavigateToNowPlaying,
                                )
                            }
                        }
                    } else {
                        item {
                            Text(
                                "Search Helix to find music.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 18.dp),
                            )
                        }
                    }
                } else if (addOnlyMode) {
                    items(songResults) { song ->
                        SongRow(
                            song = song,
                            baseUrl = baseUrl,
                            snack = snack,
                            showOverflow = false,
                            subsonicAvailable = subsonicSongAvailable["song:" + song.videoId] == true,
                            albumArtistByTitle = albumArtistByTitle,
                            onNavigateToNowPlaying = onNavigateToNowPlaying,
                            extraActionLabel = "Add",
                            onExtraAction = { onAddToPlaylist?.invoke(it) },
                        )
                    }
                } else {
                    when (filter) {
                        SearchFilter.All -> {
                            if (artistResults.isNotEmpty()) {
                                item { SearchSectionHeader("Artists") }
                                items(artistResults.take(3)) { artist -> ArtistRow(artist) { onOpenArtist(artist) } }
                                item { Spacer(Modifier.height(14.dp)) }
                            }
                            if (albumResults.isNotEmpty()) {
                                item { SearchSectionHeader("Albums") }
                                items(albumResults.take(3)) { album ->
                                    AlbumRow(
                                        album,
                                        baseUrl,
                                        snack,
                                        subsonicAlbumAvailable["album:" + album.browseId] == true,
                                        { onOpenAlbum(album) },
                                        onNavigateToNowPlaying,
                                    )
                                }
                                item { Spacer(Modifier.height(14.dp)) }
                            }
                            if (songResults.isNotEmpty()) {
                                item { SearchSectionHeader("Songs") }
                                items(songResults) { song ->
                                    SongRow(
                                        song,
                                        baseUrl,
                                        snack,
                                        true,
                                        subsonicSongAvailable["song:" + song.videoId] == true,
                                        albumArtistByTitle,
                                        onNavigateToNowPlaying = onNavigateToNowPlaying,
                                    )
                                }
                            }
                        }
                        SearchFilter.Artists -> items(artistResults) { artist -> ArtistRow(artist) { onOpenArtist(artist) } }
                        SearchFilter.Albums -> items(albumResults) { album ->
                            AlbumRow(
                                album,
                                baseUrl,
                                snack,
                                subsonicAlbumAvailable["album:" + album.browseId] == true,
                                { onOpenAlbum(album) },
                                onNavigateToNowPlaying,
                            )
                        }
                        SearchFilter.Songs -> items(songResults) { song ->
                            SongRow(
                                song,
                                baseUrl,
                                snack,
                                true,
                                subsonicSongAvailable["song:" + song.videoId] == true,
                                albumArtistByTitle,
                                onNavigateToNowPlaying = onNavigateToNowPlaying,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchFilterBar(
    selected: SearchFilter,
    onSelected: (SearchFilter) -> Unit,
) {
    val options = listOf(SearchFilter.Songs, SearchFilter.Artists, SearchFilter.Albums, SearchFilter.All)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            val active = selected == option
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(58.dp)
                    .clickable { onSelected(option) },
                shape = RoundedCornerShape(10.dp),
                color = if (active) HelixSurfaceRaised else MaterialTheme.colorScheme.surface,
                border = BorderStroke(
                    width = 1.dp,
                    color = if (active) HelixAccent else HelixBorder,
                ),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (active) HelixAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(10.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

@Composable
private fun SearchSectionHeader(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelLarge,
                color = HelixAccent,
                modifier = Modifier
                    .clickable(onClick = onAction)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun SongRow(
    song: SearchSong,
    baseUrl: String,
    snack: SnackbarHostState,
    showOverflow: Boolean,
    subsonicAvailable: Boolean,
    albumArtistByTitle: Map<String, String>,
    extraActionLabel: String? = null,
    onExtraAction: ((SearchSong) -> Unit)? = null,
    onNavigateToNowPlaying: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val thumb = HelixImages.absoluteUrl(baseUrl, song.thumbnailUrl)

    fun playSong() {
        scope.launch {
            try {
                HelixPrefs.setLastStationName(ctx, null)
                val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                val payload = HelixTrackRequests.playOrQueueBodyFromSearchSong(HelixPrefs.getBaseUrl(ctx), song)
                val resp = withContext(Dispatchers.IO) { api.playTrack(payload.toJsonRequestBody()) }
                if (!resp.isSuccessful) {
                    snack.showNonBlocking(scope, "Play failed (HTTP ${resp.code()})")
                    return@launch
                }
                RecentSearchPlay.addSong(ctx.applicationContext, song)
                HelixTransport.refreshAndPlayCurrent(ctx)
            } catch (e: Exception) {
                snack.showNonBlocking(scope, "Play error: ${e.javaClass.simpleName}")
            } finally {
                onNavigateToNowPlaying()
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = extraActionLabel == null) { playSong() }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(54.dp)) {
            AsyncImage(
                model = HelixImages.request(ctx, thumb),
                contentDescription = null,
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(6.dp)),
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" • ")
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (song.isFromSubsonic || subsonicAvailable) {
                Text(
                    "In Subsonic",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (extraActionLabel != null && onExtraAction != null) {
            TextButton(onClick = { onExtraAction(song) }) { Text(extraActionLabel) }
        } else if (showOverflow) {
            Box {
                var expanded by remember { mutableStateOf(false) }
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More actions")
                }
                HelixTrackOverflowMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    onPlay = {
                        expanded = false
                        playSong()
                    },
                    onAddToQueue = {
                        expanded = false
                        scope.launch {
                            try {
                                val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                                val bodyJson = HelixTrackRequests.playOrQueueBodyFromSearchSong(HelixPrefs.getBaseUrl(ctx), song)
                                val resp = withContext(Dispatchers.IO) { api.queueAppendTrack(bodyJson.toJsonRequestBody()) }
                                if (!resp.isSuccessful) {
                                    snack.showNonBlocking(scope, "Queue failed (HTTP ${resp.code()})")
                                    return@launch
                                }
                                RecentSearchPlay.addSong(ctx.applicationContext, song)
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
                                val artistForSubsonic = albumArtistByTitle[song.album.trim().lowercase()] ?: song.artist
                                val trackArtist = song.artist.trim().let { artist ->
                                    val lower = artist.lowercase()
                                    if (artist.isBlank() || lower.contains("view") || lower.contains("play")) artistForSubsonic else artist
                                }
                                val payload = JSONObject().apply {
                                    put("yt_video_id", song.videoId)
                                    put("title", song.title)
                                    put("artist", trackArtist)
                                    put("album_artist", artistForSubsonic)
                                    if (song.album.isNotBlank()) put("album", song.album)
                                    if (thumb.isNotBlank()) put("art_url", thumb)
                                }
                                val resp = withContext(Dispatchers.IO) { api.subsonicAddTrack(payload.toJsonRequestBody()) }
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
                    showAddToSubsonic = !song.isFromSubsonic,
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
}

@Composable
private fun AlbumRow(
    album: SearchAlbum,
    baseUrl: String,
    snack: SnackbarHostState,
    subsonicAvailable: Boolean,
    onOpen: (() -> Unit)? = null,
    onNavigateToNowPlaying: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val thumb = HelixImages.absoluteUrl(baseUrl, album.thumbnailUrl)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onOpen != null) { onOpen?.invoke() }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(54.dp)) {
            AsyncImage(
                model = HelixImages.request(ctx, thumb),
                contentDescription = null,
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(6.dp)),
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                album.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = listOf(album.artist, album.year).filter { it.isNotBlank() }.joinToString(" • ")
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (album.isFromSubsonic || subsonicAvailable) {
                Text("In Subsonic", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }

        Box {
            var expanded by remember { mutableStateOf(false) }
            IconButton(onClick = { expanded = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More actions")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, shape = HelixMenuShape) {
                DropdownMenuItem(
                    text = { Text("Play album") },
                    onClick = {
                        expanded = false
                        if (album.browseId.isBlank()) {
                            scope.launch { snack.showNonBlocking(scope, "Album has no browseId") }
                            return@DropdownMenuItem
                        }
                        RecentSearchPlay.addAlbum(ctx.applicationContext, album)
                        showLoadingOverlay("Starting album…")
                        scope.launch {
                            try {
                                HelixPrefs.setLastStationName(ctx, null)
                                val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                                val payload = JSONObject().apply {
                                    put("browse_id", album.browseId)
                                    if (album.title.isNotBlank()) put("title", album.title)
                                    if (album.artist.isNotBlank()) put("artist", album.artist)
                                    if (thumb.isNotBlank()) put("art_url", thumb)
                                }
                                val resp = withContext(Dispatchers.IO) { api.playAlbum(payload.toJsonRequestBody()) }
                                if (!resp.isSuccessful) {
                                    snack.showNonBlocking(scope, "Play failed (HTTP ${resp.code()})")
                                    return@launch
                                }
                                showLoadingOverlay("Loading now playing…")
                                HelixTransport.refreshAndPlayCurrent(ctx)
                            } catch (e: Exception) {
                                snack.showNonBlocking(scope, "Play error: ${e.javaClass.simpleName}")
                            } finally {
                                onNavigateToNowPlaying()
                                hideLoadingOverlay()
                            }
                        }
                    },
                )
                DropdownMenuItem(
                    text = { Text("Add to queue") },
                    onClick = {
                        expanded = false
                        scope.launch {
                            try {
                                val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                                val payload = JSONObject().apply {
                                    put("browse_id", album.browseId)
                                    if (album.title.isNotBlank()) put("title", album.title)
                                    if (album.artist.isNotBlank()) put("artist", album.artist)
                                    if (thumb.isNotBlank()) put("art_url", thumb)
                                }
                                val resp = withContext(Dispatchers.IO) { api.queueAppendAlbum(payload.toJsonRequestBody()) }
                                if (!resp.isSuccessful) {
                                    snack.showNonBlocking(scope, "Queue failed (HTTP ${resp.code()})")
                                    return@launch
                                }
                                snack.showNonBlocking(scope, "Queued: ${album.title}")
                            } catch (e: Exception) {
                                snack.showNonBlocking(scope, "Queue error: ${e.javaClass.simpleName}")
                            }
                        }
                    },
                )
                DropdownMenuItem(
                    text = { Text("Add to Subsonic") },
                    onClick = {
                        expanded = false
                        scope.launch {
                            try {
                                val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                                val payload = JSONObject().apply {
                                    put("browse_id", album.browseId)
                                    if (album.title.isNotBlank()) put("title", album.title)
                                    if (album.artist.isNotBlank()) put("artist", album.artist)
                                    if (thumb.isNotBlank()) put("art_url", thumb)
                                }
                                val resp = withContext(Dispatchers.IO) { api.subsonicAddAlbum(payload.toJsonRequestBody()) }
                                if (!resp.isSuccessful) {
                                    snack.showNonBlocking(scope, "Add to Subsonic failed (HTTP ${resp.code()})")
                                    return@launch
                                }
                                snack.showNonBlocking(scope, "Added to Subsonic: ${album.title}")
                            } catch (e: Exception) {
                                snack.showNonBlocking(scope, "Add to Subsonic error: ${e.javaClass.simpleName}")
                            }
                        }
                    },
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
}

@Composable
private fun ArtistRow(
    artist: SearchArtist,
    onOpen: () -> Unit,
) {
    val ctx = LocalContext.current
    val baseUrl = HelixPrefs.getBaseUrl(ctx)
    val thumb = HelixImages.absoluteUrl(baseUrl, artist.thumbnailUrl)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = HelixImages.request(ctx, thumb),
            contentDescription = null,
            modifier = Modifier
                .size(54.dp)
                ,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                artist.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = listOf(artist.subscriberCount, artist.monthlyListeners)
                .filter { it.isNotBlank() }
                .joinToString(" • ")
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
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
}


private fun parseArtists(json: String): List<SearchArtist> {
    val root = JSONObject(json)
    val artists = root.optJSONArray("artists") ?: JSONArray()
    val out = ArrayList<SearchArtist>(artists.length())
    for (i in 0 until artists.length()) {
        val o = artists.optJSONObject(i) ?: continue
        val name = o.optString("name", o.optString("artist", ""))
        val browseId = o.optString("browse_id", o.optString("browseId", o.optString("artist_id", "")))
        val thumb = when {
            o.has("thumbnail_url") -> o.optString("thumbnail_url", "")
            o.has("thumbnail") -> o.optString("thumbnail", "")
            o.has("thumb") -> o.optString("thumb", "")
            else -> ""
        }
        out.add(
            SearchArtist(
                name = name,
                thumbnailUrl = thumb,
                browseId = browseId,
                subscriberCount = o.optString("subscriber_count", o.optString("subscribers", "")),
                monthlyListeners = o.optString("monthly_listeners", o.optString("monthlyListeners", "")),
            )
        )
    }
    return out
}

private fun parseSongs(json: String): List<SearchSong> {
    val root = JSONObject(json)
    val songs = root.optJSONArray("songs") ?: JSONArray()
    val out = ArrayList<SearchSong>(songs.length())
    for (i in 0 until songs.length()) {
        val o = songs.optJSONObject(i) ?: continue
        val title = o.optString("title", "")
        val artist = o.optString("artist", o.optString("artists", ""))
        val album = o.optString("album", "")
        val videoId = o.optString("video_id", o.optString("videoId", ""))
        val source = o.optString("source", "ytmusic")
        val subsonicSongId = o.optString("subsonic_song_id", o.optString("subsonicSongId", ""))
        val thumb = when {
            o.has("thumbnail_url") -> o.optString("thumbnail_url", "")
            o.has("thumbnail") -> o.optString("thumbnail", "")
            o.has("thumb") -> o.optString("thumb", "")
            else -> ""
        }
        out.add(
            SearchSong(
                title = title,
                artist = artist,
                album = album,
                thumbnailUrl = thumb,
                videoId = videoId,
                source = source,
                subsonicSongId = subsonicSongId,
            )
        )
    }
    return out
}

private fun parseAlbums(json: String): List<SearchAlbum> {
    val root = JSONObject(json)
    val albums = root.optJSONArray("albums") ?: JSONArray()
    val out = ArrayList<SearchAlbum>(albums.length())
    for (i in 0 until albums.length()) {
        val o = albums.optJSONObject(i) ?: continue
        val title = o.optString("title", o.optString("name", ""))
        val artist = o.optString("artist", o.optString("artists", ""))
        val year = o.optString("year", o.optString("release_year", ""))
        val browseId = o.optString("browse_id", o.optString("browseId", ""))
        val source = o.optString("source", "ytmusic")
        val subsonicAlbumId = o.optString("subsonic_album_id", o.optString("subsonicAlbumId", ""))
        val thumb = when {
            o.has("thumbnail_url") -> o.optString("thumbnail_url", "")
            o.has("thumbnail") -> o.optString("thumbnail", "")
            o.has("thumb") -> o.optString("thumb", "")
            else -> ""
        }
        out.add(
            SearchAlbum(
                title = title,
                artist = artist,
                year = year,
                thumbnailUrl = thumb,
                browseId = browseId,
                source = source,
                subsonicAlbumId = subsonicAlbumId,
            )
        )
    }
    return out
}

private fun JSONObject.toJsonRequestBody(): RequestBody {
    val mt = "application/json; charset=utf-8".toMediaType()
    return toString().toRequestBody(mt)
}
