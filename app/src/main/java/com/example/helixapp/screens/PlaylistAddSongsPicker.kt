package com.example.helixapp

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Playlist → Add songs: search, open an album or artist, pick several songs, add them. */
@Composable
internal fun PlaylistAddSongsPicker(
    playlistName: String,
    playlistId: String,
    onClose: () -> Unit,
    viewModel: PlaylistPickerViewModel = helixViewModel(key = "picker:$playlistId") { PlaylistPickerViewModel(it, playlistId) },
) {
    val ctx = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query = state.query
    val tab = state.tab
    val loading = state.loading
    val status = state.status
    val artists = state.artists
    val albums = state.albums
    val drillTitle = state.drillTitle
    val selected = state.selected
    val addedKeys = state.addedKeys
    val adding = state.adding

    Scaffold(
        bottomBar = {
            if (selected.isNotEmpty()) {
                Surface(
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Button(
                        enabled = !adding,
                        onClick = viewModel::addSelected,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        if (adding) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            "Add ${selected.size} song${if (selected.size == 1) "" else "s"}"
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        if (drillTitle != null) {
                            viewModel.closeDrill()
                        } else {
                            onClose()
                        }
                    },
                ) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = drillTitle ?: "Add songs",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (drillTitle == null) {
                        Text(
                            text = "to $playlistName",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (drillTitle == null) {
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (query.isNotBlank()) {
                            IconButton(onClick = { viewModel.setQuery("") }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    placeholder = { Text("Search songs, artists, albums") },
                    shape = RoundedCornerShape(14.dp),
                )

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PlaylistPickerTab.entries.forEach { option ->
                        val active = tab == option
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .clickable { viewModel.setTab(option) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (active) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    option.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (active) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
            }

            if (loading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp))
                }
            } else if (status.isNotBlank()) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 14.dp),
                )
            }

            val displayedSongs = state.displayedSongs
            val baseUrl = HelixPrefs.getBaseUrl(ctx)

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 20.dp),
            ) {
                if (drillTitle != null || tab == PlaylistPickerTab.Songs) {
                    if (!loading && query.isBlank() && drillTitle == null) {
                        item {
                            Text(
                                "Search for music to add to this playlist.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 18.dp),
                            )
                        }
                    }

                    itemsIndexed(
                        displayedSongs,
                        key = { index, song ->
                            songKey(song).ifBlank { "picker-song-$index" }
                        },
                    ) { _, song ->
                        val key = songKey(song)
                        val isSelected = selected.containsKey(key)
                        val isAdded = addedKeys.contains(key)
                        val art = HelixImages.absoluteUrl(baseUrl, song.thumbnailUrl)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isAdded) { viewModel.toggle(song) }
                                .padding(vertical = 9.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (art.isNotBlank()) {
                                AsyncImage(
                                    model = HelixImages.request(ctx, art),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                )
                            } else {
                                Surface(
                                    modifier = Modifier.size(52.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                ) {}
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    song.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val subtitle = listOf(song.artist, song.album)
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

                            Surface(
                                modifier = Modifier.size(34.dp),
                                shape = RoundedCornerShape(10.dp),
                                color = when {
                                    isAdded -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                    isSelected -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                },
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (isAdded || isSelected) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = if (isAdded) "Added" else "Selected",
                                            modifier = Modifier.size(18.dp),
                                            tint = if (isSelected && !isAdded) {
                                                MaterialTheme.colorScheme.onPrimary
                                            } else {
                                                MaterialTheme.colorScheme.primary
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                } else if (tab == PlaylistPickerTab.Artists) {
                    itemsIndexed(
                        artists,
                        key = { index, artist -> artist.browseId.ifBlank { "picker-artist-$index" } },
                    ) { _, artist ->
                        val art = HelixImages.absoluteUrl(baseUrl, artist.thumbnailUrl)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.openArtist(artist)
                                }
                                .padding(vertical = 9.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (art.isNotBlank()) {
                                AsyncImage(
                                    model = HelixImages.request(ctx, art),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(26.dp)),
                                )
                            } else {
                                Surface(
                                    modifier = Modifier.size(52.dp),
                                    shape = RoundedCornerShape(26.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                ) {}
                            }
                            Text(
                                artist.name,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        HorizontalDivider()
                    }
                } else {
                    itemsIndexed(
                        albums,
                        key = { index, album -> album.browseId.ifBlank { "picker-album-$index" } },
                    ) { _, album ->
                        val art = HelixImages.absoluteUrl(baseUrl, album.thumbnailUrl)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.openAlbum(album)
                                }
                                .padding(vertical = 9.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (art.isNotBlank()) {
                                AsyncImage(
                                    model = HelixImages.request(ctx, art),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                )
                            } else {
                                Surface(
                                    modifier = Modifier.size(52.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                ) {}
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    album.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (album.artist.isNotBlank()) {
                                    Text(
                                        album.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
