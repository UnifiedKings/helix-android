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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import coil.compose.AsyncImage
import com.example.helixapp.playback.PlaybackActions
import com.example.helixapp.ui.theme.HelixAccent
import com.example.helixapp.ui.theme.HelixBorder
import com.example.helixapp.ui.theme.HelixMuted
import org.json.JSONObject

import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle



@Composable
fun StationsScreen(
    onNavigateToNowPlaying: () -> Unit = {},
    createRequestKey: Int = 0,
    viewModel: StationsViewModel = helixViewModel { StationsViewModel(it) },
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val state by viewModel.state.collectAsStateWithLifecycle()
    val stations = state.stations
    val loading = state.loading
    var tuningStation by remember { mutableStateOf<StationUi?>(null) }
    var creating by remember { mutableStateOf(false) }

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
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (loading) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        } else if (state.error != null && stations.isNotEmpty()) {
            Text(
                text = state.error.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (stations.isEmpty() && !loading) {
            Box(modifier = Modifier.padding(top = 12.dp, start = 2.dp)) {
                Text(
                    text = when {
                        !state.signedIn -> "Log in from Settings to load your stations."
                        state.error != null -> state.error.orEmpty()
                        else -> "You have no stations yet."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.error != null && state.signedIn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn {
                itemsIndexed(stations) { index, station ->
                    StationRow(
                        station = station,
                        provider = state.providerFor(station),
                        baseUrl = HelixPrefs.getBaseUrl(ctx),
                        onTune = { tuningStation = station },
                        onPlay = {
                            scope.launchPlaybackAction(
                                failureAction = "Starting the station",
                                overlayMessage = "Starting station…\nStations can take up to 30 seconds to load.",
                                onSuccess = onNavigateToNowPlaying,
                            ) {
                                PlaybackActions.playStation(ctx, station.id, station.name)
                            }
                        },
                    )
                    if (index < stations.lastIndex) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 68.dp)
                                .height(1.dp)
                                .background(HelixBorder),
                        )
                    }
                }
            }
        }
    }

    tuningStation?.let { station ->
        StationTuneDialog(
            station = station,
            provider = state.providerFor(station),
            onDismiss = { tuningStation = null },
            onSave = { updated, configPayload ->
                tuningStation = null
                viewModel.save(updated, configPayload)
            },
            onDelete = { stationToDelete ->
                tuningStation = null
                viewModel.delete(stationToDelete)
            },
        )
    }

    if (creating) {
        StationCreateDialog(
            providers = state.providers,
            onDismiss = { creating = false },
            onCreate = { payload ->
                creating = false
                viewModel.create(JSONObject(payload))
            },
        )
    }
}

@Composable
private fun StationRow(
    station: StationUi,
    provider: StationProviderUi?,
    baseUrl: String,
    onTune: () -> Unit,
    onPlay: () -> Unit,
) {
    val ctx = LocalContext.current
    val cover = HelixImages.absoluteUrl(baseUrl, station.thumbnailUrl)
    var menuExpanded by remember(station.id) { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlay() }
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = HelixImages.request(ctx, cover),
            contentDescription = null,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = station.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val seed = stationSeedSummary(station)
            if (seed.isNotBlank()) {
                Text(
                    text = seed,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = provider?.displayName ?: station.stationType,
                style = MaterialTheme.typography.labelMedium,
                color = HelixMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onPlay) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = "Play station",
                tint = HelixAccent,
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Station options", tint = HelixMuted)
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                shape = HelixMenuShape,
            ) {
                DropdownMenuItem(
                    text = { Text("Tune station") },
                    onClick = {
                        menuExpanded = false
                        onTune()
                    },
                )
            }
        }
    }
}
