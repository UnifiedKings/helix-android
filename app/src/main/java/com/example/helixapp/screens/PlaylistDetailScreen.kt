package com.example.helixapp

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
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

private const val LIKED_SYSTEM_KEY = "liked"

data class PlaylistTrackUi(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val artUrl: String,
    val durationMs: Long,
    val source: String,
    val subsonicSongId: String,
    val ytVideoId: String,
    val ytBrowseId: String,
    val mbRecordingId: String,
    val mbArtistId: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(playlistId: String, onNavigateToNowPlaying: () -> Unit = {}, onClose: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("Playlist") }
    var coverUrl by remember { mutableStateOf("") }
    var tracks by remember { mutableStateOf(emptyList<PlaylistTrackUi>()) }
    // Some playlists are "system" playlists (ex: Liked Songs). We must prevent destructive actions on them.
    var systemKey by remember { mutableStateOf<String?>(null) }

    var showAddOverlay by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var editMode by remember { mutableStateOf(false) }
    var selectedTrackIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showBulkSheet by remember { mutableStateOf(false) }
    var confirmBulkRemove by remember { mutableStateOf(false) }
    var confirmDeletePlaylist by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<PlaylistTrackUi?>(null) }
    var rowMenuTrack by remember { mutableStateOf<PlaylistTrackUi?>(null) }

    val snack = remember { SnackbarHostState() }
    val canEditPlaylist = playlistId != LIKED_SYSTEM_KEY && systemKey != LIKED_SYSTEM_KEY
    val selectedTracks = tracks.filter { selectedTrackIds.contains(it.id) }

    fun normalizedPlaylistId(): String = systemKey?.takeIf { it.isNotBlank() } ?: playlistId

    fun refresh() {
        if (HelixPrefs.getSessionToken(ctx).isNullOrBlank()) {
            tracks = emptyList()
            return
        }
        loading = true
        scope.launch {
            try {
                val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                val resp = withContext(Dispatchers.IO) { api.playlistDetail(playlistId) }
                val body = resp.body().orEmpty()
                if (resp.code() == 401) {
                    tracks = emptyList()
                    return@launch
                }
                if (!resp.isSuccessful) {
                    tracks = emptyList()
                    return@launch
                }
                val parsed0 = parsePlaylistDetail(body)
                // Special handling: the "Liked Songs" playlist is a system playlist whose real detail endpoint is /api/playlists/liked.
                val parsed = if (parsed0.systemKey == LIKED_SYSTEM_KEY && playlistId != LIKED_SYSTEM_KEY) {
                    val resp2 = withContext(Dispatchers.IO) { api.playlistDetail(LIKED_SYSTEM_KEY) }
                    val body2 = resp2.body().orEmpty()
                    if (resp2.isSuccessful && body2.isNotBlank()) parsePlaylistDetail(body2) else parsed0
                } else parsed0

                title = parsed.name
                coverUrl = parsed.thumbnailUrl
                tracks = parsed.tracks
                systemKey = parsed.systemKey
                selectedTrackIds = selectedTrackIds.filter { id -> parsed.tracks.any { it.id == id } }.toSet()
            } catch (e: Exception) {
                tracks = emptyList()
            } finally {
                loading = false
            }
        }
    }

    suspend fun playPlaylistNow(shuffle: Boolean = false) {
        if (playlistId.isBlank()) {
            snack.showSnackbar("Missing playlist id")
            return
        }

        // Leaving station mode (if any) when starting a playlist.
        HelixPrefs.setLastStationName(ctx, null)

        val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
        val mt = "application/json; charset=utf-8".toMediaType()

        // Backend supports a dedicated endpoint that expands the playlist server-side.
        val body = JSONObject()
            .put("playlist_id", normalizedPlaylistId())
            .put("shuffle", shuffle)
            .toString()
            .toRequestBody(mt)

        val resp = withContext(Dispatchers.IO) { api.playPlaylist(body) }
        if (!resp.isSuccessful) {
            snack.showSnackbar(if (shuffle) "Shuffle failed (HTTP ${resp.code()})" else "Play failed (HTTP ${resp.code()})")
            return
        }

        HelixTransport.refreshAndPlayCurrent(ctx)
        snack.showSnackbar(if (shuffle) "Shuffling playlist" else "Playing playlist")
    }

    suspend fun reorderTracksNow(newTracks: List<PlaylistTrackUi>) {
        if (!canEditPlaylist) {
            snack.showSnackbar("This playlist order cannot be edited")
            return
        }
        if (newTracks.size != tracks.size || newTracks.any { it.id.isBlank() }) {
            snack.showSnackbar("Cannot reorder this playlist yet")
            return
        }

        val previousTracks = tracks
        tracks = newTracks

        val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
        val mt = "application/json; charset=utf-8".toMediaType()
        val ids = JSONArray()
        newTracks.forEach { ids.put(it.id) }
        val body = JSONObject()
            .put("track_ids", ids)
            .toString()
            .toRequestBody(mt)

        val resp: retrofit2.Response<String> = withContext(Dispatchers.IO) {
            api.playlistReorderTracks(playlistId, body)
        }
        if (!resp.isSuccessful) {
            tracks = previousTracks
            snack.showSnackbar("Reorder failed (HTTP ${resp.code()})")
            return
        }

        val parsed = parsePlaylistDetail(resp.body().orEmpty())
        title = parsed.name
        coverUrl = parsed.thumbnailUrl
        tracks = parsed.tracks
        systemKey = parsed.systemKey
        selectedTrackIds = selectedTrackIds.filter { id -> parsed.tracks.any { it.id == id } }.toSet()
        RefreshSignals.bumpPlaylists()
    }

    suspend fun deletePlaylistNow() {
        // Never allow deleting the Liked Songs system playlist.
        if (!canEditPlaylist) {
            snack.showSnackbar("This playlist cannot be deleted")
            return
        }
        val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
        val resp: retrofit2.Response<String> = withContext(Dispatchers.IO) {
            api.deletePlaylist(playlistId)
        }
        if (!resp.isSuccessful) {
            snack.showSnackbar("Delete failed (HTTP ${resp.code()})")
            return
        }
        RefreshSignals.bumpPlaylists()
        snack.showSnackbar("Playlist deleted")
        onClose()
    }

    suspend fun removeTrackNow(track: PlaylistTrackUi) {
        val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
        val resp: retrofit2.Response<String> = withContext(Dispatchers.IO) {
            api.playlistRemoveTrack(playlistId, track.id)
        }
        if (!resp.isSuccessful) {
            snack.showSnackbar("Remove failed (HTTP ${resp.code()})")
            return
        }
        selectedTrackIds = selectedTrackIds - track.id
        RefreshSignals.bumpPlaylists()
        refresh()
        snack.showSnackbar("Removed")
    }

    suspend fun removeSelectedTracksNow() {
        if (!canEditPlaylist) {
            snack.showSnackbar("This playlist cannot be edited")
            return
        }
        val toRemove = selectedTracks
        if (toRemove.isEmpty()) return
        val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
        var failedCode: Int? = null
        for (track in toRemove) {
            val resp = withContext(Dispatchers.IO) { api.playlistRemoveTrack(playlistId, track.id) }
            if (!resp.isSuccessful) {
                failedCode = resp.code()
                break
            }
        }
        if (failedCode != null) {
            snack.showSnackbar("Remove failed (HTTP $failedCode)")
            refresh()
            return
        }
        selectedTrackIds = emptySet()
        RefreshSignals.bumpPlaylists()
        refresh()
        snack.showSnackbar("Removed ${toRemove.size} track${if (toRemove.size == 1) "" else "s"}")
    }

    suspend fun queueTracksNow(selection: List<PlaylistTrackUi>) {
        if (selection.isEmpty()) return
        val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
        val mt = "application/json; charset=utf-8".toMediaType()
        var failedCode: Int? = null
        for (t in selection) {
            val body = HelixTrackRequests.playOrQueueBodyFromPlaylistTrack(
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
            ).toString().toRequestBody(mt)
            val resp = withContext(Dispatchers.IO) { api.queueAppendTrack(body) }
            if (!resp.isSuccessful) {
                failedCode = resp.code()
                break
            }
        }
        if (failedCode != null) {
            snack.showSnackbar("Queue failed (HTTP $failedCode)")
        } else {
            snack.showSnackbar("Queued ${selection.size} track${if (selection.size == 1) "" else "s"}")
        }
    }

    suspend fun playTrackNow(track: PlaylistTrackUi) {
        try {
            // Leaving station mode (if any) when directly starting a track.
            HelixPrefs.setLastStationName(ctx, null)
            val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
            val mt = "application/json; charset=utf-8".toMediaType()
            val body = HelixTrackRequests.playOrQueueBodyFromPlaylistTrack(
                title = track.title,
                artist = track.artist,
                album = track.album,
                artUrl = track.artUrl,
                durationMs = track.durationMs,
                source = track.source,
                subsonicSongId = track.subsonicSongId,
                ytVideoId = track.ytVideoId,
                ytBrowseId = track.ytBrowseId,
                mbRecordingId = track.mbRecordingId,
                mbArtistId = track.mbArtistId,
            ).toString().toRequestBody(mt)
            val resp = withContext(Dispatchers.IO) { api.playTrack(body) }
            if (!resp.isSuccessful) {
                snack.showSnackbar("Play failed (HTTP ${resp.code()})")
                return
            }
            showLoadingOverlay("Loading now playing…")
            HelixTransport.refreshAndPlayCurrent(ctx)
            onNavigateToNowPlaying()
        } catch (t: Throwable) {
            snack.showSnackbar("Play error: ${t.javaClass.simpleName}")
        } finally {
            hideLoadingOverlay()
        }
    }

    suspend fun addSongToPlaylist(song: SearchSong) {
        val baseUrl = HelixPrefs.getBaseUrl(ctx).trim().trimEnd('/')
        val mt = "application/json; charset=utf-8".toMediaType()

        // Use the same payload style as playback/queue requests.
        val bodyJson = HelixTrackRequests.playOrQueueBodyFromSearchSong(baseUrl, song)
        // Some backends may prefer a field name for playlist target.
        bodyJson.put("playlist_id", normalizedPlaylistId())

        val body = bodyJson.toString().toRequestBody(mt)

        val api = HelixClient.create(ctx, baseUrl)
        val resp = withContext(Dispatchers.IO) { api.playlistAddTrack(normalizedPlaylistId(), body) }

        if (resp.isSuccessful) {
            snack.showSnackbar("Added: ${song.title}")
            // Refresh so the new track appears.
            refresh()
        } else {
            snack.showSnackbar("Add failed (HTTP ${resp.code()})")
        }
    }

    fun setEditMode(enabled: Boolean) {
        editMode = enabled && canEditPlaylist
        if (!editMode) {
            selectedTrackIds = emptySet()
            showBulkSheet = false
        }
    }

    fun toggleTrackSelection(track: PlaylistTrackUi) {
        if (track.id.isBlank()) return
        selectedTrackIds = if (selectedTrackIds.contains(track.id)) {
            selectedTrackIds - track.id
        } else {
            selectedTrackIds + track.id
        }
    }

    fun moveSelectedUp() {
        val selected = selectedTrackIds
        if (selected.isEmpty()) return
        val moved = tracks.toMutableList()
        for (i in 1 until moved.size) {
            val curSelected = selected.contains(moved[i].id)
            val prevSelected = selected.contains(moved[i - 1].id)
            if (curSelected && !prevSelected) {
                val tmp = moved[i - 1]
                moved[i - 1] = moved[i]
                moved[i] = tmp
            }
        }
        scope.launch { reorderTracksNow(moved) }
    }

    fun moveSelectedDown() {
        val selected = selectedTrackIds
        if (selected.isEmpty()) return
        val moved = tracks.toMutableList()
        for (i in moved.lastIndex - 1 downTo 0) {
            val curSelected = selected.contains(moved[i].id)
            val nextSelected = selected.contains(moved[i + 1].id)
            if (curSelected && !nextSelected) {
                val tmp = moved[i + 1]
                moved[i + 1] = moved[i]
                moved[i] = tmp
            }
        }
        scope.launch { reorderTracksNow(moved) }
    }

    fun moveSelectedToTop() {
        val selected = selectedTrackIds
        if (selected.isEmpty()) return
        val moved = tracks.filter { selected.contains(it.id) } + tracks.filterNot { selected.contains(it.id) }
        scope.launch { reorderTracksNow(moved) }
    }

    LaunchedEffect(playlistId) { refresh() }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            if (editMode) {
                PlaylistEditBottomBar(
                    selectedCount = selectedTrackIds.size,
                    canMove = selectedTrackIds.isNotEmpty(),
                    onRemove = { confirmBulkRemove = true },
                    onMoveUp = { moveSelectedUp() },
                    onMoveToTop = { moveSelectedToTop() },
                    onAddSongs = { showAddOverlay = true },
                    onMore = { showBulkSheet = true }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (editMode) {
                PlaylistEditHeader(
                    title = if (selectedTrackIds.isEmpty()) "Edit Playlist" else "${selectedTrackIds.size} Selected",
                    onCancel = { setEditMode(false) },
                    onDone = { setEditMode(false) }
                )
                CompactPlaylistHeader(
                    title = title,
                    coverUrl = HelixImages.absoluteUrl(HelixPrefs.getBaseUrl(ctx), coverUrl),
                    trackCount = tracks.size
                )
                Text(
                    text = "Select multiple tracks for bulk actions. Hold the white handle; the row lifts up and follows your finger while you reorder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                PlaylistHeroHeader(
                    title = title,
                    coverUrl = HelixImages.absoluteUrl(HelixPrefs.getBaseUrl(ctx), coverUrl),
                    trackCount = tracks.size,
                    onMenu = { showMenu = true },
                    showMenu = showMenu,
                    onDismissMenu = { showMenu = false },
                    canEditPlaylist = canEditPlaylist,
                    onEdit = {
                        showMenu = false
                        setEditMode(true)
                    },
                    onDelete = {
                        showMenu = false
                        confirmDeletePlaylist = true
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        enabled = !loading && tracks.isNotEmpty(),
                        onClick = {
                            showLoadingOverlay("Starting playlist…")
                            scope.launch {
                                try {
                                    playPlaylistNow(shuffle = false)
                                } finally {
                                    onNavigateToNowPlaying()
                                    hideLoadingOverlay()
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text("▶  Play")
                    }

                    Button(
                        enabled = !loading && tracks.size > 1,
                        onClick = {
                            showLoadingOverlay("Shuffling playlist…")
                            scope.launch {
                                try {
                                    playPlaylistNow(shuffle = true)
                                } finally {
                                    onNavigateToNowPlaying()
                                    hideLoadingOverlay()
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Text("⇄  Shuffle")
                    }

                    Button(
                        enabled = canEditPlaylist,
                        onClick = { setEditMode(true) },
                        modifier = Modifier.height(52.dp),
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text("✎ Edit")
                    }
                }

                Button(
                    onClick = { showAddOverlay = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    enabled = !loading,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                    )
                ) {
                    Text("+ Add songs")
                }
            }

            if (loading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 6.dp))
                }
            }

            if (!editMode) {
                Text(
                    "${tracks.size} tracks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = if (editMode) 8.dp else 20.dp)
            ) {
                itemsIndexed(tracks, key = { index, item -> item.id.ifBlank { "playlist-track-$index" } }) { index, track ->
                    val art = HelixImages.absoluteUrl(HelixPrefs.getBaseUrl(ctx), track.artUrl)
                    if (editMode) {
                        EditablePlaylistTrackRow(
                            track = track,
                            artUrl = art,
                            selected = selectedTrackIds.contains(track.id),
                            durationText = formatDurationMs(track.durationMs),
                            onToggle = { toggleTrackSelection(track) },
                            onMoveUp = {
                                val currentIndex = tracks.indexOfFirst { it.id == track.id }
                                if (currentIndex > 0) {
                                    val moved = tracks.toMutableList()
                                    val item = moved.removeAt(currentIndex)
                                    moved.add(currentIndex - 1, item)
                                    tracks = moved
                                }
                            },
                            onMoveDown = {
                                val currentIndex = tracks.indexOfFirst { it.id == track.id }
                                if (currentIndex in 0 until tracks.lastIndex) {
                                    val moved = tracks.toMutableList()
                                    val item = moved.removeAt(currentIndex)
                                    moved.add(currentIndex + 1, item)
                                    tracks = moved
                                }
                            },
                            onDragFinished = {
                                scope.launch { reorderTracksNow(tracks) }
                            },
                            canMoveUp = index > 0,
                            canMoveDown = index < tracks.lastIndex,
                        )
                    } else {
                        PlaylistTrackRow(
                            track = track,
                            artUrl = art,
                            durationText = formatDurationMs(track.durationMs),
                            menuOpen = rowMenuTrack?.id == track.id,
                            onRowClick = { scope.launch { playTrackNow(track) } },
                            onOpenMenu = { rowMenuTrack = track },
                            onDismissMenu = { rowMenuTrack = null },
                            onPlay = {
                                rowMenuTrack = null
                                scope.launch { playTrackNow(track) }
                            },
                            onQueue = {
                                rowMenuTrack = null
                                scope.launch { queueTracksNow(listOf(track)) }
                            },
                            onRemove = {
                                rowMenuTrack = null
                                removeTarget = track
                            }
                        )
                    }
                }
            }
        }

        if (confirmDeletePlaylist) {
            AlertDialog(
                onDismissRequest = { confirmDeletePlaylist = false },
                title = { Text("Delete playlist?") },
                text = { Text("Are you sure? This cannot be undone.") },
                confirmButton = {
                    Button(onClick = {
                        confirmDeletePlaylist = false
                        scope.launch { deletePlaylistNow() }
                    }) { Text("Delete") }
                },
                dismissButton = {
                    HelixTextButton(onClick = { confirmDeletePlaylist = false }) { Text("Cancel") }
                }
            )
        }

        if (confirmBulkRemove) {
            AlertDialog(
                onDismissRequest = { confirmBulkRemove = false },
                title = { Text("Remove selected tracks?") },
                text = { Text("Remove ${selectedTrackIds.size} selected track${if (selectedTrackIds.size == 1) "" else "s"} from this playlist?") },
                confirmButton = {
                    Button(onClick = {
                        confirmBulkRemove = false
                        scope.launch { removeSelectedTracksNow() }
                    }) { Text("Remove") }
                },
                dismissButton = {
                    HelixTextButton(onClick = { confirmBulkRemove = false }) { Text("Cancel") }
                }
            )
        }

        if (removeTarget != null) {
            val tr = removeTarget!!
            AlertDialog(
                onDismissRequest = { removeTarget = null },
                title = { Text("Remove track?") },
                text = { Text("Remove \"${tr.title}\" from this playlist?") },
                confirmButton = {
                    Button(onClick = {
                        removeTarget = null
                        scope.launch { removeTrackNow(tr) }
                    }) { Text("Remove") }
                },
                dismissButton = {
                    HelixTextButton(onClick = { removeTarget = null }) { Text("Cancel") }
                }
            )
        }

        if (showBulkSheet) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
            ModalBottomSheet(
                onDismissRequest = { showBulkSheet = false },
                sheetState = sheetState,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            ) {
                PlaylistBulkActionSheet(
                    selectedCount = selectedTrackIds.size,
                    onClear = {
                        selectedTrackIds = emptySet()
                        showBulkSheet = false
                    },
                    onRemove = {
                        showBulkSheet = false
                        confirmBulkRemove = true
                    },
                    onQueue = {
                        showBulkSheet = false
                        scope.launch { queueTracksNow(selectedTracks) }
                    },
                    onMoveToTop = {
                        showBulkSheet = false
                        moveSelectedToTop()
                    },
                    onMoveDown = {
                        showBulkSheet = false
                        moveSelectedDown()
                    }
                )
            }
        }

        if (showAddOverlay) {
            Dialog(onDismissRequest = { showAddOverlay = false }) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Add songs",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { showAddOverlay = false }) {
                                Icon(Icons.Filled.Close, contentDescription = "Close")
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Search UI overlay. Adding a track should NOT close the overlay.
                        SearchScreen(
                            onOpenAlbum = { /* no-op in overlay */ },
                            onAddToPlaylist = { song ->
                                scope.launch { addSongToPlaylist(song) }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistHeroHeader(
    title: String,
    coverUrl: String,
    trackCount: Int,
    onMenu: () -> Unit,
    showMenu: Boolean,
    onDismissMenu: () -> Unit,
    canEditPlaylist: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        if (coverUrl.isNotBlank()) {
            AsyncImage(
                model = HelixImages.request(LocalContext.current, coverUrl),
                contentDescription = null,
                modifier = Modifier
                    .size(180.dp)
                    .clip(RoundedCornerShape(28.dp))
            )
        } else {
            Spacer(modifier = Modifier.height(180.dp))
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(modifier = Modifier.width(40.dp))
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$trackCount songs",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box {
            IconButton(onClick = onMenu) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = onDismissMenu, shape = HelixMenuShape) {
                if (canEditPlaylist) {
                    DropdownMenuItem(
                        text = { Text("Edit playlist") },
                        onClick = onEdit
                    )
                    DropdownMenuItem(
                        text = { Text("Delete playlist") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = onDelete
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistEditHeader(title: String, onCancel: () -> Unit, onDone: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HelixTextButton(onClick = onCancel) { Text("Cancel") }
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        HelixTextButton(onClick = onDone) { Text("Done") }
    }
}

@Composable
private fun CompactPlaylistHeader(title: String, coverUrl: String, trackCount: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (coverUrl.isNotBlank()) {
            AsyncImage(
                model = HelixImages.request(LocalContext.current, coverUrl),
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "$trackCount songs",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PlaylistTrackRow(
    track: PlaylistTrackUi,
    artUrl: String,
    durationText: String,
    menuOpen: Boolean,
    onRowClick: () -> Unit,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onPlay: () -> Unit,
    onQueue: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRowClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TrackArt(artUrl = artUrl, sizeDp = 50)
            TrackText(track = track, modifier = Modifier.weight(1f))
            if (durationText.isNotBlank()) {
                Text(
                    durationText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(onClick = onOpenMenu) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Track menu")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu, shape = HelixMenuShape) {
                    DropdownMenuItem(text = { Text("Play now") }, onClick = onPlay)
                    DropdownMenuItem(text = { Text("Add to queue") }, onClick = onQueue)
                    DropdownMenuItem(text = { Text("Remove from playlist") }, onClick = onRemove)
                }
            }
        }
    }
}

@Composable
private fun EditablePlaylistTrackRow(
    track: PlaylistTrackUi,
    artUrl: String,
    selected: Boolean,
    durationText: String,
    onToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDragFinished: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragOffsetPx by remember { mutableStateOf(0f) }

    val rowScale by animateFloatAsState(
        targetValue = if (isDragging) 1.035f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "playlistRowDragScale"
    )
    val rowAlpha by animateFloatAsState(
        targetValue = if (isDragging) 0.96f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "playlistRowDragAlpha"
    )
    val handleAlpha by animateFloatAsState(
        targetValue = if (isDragging) 1f else 0.78f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "playlistHandleAlpha"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isDragging) 10f else 0f)
            .graphicsLayer {
                translationY = dragOffsetPx
                scaleX = rowScale
                scaleY = rowScale
                alpha = rowAlpha
                shadowElevation = if (isDragging) 20f else 0f
            }
            .shadow(
                elevation = if (isDragging) 16.dp else 0.dp,
                shape = RoundedCornerShape(18.dp),
                clip = false
            )
            .then(
                if (isDragging) {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                        shape = RoundedCornerShape(18.dp)
                    )
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(16.dp),
        color = when {
            isDragging -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f)
            selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        },
        tonalElevation = if (isDragging) 8.dp else 0.dp,
        shadowElevation = if (isDragging) 10.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
            TrackArt(artUrl = artUrl, sizeDp = 46)
            TrackText(track = track, modifier = Modifier.weight(1f))
            if (durationText.isNotBlank()) {
                Text(
                    durationText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            PlaylistDragHandle(
                enabled = canMoveUp || canMoveDown,
                activeAlpha = handleAlpha,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onDragStarted = {
                    isDragging = true
                    dragOffsetPx = 0f
                },
                onDragOffsetChanged = { dragOffsetPx = it },
                onDragFinished = {
                    isDragging = false
                    dragOffsetPx = 0f
                    onDragFinished()
                },
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
            )
        }
    }
}

@Composable
private fun PlaylistDragHandle(
    enabled: Boolean,
    activeAlpha: Float,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDragStarted: () -> Unit,
    onDragOffsetChanged: (Float) -> Unit,
    onDragFinished: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
) {
    val thresholdPx = with(LocalDensity.current) { 56.dp.toPx() }
    var dragRemainder by remember { mutableStateOf(0f) }

    fun resetDrag() {
        dragRemainder = 0f
        onDragOffsetChanged(0f)
    }

    Box(
        modifier = Modifier
            .width(40.dp)
            .height(54.dp)
            .clip(RoundedCornerShape(14.dp))
            .pointerInput(enabled, canMoveUp, canMoveDown) {
                if (!enabled) return@pointerInput
                detectVerticalDragGestures(
                    onDragStart = {
                        dragRemainder = 0f
                        onDragStarted()
                        onDragOffsetChanged(0f)
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        dragRemainder += dragAmount
                        while (dragRemainder <= -thresholdPx && canMoveUp) {
                            onMoveUp()
                            dragRemainder += thresholdPx
                        }
                        while (dragRemainder >= thresholdPx && canMoveDown) {
                            onMoveDown()
                            dragRemainder -= thresholdPx
                        }
                        onDragOffsetChanged(dragRemainder.coerceIn(-thresholdPx, thresholdPx))
                    },
                    onDragEnd = {
                        resetDrag()
                        onDragFinished()
                    },
                    onDragCancel = {
                        resetDrag()
                        onDragFinished()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.matchParentSize(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.08f else 0.03f),
            content = {}
        )
        Column(
            modifier = Modifier.graphicsLayer { alpha = if (enabled) activeAlpha else 0.28f },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            repeat(3) {
                Surface(
                    modifier = Modifier
                        .width(24.dp)
                        .height(2.5.dp),
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.98f else 0.28f),
                    content = {}
                )
            }
        }
    }
}

@Composable
private fun TrackArt(artUrl: String, sizeDp: Int) {
    if (artUrl.isNotBlank()) {
        AsyncImage(
            model = HelixImages.request(LocalContext.current, artUrl),
            contentDescription = null,
            modifier = Modifier
                .size(sizeDp.dp)
                .clip(RoundedCornerShape(12.dp))
        )
    } else {
        Surface(
            modifier = Modifier.size(sizeDp.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {}
    }
}

@Composable
private fun TrackText(track: PlaylistTrackUi, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val sub = listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" • ")
        if (sub.isNotBlank()) {
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PlaylistEditBottomBar(
    selectedCount: Int,
    canMove: Boolean,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveToTop: () -> Unit,
    onAddSongs: () -> Unit,
    onMore: () -> Unit,
) {
    Surface(
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomBarTextAction(
                label = if (selectedCount > 0) "Remove\n($selectedCount)" else "Remove",
                enabled = selectedCount > 0,
                onClick = onRemove
            )
            BottomBarTextAction(label = "Move\nUp", enabled = canMove, onClick = onMoveUp)
            BottomBarTextAction(label = "Move\nTop", enabled = canMove, onClick = onMoveToTop)
            BottomBarTextAction(label = "Add\nSongs", enabled = true, onClick = onAddSongs)
            BottomBarTextAction(label = "More", enabled = true, onClick = onMore)
        }
    }
}

@Composable
private fun RowScope.BottomBarTextAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Text(label, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun PlaylistBulkActionSheet(
    selectedCount: Int,
    onClear: () -> Unit,
    onRemove: () -> Unit,
    onQueue: () -> Unit,
    onMoveToTop: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "$selectedCount tracks selected",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            HelixTextButton(onClick = onClear) { Text("Clear") }
        }
        BulkSheetRow("Remove from playlist", enabled = selectedCount > 0, onClick = onRemove)
        BulkSheetRow("Add to queue", enabled = selectedCount > 0, onClick = onQueue)
        BulkSheetRow("Move selected to top", enabled = selectedCount > 0, onClick = onMoveToTop)
        BulkSheetRow("Move selected down", enabled = selectedCount > 0, onClick = onMoveDown)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ) {
            Text(
                text = "Tip: hold the white handle. The selected row will lift up and move with your finger while reordering.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(14.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun BulkSheetRow(label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
        )
    }
}

private data class PlaylistDetailParsed(
    val name: String,
    val thumbnailUrl: String,
    val systemKey: String,
    val tracks: List<PlaylistTrackUi>,
)

private fun parsePlaylistDetail(json: String): PlaylistDetailParsed {
    val root = JSONObject(json)
    val pl = root.optJSONObject("playlist") ?: JSONObject()
    val name = pl.optString("name", "Playlist")
    val thumb = pl.optString("thumbnail_url", "")
    val systemKey = pl.optString("system_key", "")
    val arr = root.optJSONArray("tracks") ?: JSONArray()
    val tracks = ArrayList<PlaylistTrackUi>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        tracks.add(
            PlaylistTrackUi(
                id = o.optString("id", ""),
                title = o.optString("title", ""),
                artist = o.optString("artist", ""),
                album = o.optString("album", ""),
                artUrl = o.optString("art_url", ""),
                durationMs = o.optLong("duration_ms", 0L),
                source = o.optString("source", ""),
                subsonicSongId = o.optString("subsonic_song_id", ""),
                ytVideoId = o.optString("yt_video_id", ""),
                ytBrowseId = o.optString("yt_browse_id", ""),
                mbRecordingId = o.optString("mb_recording_id", ""),
                mbArtistId = o.optString("mb_artist_id", ""),
            )
        )
    }
    return PlaylistDetailParsed(name = name, thumbnailUrl = thumb, systemKey = systemKey, tracks = tracks)
}

private fun formatDurationMs(durationMs: Long): String {
    if (durationMs <= 0L) return ""
    val totalSeconds = durationMs / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
