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
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import com.example.helixapp.playback.HelixTransport
import com.example.helixapp.ui.theme.HelixAccent
import com.example.helixapp.ui.theme.HelixBorder
import com.example.helixapp.ui.theme.HelixMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class PlaylistUi(
    val id: String,
    val name: String,
    val systemKey: String,
    val kind: String,
    val trackCount: Int,
    val thumbnailUrl: String,
)

@Composable
fun PlaylistsScreen(
    onOpenPlaylist: (String) -> Unit,
    onNavigateToNowPlaying: () -> Unit = {},
    createRequestKey: Int = 0,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf("Idle") }
    var loading by remember { mutableStateOf(false) }
    var playlists by remember { mutableStateOf(emptyList<PlaylistUi>()) }
    var lastRefreshMs by remember { mutableStateOf(0L) }
    var creating by remember { mutableStateOf(false) }

    val playlistsRefreshTick by RefreshSignals.playlists.collectAsState()

    fun refresh() {
        lastRefreshMs = System.currentTimeMillis()
        if (HelixPrefs.getSessionToken(ctx).isNullOrBlank()) {
            status = "Not logged in — go to Settings"
            playlists = emptyList()
            return
        }
        loading = true
        status = "Loading…"
        scope.launch {
            try {
                val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                val resp = withContext(Dispatchers.IO) { api.listPlaylists() }
                val body = resp.body().orEmpty()
                if (resp.code() == 401) {
                    status = "Unauthorized (401) — session expired? Login again."
                    playlists = emptyList()
                    return@launch
                }
                if (!resp.isSuccessful) {
                    status = "Failed (HTTP ${resp.code()})"
                    playlists = emptyList()
                    return@launch
                }
                playlists = parsePlaylists(body)
                status = if (playlists.isEmpty()) "No playlists" else "Done"
            } catch (e: Exception) {
                status = "Error: ${e.javaClass.simpleName}: ${e.message}"
                playlists = emptyList()
            } finally {
                loading = false
            }
        }
    }

    suspend fun playPlaylistFromList(pl: PlaylistUi, shuffle: Boolean) {
        showLoadingOverlay(if (shuffle) "Shuffling playlist…" else "Starting playlist…")
        try {
            HelixPrefs.setLastStationName(ctx, null)
            val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
            val effectivePlaylistId = pl.systemKey.takeIf { it.isNotBlank() } ?: pl.id
            val mt = "application/json; charset=utf-8".toMediaType()
            val body = JSONObject()
                .put("playlist_id", effectivePlaylistId)
                .put("shuffle", shuffle)
                .toString()
                .toRequestBody(mt)

            val playResp = withContext(Dispatchers.IO) { api.playPlaylist(body) }
            if (!playResp.isSuccessful) {
                status = if (shuffle) "Shuffle failed (HTTP ${playResp.code()})" else "Play failed (HTTP ${playResp.code()})"
                return
            }

            showLoadingOverlay("Loading now playing…")
            HelixTransport.refreshAndPlayCurrent(ctx)
            status = if (shuffle) "Shuffling playlist: ${pl.name}" else "Playing playlist: ${pl.name}"
        } catch (e: Exception) {
            status = if (shuffle) {
                "Shuffle error: ${e.javaClass.simpleName}: ${e.message}"
            } else {
                "Play error: ${e.javaClass.simpleName}: ${e.message}"
            }
        } finally {
            onNavigateToNowPlaying()
            hideLoadingOverlay()
        }
    }

    LaunchedEffect(Unit) { refresh() }

    LaunchedEffect(createRequestKey) {
        if (createRequestKey > 0) creating = true
    }

    LaunchedEffect(playlistsRefreshTick) {
        if (playlistsRefreshTick > 0) refresh()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = System.currentTimeMillis()
                if (now - lastRefreshMs > 30_000L && !loading) {
                    refresh()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val statusMessage = when {
        loading -> "Loading playlists…"
        status.startsWith("Unauthorized") || status.startsWith("Failed") || status.startsWith("Error") || status.startsWith("Not logged") -> status
        playlists.isEmpty() && status == "No playlists" -> "No playlists yet"
        else -> null
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
        } else if (statusMessage != null && playlists.isNotEmpty()) {
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = if (
                    status.startsWith("Unauthorized") ||
                    status.startsWith("Failed") ||
                    status.startsWith("Error") ||
                    status.startsWith("Not logged")
                ) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (playlists.isEmpty() && !loading) {
            Box(modifier = Modifier.padding(top = 12.dp, start = 2.dp)) {
                Text(
                    text = if (status.startsWith("Not logged")) "Log in from Settings to load your playlists." else "You have no playlists yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        onPlay = { scope.launch { playPlaylistFromList(pl, shuffle = false) } },
                        onShuffle = { scope.launch { playPlaylistFromList(pl, shuffle = true) } },
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

    if (creating) {
        PlaylistCreateDialog(
            onDismiss = { creating = false },
            onCreate = { name ->
                scope.launch {
                    try {
                        val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                        val payload = JSONObject().put("name", name).toString()
                        val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
                        val resp: retrofit2.Response<String> = withContext(Dispatchers.IO) {
                            api.createPlaylist(body)
                        }
                        if (!resp.isSuccessful) {
                            status = "Create failed (HTTP ${resp.code()})"
                            return@launch
                        }
                        status = "Created playlist: $name"
                        creating = false
                        refresh()
                    } catch (e: Exception) {
                        status = "Create error: ${e.javaClass.simpleName}: ${e.message}"
                    }
                }
            }
        )
    }
}

@Composable
private fun PlaylistRow(
    playlist: PlaylistUi,
    onOpenPlaylist: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
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

private fun parsePlaylists(json: String): List<PlaylistUi> {
    val arr = JSONArray(json)
    val out = ArrayList<PlaylistUi>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val systemKey = o.optString("system_key", "")
        out.add(
            PlaylistUi(
                id = o.optString("id", ""),
                name = o.optString("name", ""),
                systemKey = systemKey,
                kind = o.optString("kind", ""),
                trackCount = o.optInt("track_count", 0),
                thumbnailUrl = o.optString("thumbnail_url", ""),
            )
        )
    }
    return out
}
