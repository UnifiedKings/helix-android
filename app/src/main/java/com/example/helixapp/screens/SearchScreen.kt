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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.example.helixapp.playback.PlaybackActions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.json.JSONObject

/** The Search tab: songs, albums and artists, with recently played results while it's empty. */
@Composable
fun SearchScreen(
    onOpenAlbum: (SearchAlbum) -> Unit,
    onOpenArtist: (SearchArtist) -> Unit = {},
    onNavigateToNowPlaying: () -> Unit = {},
    viewModel: SearchViewModel = helixViewModel { SearchViewModel(it) },
) {
    val ctx = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Something may have been played from search since the screen was last shown.
    LaunchedEffect(Unit) { viewModel.refreshRecents() }

    Scaffold { padding: PaddingValues ->
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
                Image(
                    painter = painterResource(id = R.drawable.helix_logo),
                    contentDescription = "Helix",
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.tint(HelixAccent),
                    modifier = Modifier.size(30.dp),
                )
                Text(
                    text = "Search",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text("What do you want to play?") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setQuery("") }) {
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

            Spacer(Modifier.height(10.dp))
            SearchFilterBar(selected = state.filter, onSelected = viewModel::setFilter)

            if (state.loading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            } else {
                state.message?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }

            val baseUrl = HelixPrefs.getBaseUrl(ctx)

            @Composable
            fun Song(song: SearchSong) = SongRow(
                song = song,
                baseUrl = baseUrl,
                subsonicAvailable = state.songInSubsonic(song),
                onAddToSubsonic = viewModel::addSongToSubsonic,
                onNavigateToNowPlaying = onNavigateToNowPlaying,
            )

            @Composable
            fun Album(album: SearchAlbum) = AlbumRow(
                album = album,
                baseUrl = baseUrl,
                subsonicAvailable = state.albumInSubsonic(album),
                onOpen = { onOpenAlbum(album) },
                onAddToSubsonic = viewModel::addAlbumToSubsonic,
                onNavigateToNowPlaying = onNavigateToNowPlaying,
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
            ) {
                if (state.query.isBlank()) {
                    if (state.recents.isNotEmpty()) {
                        item {
                            SearchSectionHeader(title = "Recent", actionLabel = "Clear", onAction = viewModel::clearRecents)
                        }
                        items(state.recents, key = { it.kind.name + ":" + it.id }) { recent ->
                            when (recent.kind) {
                                RecentSearchPlay.Kind.SONG -> Song(recent.toSearchSong())
                                RecentSearchPlay.Kind.ALBUM -> Album(recent.toSearchAlbum())
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
                } else {
                    when (state.filter) {
                        SearchFilter.All -> {
                            if (state.artists.isNotEmpty()) {
                                item { SearchSectionHeader("Artists") }
                                items(state.artists.take(3)) { artist -> ArtistRow(artist) { onOpenArtist(artist) } }
                                item { Spacer(Modifier.height(14.dp)) }
                            }
                            if (state.albums.isNotEmpty()) {
                                item { SearchSectionHeader("Albums") }
                                items(state.albums.take(3)) { album -> Album(album) }
                                item { Spacer(Modifier.height(14.dp)) }
                            }
                            if (state.songs.isNotEmpty()) {
                                item { SearchSectionHeader("Songs") }
                                items(state.songs) { song -> Song(song) }
                            }
                        }
                        SearchFilter.Artists -> items(state.artists) { artist -> ArtistRow(artist) { onOpenArtist(artist) } }
                        SearchFilter.Albums -> items(state.albums) { album -> Album(album) }
                        SearchFilter.Songs -> items(state.songs) { song -> Song(song) }
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
    subsonicAvailable: Boolean,
    onAddToSubsonic: (SearchSong, artUrl: String) -> Unit,
    onNavigateToNowPlaying: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val thumb = HelixImages.absoluteUrl(baseUrl, song.thumbnailUrl)

    fun playSong() {
        scope.launchPlaybackAction(
            failureAction = "Play",
            overlayMessage = "Starting track…",
            onSuccess = onNavigateToNowPlaying,
        ) {
            val payload = HelixTrackRequests.playOrQueueBodyFromSearchSong(HelixPrefs.getBaseUrl(ctx), song)
            PlaybackActions.playTrack(ctx, payload)
            RecentSearchPlay.addSong(ctx.applicationContext, song)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { playSong() }
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
                onPlayNext = {
                    expanded = false
                    scope.launchPlaybackAction(
                        failureAction = "Play next",
                        successMessage = "Playing next: ${song.title}",
                    ) {
                        val bodyJson = HelixTrackRequests.playOrQueueBodyFromSearchSong(HelixPrefs.getBaseUrl(ctx), song)
                        PlaybackActions.playNext(ctx, bodyJson)
                        RecentSearchPlay.addSong(ctx.applicationContext, song)
                    }
                },
                onAddToQueue = {
                    expanded = false
                    scope.launchPlaybackAction(
                        failureAction = "Queue",
                        successMessage = "Queued: ${song.title}",
                    ) {
                        val bodyJson = HelixTrackRequests.playOrQueueBodyFromSearchSong(HelixPrefs.getBaseUrl(ctx), song)
                        PlaybackActions.queueTrack(ctx, bodyJson)
                        RecentSearchPlay.addSong(ctx.applicationContext, song)
                    }
                },
                onAddToSubsonic = {
                    expanded = false
                    onAddToSubsonic(song, thumb)
                },
                showAddToSubsonic = !song.isFromSubsonic,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
}

@Composable
private fun AlbumRow(
    album: SearchAlbum,
    baseUrl: String,
    subsonicAvailable: Boolean,
    onOpen: (() -> Unit)? = null,
    onAddToSubsonic: (SearchAlbum, request: JSONObject) -> Unit,
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
                            UserMessages.show("Album has no browseId")
                            return@DropdownMenuItem
                        }
                        RecentSearchPlay.addAlbum(ctx.applicationContext, album)
                        scope.launchPlaybackAction(
                            failureAction = "Play",
                            overlayMessage = "Starting album…",
                            onSuccess = onNavigateToNowPlaying,
                        ) {
                            PlaybackActions.playAlbum(ctx, albumPayload(album, thumb))
                        }
                    },
                )
                DropdownMenuItem(
                    text = { Text("Add to queue") },
                    onClick = {
                        expanded = false
                        scope.launchPlaybackAction(
                            failureAction = "Queue",
                            successMessage = "Queued: ${album.title}",
                        ) {
                            PlaybackActions.queueAlbum(ctx, albumPayload(album, thumb))
                        }
                    },
                )
                DropdownMenuItem(
                    text = { Text("Add to Subsonic") },
                    onClick = {
                        expanded = false
                        onAddToSubsonic(album, albumPayload(album, thumb))
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


private fun albumPayload(album: SearchAlbum, absoluteThumb: String): JSONObject = JSONObject().apply {
    put("browse_id", album.browseId)
    if (album.title.isNotBlank()) put("title", album.title)
    if (album.artist.isNotBlank()) put("artist", album.artist)
    if (absoluteThumb.isNotBlank()) put("art_url", absoluteThumb)
}
