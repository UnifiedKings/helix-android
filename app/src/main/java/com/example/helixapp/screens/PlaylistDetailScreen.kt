package com.example.helixapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.helixapp.helix.HelixTrackRequests
import com.example.helixapp.playback.PlaybackActions
import org.json.JSONObject

import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: String,
    onNavigateToNowPlaying: () -> Unit = {},
    onClose: () -> Unit = {},
    viewModel: PlaylistDetailViewModel = helixViewModel(key = "playlist:$playlistId") { PlaylistDetailViewModel(it, playlistId) },
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val state by viewModel.state.collectAsStateWithLifecycle()
    val loading = state.loading
    val title = state.title
    val coverUrl = state.coverUrl
    val tracks = state.tracks
    val editMode = state.editMode
    val selectedTrackIds = state.selectedIds
    val canEditPlaylist = state.canEdit
    val selectedTracks = state.selectedTracks

    var showAddOverlay by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showBulkSheet by remember { mutableStateOf(false) }
    var confirmBulkRemove by remember { mutableStateOf(false) }
    var confirmDeletePlaylist by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<PlaylistTrackUi?>(null) }
    var rowMenuTrack by remember { mutableStateOf<PlaylistTrackUi?>(null) }

    fun playPlaylistNow(shuffle: Boolean) {
        if (playlistId.isBlank()) {
            UserMessages.show("Missing playlist id")
            return
        }
        scope.launchPlaybackAction(
            failureAction = if (shuffle) "Shuffle" else "Play",
            overlayMessage = if (shuffle) "Shuffling playlist…" else "Starting playlist…",
            onSuccess = onNavigateToNowPlaying,
        ) {
            PlaybackActions.playPlaylist(ctx, state.playId, shuffle)
        }
    }

    fun trackPayload(t: PlaylistTrackUi): JSONObject =
        HelixTrackRequests.playOrQueueBodyFromPlaylistTrack(
            title = t.title,
            artist = t.artist,
            album = t.album,
            artUrl = t.artUrl,
            durationMs = t.durationMs,
            source = t.source,
            subsonicSongId = t.subsonicSongId,
            ytVideoId = t.ytVideoId,
            ytBrowseId = t.ytBrowseId,
            mbRecordingId = t.mbRecordingId,
            mbArtistId = t.mbArtistId,
        )

    fun queueTracksNow(selection: List<PlaylistTrackUi>) {
        if (selection.isEmpty()) return
        var queued = 0
        scope.launchPlaybackAction(
            failureAction = "Queue",
            successMessage = "Queued ${selection.size} track${if (selection.size == 1) "" else "s"}",
        ) {
            try {
                for (t in selection) {
                    PlaybackActions.queueTrack(ctx, trackPayload(t))
                    queued++
                }
            } catch (e: Exception) {
                if (queued > 0) UserMessages.show("Queued $queued of ${selection.size} before an error")
                throw e
            }
        }
    }

    fun playNextTrack(track: PlaylistTrackUi) {
        scope.launchPlaybackAction(failureAction = "Play next", successMessage = "Playing next: ${track.title}") {
            PlaybackActions.playNext(ctx, trackPayload(track))
        }
    }

    fun playTrackNow(track: PlaylistTrackUi) {
        scope.launchPlaybackAction(
            failureAction = "Play",
            overlayMessage = "Starting track…",
            onSuccess = onNavigateToNowPlaying,
        ) {
            PlaybackActions.playTrack(ctx, trackPayload(track))
        }
    }

    LaunchedEffect(Unit) { viewModel.refresh() }

    fun setEditMode(enabled: Boolean) {
        viewModel.setEditMode(enabled)
        if (!enabled) showBulkSheet = false
    }


    if (showAddOverlay) {
        PlaylistAddSongsPicker(
            playlistName = title,
            playlistId = state.playId,
            onClose = {
                showAddOverlay = false
                viewModel.refresh()
            },
        )
        return
    }

    Scaffold(
        bottomBar = {
            if (editMode) {
                PlaylistEditBottomBar(
                    selectedCount = selectedTrackIds.size,
                    canMove = selectedTrackIds.isNotEmpty(),
                    onRemove = { confirmBulkRemove = true },
                    onMoveUp = viewModel::moveSelectedUp,
                    onMoveToTop = viewModel::moveSelectedToTop,
                    onAddSongs = { showAddOverlay = true },
                    onMore = { showBulkSheet = true },
                )
            }
        },
    ) { padding ->
        // The entire playlist page is now one LazyColumn. The large cover, title and
        // controls are list content instead of a permanently pinned block, so scrolling
        // naturally gives almost the whole screen to playlist entries.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = if (editMode) 8.dp else 20.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (editMode) {
                item(key = "edit-header") {
                    PlaylistEditHeader(
                        title = if (selectedTrackIds.isEmpty()) {
                            "Edit Playlist"
                        } else {
                            "${selectedTrackIds.size} Selected"
                        },
                        onCancel = { setEditMode(false) },
                        onDone = { setEditMode(false) },
                    )
                }
                item(key = "edit-compact-header") {
                    CompactPlaylistHeader(
                        title = title,
                        coverUrl = HelixImages.absoluteUrl(
                            HelixPrefs.getBaseUrl(ctx),
                            coverUrl,
                        ),
                        trackCount = tracks.size,
                    )
                }
                item(key = "edit-help") {
                    Text(
                        text = "Select multiple tracks for bulk actions. Hold the white handle; the row lifts up and follows your finger while you reorder.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                item(key = "playlist-hero") {
                    PlaylistHeroHeader(
                        title = title,
                        coverUrl = HelixImages.absoluteUrl(
                            HelixPrefs.getBaseUrl(ctx),
                            coverUrl,
                        ),
                        trackCount = tracks.size,
                    )
                }

                item(key = "playlist-actions") {
                    PlaylistActionRow(
                        loading = loading,
                        trackCount = tracks.size,
                        canEditPlaylist = canEditPlaylist,
                        showMenu = showMenu,
                        onShowMenu = { showMenu = true },
                        onDismissMenu = { showMenu = false },
                        onPlay = {
                            playPlaylistNow(shuffle = false)
                        },
                        onShuffle = {
                            playPlaylistNow(shuffle = true)
                        },
                        onAddSongs = { showAddOverlay = true },
                        onEdit = {
                            showMenu = false
                            setEditMode(true)
                        },
                        onDelete = {
                            showMenu = false
                            confirmDeletePlaylist = true
                        },
                    )
                }
            }

            if (loading) {
                item(key = "playlist-loading") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }

            itemsIndexed(
                tracks,
                key = { index, item ->
                    item.id.ifBlank { "playlist-track-$index" }
                },
            ) { index, track ->
                val art = HelixImages.absoluteUrl(
                    HelixPrefs.getBaseUrl(ctx),
                    track.artUrl,
                )
                if (editMode) {
                    EditablePlaylistTrackRow(
                        track = track,
                        artUrl = art,
                        selected = selectedTrackIds.contains(track.id),
                        durationText = formatDurationMs(track.durationMs),
                        onToggle = { viewModel.toggleSelection(track) },
                        onMoveUp = { viewModel.moveTrack(track, -1) },
                        onMoveDown = { viewModel.moveTrack(track, +1) },
                        onDragFinished = viewModel::saveOrder,
                        canMoveUp = index > 0,
                        canMoveDown = index < tracks.lastIndex,
                    )
                } else {
                    PlaylistTrackRow(
                        track = track,
                        artUrl = art,
                        durationText = formatDurationMs(track.durationMs),
                        menuOpen = rowMenuTrack?.id == track.id,
                        onRowClick = { playTrackNow(track) },
                        onOpenMenu = { rowMenuTrack = track },
                        onDismissMenu = { rowMenuTrack = null },
                        onPlay = {
                            rowMenuTrack = null
                            playTrackNow(track)
                        },
                        onPlayNext = {
                            rowMenuTrack = null
                            playNextTrack(track)
                        },
                        onQueue = {
                            rowMenuTrack = null
                            queueTracksNow(listOf(track))
                        },
                        onRemove = {
                            rowMenuTrack = null
                            removeTarget = track
                        },
                    )
                }
            }
        }

        if (confirmDeletePlaylist) {
            AlertDialog(
                onDismissRequest = { confirmDeletePlaylist = false },
                title = { Text("Delete playlist?") },
                text = { Text("Are you sure? This cannot be undone.") },
                confirmButton = {
                    Button(
                        onClick = {
                            confirmDeletePlaylist = false
                            viewModel.deletePlaylist(onDeleted = onClose)
                        },
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    HelixTextButton(onClick = { confirmDeletePlaylist = false }) {
                        Text("Cancel")
                    }
                },
            )
        }

        if (confirmBulkRemove) {
            AlertDialog(
                onDismissRequest = { confirmBulkRemove = false },
                title = { Text("Remove selected tracks?") },
                text = {
                    Text(
                        "Remove ${selectedTrackIds.size} selected track${if (selectedTrackIds.size == 1) "" else "s"} from this playlist?"
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            confirmBulkRemove = false
                            viewModel.removeSelected()
                        },
                    ) {
                        Text("Remove")
                    }
                },
                dismissButton = {
                    HelixTextButton(onClick = { confirmBulkRemove = false }) {
                        Text("Cancel")
                    }
                },
            )
        }

        if (removeTarget != null) {
            val tr = removeTarget!!
            AlertDialog(
                onDismissRequest = { removeTarget = null },
                title = { Text("Remove track?") },
                text = { Text("Remove \"${tr.title}\" from this playlist?") },
                confirmButton = {
                    Button(
                        onClick = {
                            removeTarget = null
                            viewModel.removeTrack(tr)
                        },
                    ) {
                        Text("Remove")
                    }
                },
                dismissButton = {
                    HelixTextButton(onClick = { removeTarget = null }) {
                        Text("Cancel")
                    }
                },
            )
        }

        if (showBulkSheet) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
            ModalBottomSheet(
                onDismissRequest = { showBulkSheet = false },
                sheetState = sheetState,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            ) {
                PlaylistBulkActionSheet(
                    selectedCount = selectedTrackIds.size,
                    onClear = {
                        viewModel.clearSelection()
                        showBulkSheet = false
                    },
                    onRemove = {
                        showBulkSheet = false
                        confirmBulkRemove = true
                    },
                    onQueue = {
                        showBulkSheet = false
                        queueTracksNow(selectedTracks)
                    },
                    onMoveToTop = {
                        showBulkSheet = false
                        viewModel.moveSelectedToTop()
                    },
                    onMoveDown = {
                        showBulkSheet = false
                        viewModel.moveSelectedDown()
                    },
                )
            }
        }


    }
}
