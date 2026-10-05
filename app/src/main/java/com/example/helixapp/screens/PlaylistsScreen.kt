package com.example.helixapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.helixapp.playback.PlaybackActions
import com.example.helixapp.ui.theme.HelixAccent
import com.example.helixapp.ui.theme.HelixBorder
import com.example.helixapp.ui.theme.HelixMuted

@Composable
fun PlaylistsScreen(
    onOpenPlaylist: (String) -> Unit,
    onNavigateToNowPlaying: () -> Unit = {},
    createRequestKey: Int = 0,
    viewModel: PlaylistsViewModel = helixViewModel { PlaylistsViewModel(it) },
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val state by viewModel.state.collectAsStateWithLifecycle()
    val playlists = state.playlists
    val loading = state.loading
    var creating by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PlaylistUi?>(null) }

    fun playPlaylistFromList(pl: PlaylistUi, shuffle: Boolean) {
        scope.launchPlaybackAction(
            failureAction = if (shuffle) "Shuffle" else "Play",
            overlayMessage = if (shuffle) "Shuffling playlist…" else "Starting playlist…",
            onSuccess = onNavigateToNowPlaying,
        ) {
            PlaybackActions.playPlaylist(ctx, pl.playId, shuffle)
        }
    }

    LaunchedEffect(createRequestKey) {
        if (createRequestKey > 0) creating = true
    }

    // Coming back to the tab or the app after a while: refresh.
    LaunchedEffect(Unit) { viewModel.refreshIfStale() }
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshIfStale()
        onPauseOrDispose {}
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (loading) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        } else if (state.error != null && playlists.isNotEmpty()) {
            Text(
                text = state.error.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (playlists.isEmpty() && !loading) {
            Box(modifier = Modifier.padding(top = 12.dp, start = 2.dp)) {
                Text(
                    text = when {
                        !state.signedIn -> "Log in from Settings to load your playlists."
                        state.error != null -> state.error.orEmpty()
                        else -> "You have no playlists yet."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.error != null && state.signedIn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn {
                itemsIndexed(playlists) { index, pl ->
                    PlaylistRow(
                        playlist = pl,
                        onOpenPlaylist = {
                            onOpenPlaylist(if (pl.systemKey == "liked") "liked" else pl.id)
                        },
                        onPlay = { playPlaylistFromList(pl, shuffle = false) },
                        onShuffle = { playPlaylistFromList(pl, shuffle = true) },
                        onDelete = { pendingDelete = pl },
                    )
                    if (index < playlists.lastIndex) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 68.dp)
                                .height(1.dp)
                                .background(HelixBorder)
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { pl ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete playlist?") },
            text = { Text("\"${pl.name}\" will be deleted for good.") },
            confirmButton = {
                HelixTextButton(
                    onClick = {
                        pendingDelete = null
                        viewModel.delete(pl)
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                HelixTextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }

    if (creating) {
        PlaylistCreateDialog(
            onDismiss = { creating = false },
            onCreate = { name -> viewModel.create(name, onCreated = { creating = false }) }
        )
    }
}

@Composable
private fun PlaylistRow(
    playlist: PlaylistUi,
    onOpenPlaylist: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onDelete: () -> Unit,
) {
    val ctx = LocalContext.current
    val cover = HelixImages.absoluteUrl(HelixPrefs.getBaseUrl(ctx), playlist.thumbnailUrl)
    var menuExpanded by remember(playlist.id, playlist.systemKey) { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenPlaylist)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = HelixImages.request(ctx, cover),
            contentDescription = null,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(10.dp))
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = playlistSummary(playlist),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            playlistBadgeLabel(playlist)?.let { badge ->
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelMedium,
                    color = HelixMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        IconButton(onClick = onPlay) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = "Play playlist",
                tint = HelixAccent,
            )
        }

        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Playlist options", tint = HelixMuted)
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                shape = HelixMenuShape,
            ) {
                DropdownMenuItem(
                    text = { Text("Shuffle") },
                    onClick = {
                        menuExpanded = false
                        onShuffle()
                    },
                )
                // System playlists (e.g. Liked songs) can't be deleted.
                if (playlist.systemKey.isBlank()) {
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistCreateDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(enabled = trimmed.isNotBlank(), onClick = { onCreate(trimmed) }) { Text("Create") }
        },
        dismissButton = {
            HelixTextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun playlistSummary(playlist: PlaylistUi): String {
    val noun = if (playlist.trackCount == 1) "track" else "tracks"
    return "${playlist.trackCount} $noun"
}

private fun playlistBadgeLabel(playlist: PlaylistUi): String? {
    return when {
        playlist.systemKey.equals("liked", ignoreCase = true) -> "System playlist"
        playlist.systemKey.isNotBlank() -> simpleTitleCase(playlist.systemKey)
        playlist.kind.isNotBlank() && !playlist.kind.equals("playlist", ignoreCase = true) ->
            simpleTitleCase(playlist.kind.replace('_', ' '))
        else -> null
    }
}

private fun simpleTitleCase(value: String): String {
    if (value.isBlank()) return value
    return value.substring(0, 1).uppercase() + value.substring(1)
}
