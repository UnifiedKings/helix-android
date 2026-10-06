package com.example.helixapp

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.helixapp.helix.HelixTrackRequests
import com.example.helixapp.playback.PlaybackActions
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ArtistScreen(
    browseId: String,
    onOpenAlbum: (SearchAlbum) -> Unit,
    onOpenArtist: (SearchArtist) -> Unit,
    onNavigateToNowPlaying: () -> Unit = {},
    viewModel: ArtistViewModel = helixViewModel(key = "artist:$browseId") { ArtistViewModel(it, browseId) },
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val artist = state.artist
    val popularTracks = state.popular
    val albums = state.albums
    val similarArtists = state.similar
    val similarState = state.similarState

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(artist.name.ifBlank { "Artist" }) },
                navigationIcon = {},
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                ArtistHeader(
                    artist = artist,
                    loading = state.loading,
                    status = state.status,
                    onCreateStation = viewModel::createStation,
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
                        onAddToSubsonic = viewModel::addToSubsonic,
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

            if (similarArtists.isNotEmpty() || similarState != SimilarState.Idle) {
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
                            if (similarState == SimilarState.Loading) "Finding similar artists…" else "No similar artists available yet",
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
    onAddToSubsonic: (SearchSong, artUrl: String) -> Unit,
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
                    onAddToSubsonic(song, thumb)
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
