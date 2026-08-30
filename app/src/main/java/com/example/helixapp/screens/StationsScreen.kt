package com.example.helixapp

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import com.example.helixapp.playback.HelixTransport
import com.example.helixapp.ui.theme.HelixAccent
import com.example.helixapp.ui.theme.HelixBorder
import com.example.helixapp.ui.theme.HelixMuted
import com.example.helixapp.ui.theme.HelixSurfaceRaised
import com.example.helixapp.ui.theme.HelixSurfaceSoft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class StationUi(
    val id: String,
    val name: String,
    val stationType: String,
    val config: JSONObject,
    val seedType: String,
    val seedTitle: String,
    val seedArtist: String,
    val discovery: Float,
    val seedInfluence: Float,
    val thumbnailUrl: String,
)

data class StationChoiceUi(
    val value: String,
    val label: String,
)

data class StationConfigOptionUi(
    val key: String,
    val label: String,
    val type: String,
    val description: String,
    val required: Boolean,
    val defaultValue: Any?,
    val min: Double?,
    val max: Double?,
    val step: Double?,
    val choices: List<StationChoiceUi>,
    val minItems: Int?,
    val maxItems: Int?,
    val category: String,
    val categoryLabel: String,
    val categoryOrder: Int,
    val order: Int,
)

data class StationProviderUi(
    val stationType: String,
    val displayName: String,
    val description: String,
    val configOptions: List<StationConfigOptionUi>,
)

private data class StationConfigSectionUi(
    val id: String,
    val label: String,
    val order: Int,
    val options: List<StationConfigOptionUi>,
)

private data class StationArtistSeedUi(
    val name: String,
    val browseId: String,
    val artUrl: String,
    val thumbnailUrl: String,
)

private data class StationTrackSeedUi(
    val title: String,
    val artist: String,
    val album: String,
    val videoId: String,
    val artUrl: String,
    val thumbnailUrl: String,
)

private const val LEGACY_SONG_RADIO_SEED_KEY = "__song_radio_seed"
private const val LEGACY_SIMILAR_ARTIST_SEED_KEY = "__similar_artist_seed"

private fun StationProviderUi.hasSearchOption(type: String): Boolean =
    configOptions.any { it.type == type }

private fun StationProviderUi.androidConfigOptions(): List<StationConfigOptionUi> {
    if (stationType != "artist_collection" || hasSearchOption("artist_search")) return configOptions
    val fallback = StationConfigOptionUi(
        key = "seed_artists",
        label = "Seed artists",
        type = "artist_search",
        description = "Search YouTube Music and choose the artists this station is allowed to play.",
        required = true,
        defaultValue = JSONArray(),
        min = null,
        max = null,
        step = null,
        choices = emptyList(),
        minItems = 1,
        maxItems = 100,
        category = "seeds",
        categoryLabel = "Seeds",
        categoryOrder = 10,
        order = 0,
    )
    return listOf(fallback) + configOptions.filterNot { it.key == "seed_artists" }
}

@Composable
fun StationsScreen(
    onNavigateToNowPlaying: () -> Unit = {},
    createRequestKey: Int = 0,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf("Idle") }
    var loading by remember { mutableStateOf(false) }
    var stations by remember { mutableStateOf(emptyList<StationUi>()) }
    var providers by remember { mutableStateOf(emptyList<StationProviderUi>()) }
    var lastRefreshMs by remember { mutableStateOf(0L) }
    var tuningStation by remember { mutableStateOf<StationUi?>(null) }
    var creating by remember { mutableStateOf(false) }

    suspend fun waitForNowPlayingReady(timeoutMs: Long): Boolean {
        val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            val resp = withContext(Dispatchers.IO) { api.playerState() }
            if (resp.isSuccessful) {
                val body = resp.body().orEmpty()
                val root = JSONObject(body)
                val now = root.optJSONObject("now_playing")
                val qid = now?.optString("id", now.optString("queue_item_id", "")) ?: ""
                if (qid.isNotBlank()) return true
            }
            delay(750)
        }
        return false
    }

    fun refresh() {
        lastRefreshMs = System.currentTimeMillis()
        if (HelixPrefs.getSessionToken(ctx).isNullOrBlank()) {
            status = "Not logged in — go to Settings"
            stations = emptyList()
            providers = emptyList()
            return
        }

        loading = true
        status = "Loading…"
        scope.launch {
            try {
                val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                val typesResp = withContext(Dispatchers.IO) { api.listStationTypes() }
                if (typesResp.isSuccessful) {
                    providers = parseStationProviders(typesResp.body().orEmpty())
                }
                val stationsResp = withContext(Dispatchers.IO) { api.listStations() }
                val body = stationsResp.body().orEmpty()
                if (stationsResp.code() == 401) {
                    status = "Unauthorized (401) — session expired? Login again."
                    stations = emptyList()
                    return@launch
                }
                if (!stationsResp.isSuccessful) {
                    status = "Failed (HTTP ${stationsResp.code()})"
                    stations = emptyList()
                    return@launch
                }
                stations = parseStations(body)
                status = if (stations.isEmpty()) "No stations" else "Done"
            } catch (e: Exception) {
                status = "Error: ${e.javaClass.simpleName}: ${e.message}"
                stations = emptyList()
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(createRequestKey) {
        if (createRequestKey > 0) creating = true
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = System.currentTimeMillis()
                if (now - lastRefreshMs > 30_000L && !loading) refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val statusMessage = when {
        loading -> "Loading stations…"
        status.startsWith("Unauthorized") ||
            status.startsWith("Failed") ||
            status.startsWith("Error") ||
            status.startsWith("Not logged") -> status
        stations.isEmpty() && status == "No stations" -> "No stations yet"
        else -> null
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
        } else if (statusMessage != null && stations.isNotEmpty()) {
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

        if (stations.isEmpty() && !loading) {
            Box(modifier = Modifier.padding(top = 12.dp, start = 2.dp)) {
                Text(
                    text = if (status.startsWith("Not logged")) {
                        "Log in from Settings to load your stations."
                    } else {
                        "You have no stations yet."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn {
                itemsIndexed(stations) { index, station ->
                    StationRow(
                        station = station,
                        provider = providers.firstOrNull { it.stationType == station.stationType },
                        baseUrl = HelixPrefs.getBaseUrl(ctx),
                        onTune = { tuningStation = station },
                        onPlay = {
                            scope.launch {
                                showLoadingOverlay(
                                    "Starting station…\nStations can take up to 30 seconds to load."
                                )
                                try {
                                    val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                                    val payload = JSONObject().put("reset", true).toString()
                                    val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
                                    val resp = withContext(Dispatchers.IO) { api.playStation(station.id, body) }
                                    if (!resp.isSuccessful) {
                                        status = "Play failed (HTTP ${resp.code()})"
                                        return@launch
                                    }
                                    showLoadingOverlay(
                                        "Building station…\nStations can take up to 30 seconds to load."
                                    )
                                    val ready = waitForNowPlayingReady(timeoutMs = 30_000L)
                                    if (!ready) {
                                        status = "Station took too long to load"
                                        return@launch
                                    }
                                    showLoadingOverlay("Loading now playing…")
                                    withContext(Dispatchers.IO) { api.resume() }
                                    HelixTransport.refreshAndPlayCurrent(ctx, forceRestart = true)
                                    status = "Playing station: ${station.name}"
                                } catch (e: Exception) {
                                    status = "Play error: ${e.javaClass.simpleName}: ${e.message}"
                                } finally {
                                    onNavigateToNowPlaying()
                                    hideLoadingOverlay()
                                }
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
            provider = providers.firstOrNull { it.stationType == station.stationType },
            onDismiss = { tuningStation = null },
            onSave = { updated, configPayload ->
                stations = stations.map { if (it.id == updated.id) updated else it }
                tuningStation = null
                scope.launch {
                    try {
                        val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                        val payload = JSONObject()
                            .put("name", updated.name)
                            .put("config", configPayload)
                        addLegacyStationMirrors(payload, configPayload)
                        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                        val resp = withContext(Dispatchers.IO) { api.updateStation(updated.id, body) }
                        if (!resp.isSuccessful) {
                            status = "Save failed (HTTP ${resp.code()})"
                            refresh()
                        } else {
                            status = "Saved ✅"
                            refresh()
                        }
                    } catch (e: Exception) {
                        status = "Save error: ${e.javaClass.simpleName}: ${e.message}"
                        refresh()
                    }
                }
            },
            onDelete = { stationToDelete ->
                stations = stations.filterNot { it.id == stationToDelete.id }
                tuningStation = null
                scope.launch {
                    try {
                        val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                        val resp = withContext(Dispatchers.IO) { api.deleteStation(stationToDelete.id) }
                        if (!resp.isSuccessful) {
                            status = "Delete failed (HTTP ${resp.code()})"
                            refresh()
                        } else {
                            status = "Deleted ✅"
                            refresh()
                        }
                    } catch (e: Exception) {
                        status = "Delete error: ${e.javaClass.simpleName}: ${e.message}"
                        refresh()
                    }
                }
            },
        )
    }

    if (creating) {
        StationCreateDialog(
            providers = providers,
            onDismiss = { creating = false },
            onCreate = { payload ->
                creating = false
                scope.launch {
                    try {
                        val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                        val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
                        val resp = withContext(Dispatchers.IO) { api.createStation(body) }
                        if (!resp.isSuccessful) {
                            status = "Create failed (HTTP ${resp.code()})"
                        } else {
                            status = "Created ✅"
                            refresh()
                        }
                    } catch (e: Exception) {
                        status = "Create error: ${e.javaClass.simpleName}: ${e.message}"
                    }
                }
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

@Composable
private fun StationTuneDialog(
    station: StationUi,
    provider: StationProviderUi?,
    onDismiss: () -> Unit,
    onSave: (StationUi, JSONObject) -> Unit,
    onDelete: (StationUi) -> Unit,
) {
    var name by remember(station.id) { mutableStateOf(station.name) }
    val values = remember(station.id) { mutableStateMapOf<String, String>() }
    val boolValues = remember(station.id) { mutableStateMapOf<String, Boolean>() }
    val multiValues = remember(station.id) { mutableStateMapOf<String, Set<String>>() }
    val artistValues = remember(station.id) { mutableStateMapOf<String, List<StationArtistSeedUi>>() }
    val trackValues = remember(station.id) { mutableStateMapOf<String, List<StationTrackSeedUi>>() }
    var menuExpanded by remember(station.id) { mutableStateOf(false) }
    var confirmDelete by remember(station.id) { mutableStateOf(false) }

    val options = provider?.androidConfigOptions().orEmpty()
    val useLegacySongSeed = provider?.let { it.stationType == "song_radio" && it.hasSearchOption("track_search").not() } ?: false
    val useLegacySimilarArtistSeed = provider?.let { it.stationType == "similar_artist" && it.hasSearchOption("artist_search").not() } ?: false

    LaunchedEffect(station.id, provider?.stationType) {
        values.clear()
        boolValues.clear()
        multiValues.clear()
        artistValues.clear()
        trackValues.clear()
        options.forEach { option ->
            seedOptionState(option, station.config, values, boolValues, multiValues, artistValues, trackValues)
        }
        if (useLegacySongSeed) {
            trackValues[LEGACY_SONG_RADIO_SEED_KEY] = parseTrackSeedSelections(station.config, null).take(1)
        }
        if (useLegacySimilarArtistSeed) {
            artistValues[LEGACY_SIMILAR_ARTIST_SEED_KEY] = parseArtistSeedSelections(station.config, null).take(1)
        }
    }

    val configPayload = buildConfigPayload(options, values, boolValues, multiValues, artistValues, trackValues)
    val hasSpecialSeed = (!useLegacySongSeed || trackValues[LEGACY_SONG_RADIO_SEED_KEY].orEmpty().isNotEmpty()) &&
        (!useLegacySimilarArtistSeed || artistValues[LEGACY_SIMILAR_ARTIST_SEED_KEY].orEmpty().isNotEmpty())
    val canSave = name.trim().isNotBlank() && hasRequiredOptions(options, configPayload) && hasSpecialSeed

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .widthIn(max = 640.dp),
            shape = RoundedCornerShape(18.dp),
            color = HelixSurfaceRaised,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 8.dp, top = 16.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text("Tune station", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            provider?.displayName ?: station.stationType,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            shape = HelixMenuShape,
                        ) {
                            DropdownMenuItem(
                                text = { Text("Delete station") },
                                onClick = {
                                    menuExpanded = false
                                    confirmDelete = true
                                },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            )
                        }
                    }
                }

                StationFormContent(
                    showNameField = true,
                    name = name,
                    onNameChange = { name = it },
                    provider = provider,
                    options = options,
                    values = values,
                    boolValues = boolValues,
                    multiValues = multiValues,
                    artistValues = artistValues,
                    trackValues = trackValues,
                    useLegacySongSeed = useLegacySongSeed,
                    useLegacySimilarArtistSeed = useLegacySimilarArtistSeed,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp),
                )

                HorizontalDivider(color = HelixBorder)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HelixTextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        onClick = {
                            val mergedConfig = JSONObject(station.config.toString())
                            options.forEach { option ->
                                mergedConfig.put(option.key, configPayload.opt(option.key))
                            }
                            applyLegacySearchSeeds(
                                provider = provider,
                                config = mergedConfig,
                                artistValues = artistValues,
                                trackValues = trackValues,
                            )
                            onSave(
                                station.copy(name = name.trim(), config = mergedConfig),
                                mergedConfig,
                            )
                        },
                        enabled = canSave,
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete station?") },
            text = { Text("Are you sure you want to delete \"${station.name}\"?") },
            confirmButton = {
                HelixTextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete(station)
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                HelixTextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun StationCreateDialog(
    providers: List<StationProviderUi>,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    val usableProviders = providers.ifEmpty {
        listOf(
            StationProviderUi(
                stationType = "listenbrainz_similar_artist",
                displayName = "Similar Artist Radio",
                description = "Uses ListenBrainz similar artists and top recordings.",
                configOptions = emptyList(),
            )
        )
    }
    var name by remember { mutableStateOf("") }
    var selectedProviderType by remember(usableProviders) { mutableStateOf(usableProviders.first().stationType) }
    val selectedProvider = usableProviders.firstOrNull { it.stationType == selectedProviderType } ?: usableProviders.first()
    var providerMenuExpanded by remember { mutableStateOf(false) }
    val values = remember(selectedProviderType) { mutableStateMapOf<String, String>() }
    val boolValues = remember(selectedProviderType) { mutableStateMapOf<String, Boolean>() }
    val multiValues = remember(selectedProviderType) { mutableStateMapOf<String, Set<String>>() }
    val artistValues = remember(selectedProviderType) { mutableStateMapOf<String, List<StationArtistSeedUi>>() }
    val trackValues = remember(selectedProviderType) { mutableStateMapOf<String, List<StationTrackSeedUi>>() }

    val options = selectedProvider.androidConfigOptions()
    val useLegacySongSeed = selectedProvider.stationType == "song_radio" && selectedProvider.hasSearchOption("track_search").not()
    val useLegacySimilarArtistSeed = selectedProvider.stationType == "similar_artist" && selectedProvider.hasSearchOption("artist_search").not()

    LaunchedEffect(selectedProviderType) {
        values.clear()
        boolValues.clear()
        multiValues.clear()
        artistValues.clear()
        trackValues.clear()
        options.forEach { option ->
            seedOptionState(option, JSONObject(), values, boolValues, multiValues, artistValues, trackValues)
        }
        if (useLegacySongSeed) trackValues[LEGACY_SONG_RADIO_SEED_KEY] = emptyList()
        if (useLegacySimilarArtistSeed) artistValues[LEGACY_SIMILAR_ARTIST_SEED_KEY] = emptyList()
    }

    val configPayload = buildConfigPayload(
        options,
        values,
        boolValues,
        multiValues,
        artistValues,
        trackValues,
    )
    val hasSpecialSeed = (!useLegacySongSeed || trackValues[LEGACY_SONG_RADIO_SEED_KEY].orEmpty().isNotEmpty()) &&
        (!useLegacySimilarArtistSeed || artistValues[LEGACY_SIMILAR_ARTIST_SEED_KEY].orEmpty().isNotEmpty())
    val canCreate = name.trim().isNotBlank() && hasRequiredOptions(options, configPayload) && hasSpecialSeed

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .widthIn(max = 560.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        shape = RoundedCornerShape(18.dp),
        containerColor = HelixSurfaceRaised,
        title = {
            Text("Create station", style = MaterialTheme.typography.headlineSmall)
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 600.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = HelixSurfaceSoft,
                    border = BorderStroke(1.dp, HelixBorder),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "Choose station type",
                            style = MaterialTheme.typography.labelLarge,
                            color = HelixAccent,
                        )
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Station name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Box {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { providerMenuExpanded = true },
                                color = HelixSurfaceRaised,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, HelixBorder),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(selectedProvider.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (selectedProvider.description.isNotBlank()) {
                                            Text(
                                                selectedProvider.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    Text("▾", color = HelixMuted)
                                }
                            }
                            DropdownMenu(
                                expanded = providerMenuExpanded,
                                onDismissRequest = { providerMenuExpanded = false },
                                shape = HelixMenuShape,
                            ) {
                                usableProviders.forEach { provider ->
                                    DropdownMenuItem(
                                        text = { Text(provider.displayName) },
                                        onClick = {
                                            selectedProviderType = provider.stationType
                                            providerMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                StationFormContent(
                    showNameField = false,
                    name = name,
                    onNameChange = {},
                    provider = selectedProvider,
                    options = options,
                    values = values,
                    boolValues = boolValues,
                    multiValues = multiValues,
                    artistValues = artistValues,
                    trackValues = trackValues,
                    useLegacySongSeed = useLegacySongSeed,
                    useLegacySimilarArtistSeed = useLegacySimilarArtistSeed,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalConfig = JSONObject(configPayload.toString())
                    applyLegacySearchSeeds(
                        provider = selectedProvider,
                        config = finalConfig,
                        artistValues = artistValues,
                        trackValues = trackValues,
                    )
                    val payload = JSONObject()
                        .put("name", name.trim())
                        .put("station_type", selectedProvider.stationType)
                        .put("config", finalConfig)
                        .put("seed_type", deriveSeedType(finalConfig))
                    val seedArtist = seedArtistFromConfig(finalConfig)
                    payload.put("seed_artist", seedArtist)
                    payload.put("seed_title", seedTitleFromConfig(finalConfig))
                    addLegacyStationMirrors(payload, finalConfig)
                    onCreate(payload.toString())
                },
                enabled = canCreate,
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            HelixTextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun StationFormContent(
    showNameField: Boolean,
    name: String,
    onNameChange: (String) -> Unit,
    provider: StationProviderUi?,
    options: List<StationConfigOptionUi>,
    values: MutableMap<String, String>,
    boolValues: MutableMap<String, Boolean>,
    multiValues: MutableMap<String, Set<String>>,
    artistValues: MutableMap<String, List<StationArtistSeedUi>>,
    trackValues: MutableMap<String, List<StationTrackSeedUi>>,
    useLegacySongSeed: Boolean,
    useLegacySimilarArtistSeed: Boolean,
    modifier: Modifier = Modifier,
) {
    val priorityOptions = remember(options) {
        options.filter { it.type == "artist_search" || it.type == "track_search" }
    }
    val regularOptions = remember(options) {
        options.filterNot { it.type == "artist_search" || it.type == "track_search" }
            .filterNot { useLegacySimilarArtistSeed && it.key == "seed_artist" }
    }
    val sections = remember(regularOptions) { groupStationOptions(regularOptions) }
    val prioritySections = remember(priorityOptions) { groupStationOptions(priorityOptions) }

    Column(
        modifier = modifier
            .heightIn(max = 590.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (showNameField) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = HelixSurfaceSoft,
                border = BorderStroke(1.dp, HelixBorder),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("General", style = MaterialTheme.typography.labelLarge, color = HelixAccent)
                    OutlinedTextField(
                        value = name,
                        onValueChange = onNameChange,
                        label = { Text("Station name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        if (useLegacySongSeed) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = HelixSurfaceSoft,
                border = BorderStroke(1.dp, HelixBorder),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("Seeds", style = MaterialTheme.typography.labelLarge, color = HelixAccent)
                    TrackSearchConfigField(
                        option = legacySongRadioSeedOption(),
                        trackValues = trackValues,
                    )
                }
            }
        } else if (useLegacySimilarArtistSeed) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = HelixSurfaceSoft,
                border = BorderStroke(1.dp, HelixBorder),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("Seeds", style = MaterialTheme.typography.labelLarge, color = HelixAccent)
                    ArtistSearchConfigField(
                        option = legacySimilarArtistSeedOption(),
                        artistValues = artistValues,
                    )
                }
            }
        }

        prioritySections.forEach { section ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = HelixSurfaceSoft,
                border = BorderStroke(1.dp, HelixBorder),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(section.label, style = MaterialTheme.typography.labelLarge, color = HelixAccent)
                    section.options.forEachIndexed { index, option ->
                        StationConfigField(
                            option = option,
                            values = values,
                            boolValues = boolValues,
                            multiValues = multiValues,
                            artistValues = artistValues,
                            trackValues = trackValues,
                        )
                        if (index < section.options.lastIndex) HorizontalDivider(color = HelixBorder)
                    }
                }
            }
        }

        provider?.description?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                description,
                modifier = Modifier.padding(horizontal = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (sections.isEmpty() && prioritySections.isEmpty() && !useLegacySongSeed && !useLegacySimilarArtistSeed) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = HelixSurfaceSoft,
                border = BorderStroke(1.dp, HelixBorder),
            ) {
                Text(
                    text = "This station type does not expose configurable settings.",
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            sections.forEach { section ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = HelixSurfaceSoft,
                    border = BorderStroke(1.dp, HelixBorder),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(section.label, style = MaterialTheme.typography.labelLarge, color = HelixAccent)
                        section.options.forEachIndexed { index, option ->
                            StationConfigField(
                                option = option,
                                values = values,
                                boolValues = boolValues,
                                multiValues = multiValues,
                                artistValues = artistValues,
                                trackValues = trackValues,
                            )
                            if (index < section.options.lastIndex) HorizontalDivider(color = HelixBorder)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StationConfigField(
    option: StationConfigOptionUi,
    values: MutableMap<String, String>,
    boolValues: MutableMap<String, Boolean>,
    multiValues: MutableMap<String, Set<String>>,
    artistValues: MutableMap<String, List<StationArtistSeedUi>>,
    trackValues: MutableMap<String, List<StationTrackSeedUi>>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (option.type) {
            "boolean" -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(option.label, style = MaterialTheme.typography.titleMedium)
                    Checkbox(
                        checked = boolValues[option.key] ?: option.defaultAsBoolean(),
                        onCheckedChange = { boolValues[option.key] = it },
                    )
                }
            }

            "number", "integer" -> {
                val current = values[option.key].orEmpty()
                val min = option.min
                val max = option.max
                val parsed = current.toFloatOrNull()
                if (min != null && max != null && parsed != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(option.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            formatNumberForDisplay(parsed.toDouble(), option.type),
                            style = MaterialTheme.typography.labelLarge,
                            color = HelixAccent,
                        )
                    }
                    Slider(
                        value = parsed.coerceIn(min.toFloat(), max.toFloat()),
                        onValueChange = { newValue ->
                            values[option.key] = if (option.type == "integer") {
                                newValue.toInt().toString()
                            } else {
                                trimFloatString(newValue)
                            }
                        },
                        valueRange = min.toFloat()..max.toFloat(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            formatNumberForDisplay(min, option.type),
                            style = MaterialTheme.typography.labelMedium,
                            color = HelixMuted,
                        )
                        Text(
                            formatNumberForDisplay(max, option.type),
                            style = MaterialTheme.typography.labelMedium,
                            color = HelixMuted,
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = current,
                        onValueChange = { values[option.key] = it },
                        label = { Text(option.label) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            "select" -> {
                var expanded by remember(option.key) { mutableStateOf(false) }
                val current = values[option.key].orEmpty()
                val currentLabel = option.choices.firstOrNull { it.value == current }?.label ?: current.ifBlank { "Choose…" }
                Text(option.label, style = MaterialTheme.typography.titleMedium)
                Box {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .clickable { expanded = true },
                        color = HelixSurfaceRaised,
                        shape = RoundedCornerShape(9.dp),
                        border = BorderStroke(1.dp, HelixBorder),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                currentLabel,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text("▾", color = HelixMuted)
                        }
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        shape = HelixMenuShape,
                    ) {
                        option.choices.forEach { choice ->
                            DropdownMenuItem(
                                text = { Text(choice.label) },
                                onClick = {
                                    values[option.key] = choice.value
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }

            "multiselect" -> {
                Text(option.label, style = MaterialTheme.typography.titleMedium)
                val selected = multiValues[option.key] ?: emptySet()
                option.choices.forEach { choice ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = selected.contains(choice.value),
                            onCheckedChange = { checked ->
                                val next = selected.toMutableSet()
                                if (checked) next.add(choice.value) else next.remove(choice.value)
                                multiValues[option.key] = next
                            },
                        )
                        Text(choice.label)
                    }
                }
            }

            "artist_search" -> {
                ArtistSearchConfigField(
                    option = option,
                    artistValues = artistValues,
                )
            }

            "track_search" -> {
                TrackSearchConfigField(
                    option = option,
                    trackValues = trackValues,
                )
            }

            else -> {
                OutlinedTextField(
                    value = values[option.key].orEmpty(),
                    onValueChange = { values[option.key] = it },
                    label = { Text(option.label) },
                    singleLine = option.type != "textarea",
                    minLines = if (option.type == "textarea") 3 else 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        option.description.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ArtistSearchConfigField(
    option: StationConfigOptionUi,
    artistValues: MutableMap<String, List<StationArtistSeedUi>>,
) {
    val ctx = LocalContext.current
    val baseUrl = HelixPrefs.getBaseUrl(ctx)
    val maxItems = option.maxItems ?: Int.MAX_VALUE
    val minItems = option.minItems ?: if (option.required) 1 else 0
    val selected = artistValues[option.key] ?: emptyList()

    var query by remember(option.key) { mutableStateOf("") }
    var loading by remember(option.key) { mutableStateOf(false) }
    var results by remember(option.key) { mutableStateOf(emptyList<SearchArtist>()) }
    var error by remember(option.key) { mutableStateOf("") }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            results = emptyList()
            error = ""
            return@LaunchedEffect
        }
        delay(350)
        if (q != query.trim()) return@LaunchedEffect
        loading = true
        error = ""
        try {
            val api = HelixClient.create(ctx, baseUrl)
            val resp = withContext(Dispatchers.IO) { api.ytmusicSearchArtists(q) }
            if (!resp.isSuccessful) {
                error = "Artist search failed (HTTP ${resp.code()})"
                results = emptyList()
            } else {
                val existingKeys = selected.map { artistSeedKey(it) }.toSet()
                results = parseArtistSearchResults(resp.body().orEmpty())
                    .filter { artistSeedKey(it) !in existingKeys }
                    .take(8)
            }
        } catch (e: Exception) {
            error = "Artist search error: ${e.javaClass.simpleName}"
            results = emptyList()
        } finally {
            loading = false
        }
    }

    Text(option.label, style = MaterialTheme.typography.titleMedium)
    SelectionCountHint(count = selected.size, minItems = minItems, maxItems = maxItems)

    if (selected.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            selected.forEach { artist ->
                SelectedArtistRow(
                    artist = artist,
                    baseUrl = baseUrl,
                    onRemove = {
                        artistValues[option.key] = selected.filterNot { artistSeedKey(it) == artistSeedKey(artist) }
                    },
                )
            }
        }
    }

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text("Search artists") },
        placeholder = { Text("Find seed artists") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { query = "" }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear")
                }
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    if (loading) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
    } else if (error.isNotBlank()) {
        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    } else if (query.trim().length >= 2 && results.isEmpty()) {
        Text(
            text = if (selected.size >= maxItems) {
                "Selection limit reached. Remove an artist to add another."
            } else {
                "No artist matches found."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (results.isNotEmpty() && selected.size < maxItems) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = HelixSurfaceRaised,
            border = BorderStroke(1.dp, HelixBorder),
        ) {
            Column {
                results.forEachIndexed { index, artist ->
                    SearchArtistResultRow(
                        artist = artist,
                        baseUrl = baseUrl,
                        onAdd = {
                            if (selected.size < maxItems) {
                                val next = selected + StationArtistSeedUi(
                                name = artist.name,
                                browseId = artist.browseId,
                                artUrl = artist.thumbnailUrl,
                                thumbnailUrl = artist.thumbnailUrl,
                            )
                                artistValues[option.key] = next.distinctBy { artistSeedKey(it) }.take(maxItems)
                                query = ""
                                results = emptyList()
                            }
                        },
                    )
                    if (index < results.lastIndex) HorizontalDivider(color = HelixBorder)
                }
            }
        }
    }
}

@Composable
private fun TrackSearchConfigField(
    option: StationConfigOptionUi,
    trackValues: MutableMap<String, List<StationTrackSeedUi>>,
) {
    val ctx = LocalContext.current
    val baseUrl = HelixPrefs.getBaseUrl(ctx)
    val maxItems = option.maxItems ?: Int.MAX_VALUE
    val minItems = option.minItems ?: if (option.required) 1 else 0
    val selected = trackValues[option.key] ?: emptyList()

    var query by remember(option.key) { mutableStateOf("") }
    var loading by remember(option.key) { mutableStateOf(false) }
    var results by remember(option.key) { mutableStateOf(emptyList<SearchSong>()) }
    var error by remember(option.key) { mutableStateOf("") }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            results = emptyList()
            error = ""
            return@LaunchedEffect
        }
        delay(350)
        if (q != query.trim()) return@LaunchedEffect
        loading = true
        error = ""
        try {
            val api = HelixClient.create(ctx, baseUrl)
            val resp = withContext(Dispatchers.IO) { api.ytmusicSearch(q) }
            if (!resp.isSuccessful) {
                error = "Track search failed (HTTP ${resp.code()})"
                results = emptyList()
            } else {
                val existingKeys = selected.map { trackSeedKey(it) }.toSet()
                results = parseTrackSearchResults(resp.body().orEmpty())
                    .filter { trackSeedKey(it) !in existingKeys }
                    .take(8)
            }
        } catch (e: Exception) {
            error = "Track search error: ${e.javaClass.simpleName}"
            results = emptyList()
        } finally {
            loading = false
        }
    }

    Text(option.label, style = MaterialTheme.typography.titleMedium)
    SelectionCountHint(count = selected.size, minItems = minItems, maxItems = maxItems)

    if (selected.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            selected.forEach { track ->
                SelectedTrackRow(
                    track = track,
                    baseUrl = baseUrl,
                    onRemove = {
                        trackValues[option.key] = selected.filterNot { trackSeedKey(it) == trackSeedKey(track) }
                    },
                )
            }
        }
    }

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text("Search tracks") },
        placeholder = { Text("Find reference tracks") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { query = "" }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear")
                }
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    if (loading) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
    } else if (error.isNotBlank()) {
        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    } else if (query.trim().length >= 2 && results.isEmpty()) {
        Text(
            text = if (selected.size >= maxItems) {
                "Selection limit reached. Remove a track to add another."
            } else {
                "No track matches found."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (results.isNotEmpty() && selected.size < maxItems) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = HelixSurfaceRaised,
            border = BorderStroke(1.dp, HelixBorder),
        ) {
            Column {
                results.forEachIndexed { index, song ->
                    SearchTrackResultRow(
                        song = song,
                        baseUrl = baseUrl,
                        onAdd = {
                            if (selected.size < maxItems) {
                                val next = selected + StationTrackSeedUi(
                                title = song.title,
                                artist = song.artist,
                                album = song.album,
                                videoId = song.videoId,
                                artUrl = song.thumbnailUrl,
                                thumbnailUrl = song.thumbnailUrl,
                            )
                                trackValues[option.key] = next.distinctBy { trackSeedKey(it) }.take(maxItems)
                                query = ""
                                results = emptyList()
                            }
                        },
                    )
                    if (index < results.lastIndex) HorizontalDivider(color = HelixBorder)
                }
            }
        }
    }
}

@Composable
private fun SelectionCountHint(
    count: Int,
    minItems: Int,
    maxItems: Int,
) {
    val maxLabel = if (maxItems == Int.MAX_VALUE) "any number" else maxItems.toString()
    val minText = if (minItems > 0) "Min $minItems" else "Optional"
    Text(
        text = "$count selected • $minText • Max $maxLabel",
        style = MaterialTheme.typography.labelMedium,
        color = HelixMuted,
    )
}

@Composable
private fun SelectedArtistRow(
    artist: StationArtistSeedUi,
    baseUrl: String,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = HelixSurfaceRaised,
        border = BorderStroke(1.dp, HelixBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AsyncImage(
                model = HelixImages.request(LocalContext.current, HelixImages.absoluteUrl(baseUrl, artist.thumbnailUrl)),
                contentDescription = null,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Text(
                text = artist.name,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = "Remove artist")
            }
        }
    }
}

@Composable
private fun SelectedTrackRow(
    track: StationTrackSeedUi,
    baseUrl: String,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = HelixSurfaceRaised,
        border = BorderStroke(1.dp, HelixBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AsyncImage(
                model = HelixImages.request(LocalContext.current, HelixImages.absoluteUrl(baseUrl, track.thumbnailUrl)),
                contentDescription = null,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val subtitle = listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" • ")
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
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = "Remove track")
            }
        }
    }
}

@Composable
private fun SearchArtistResultRow(
    artist: SearchArtist,
    baseUrl: String,
    onAdd: () -> Unit,
) {
    val ctx = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onAdd() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AsyncImage(
            model = HelixImages.request(ctx, HelixImages.absoluteUrl(baseUrl, artist.thumbnailUrl)),
            contentDescription = null,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val secondary = artist.subscriberCount.ifBlank { artist.monthlyListeners }
            if (secondary.isNotBlank()) {
                Text(
                    secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(Icons.Default.Add, contentDescription = "Add artist", tint = HelixAccent)
    }
}

@Composable
private fun SearchTrackResultRow(
    song: SearchSong,
    baseUrl: String,
    onAdd: () -> Unit,
) {
    val ctx = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onAdd() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AsyncImage(
            model = HelixImages.request(ctx, HelixImages.absoluteUrl(baseUrl, song.thumbnailUrl)),
            contentDescription = null,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        }
        Icon(Icons.Default.Add, contentDescription = "Add track", tint = HelixAccent)
    }
}

private fun parseStations(json: String): List<StationUi> {
    val arr = JSONArray(json)
    val out = ArrayList<StationUi>(arr.length())
    for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val config = obj.optJSONObject("config") ?: JSONObject()
        out.add(
            StationUi(
                id = obj.optString("id", ""),
                name = obj.optString("name", ""),
                stationType = obj.optString("station_type", "listenbrainz_similar_artist"),
                config = config,
                seedType = config.optString("seed_type", obj.optString("seed_type", "")),
                seedTitle = config.optString("seed_title", obj.optString("seed_title", "")),
                seedArtist = config.optString("seed_artist", obj.optString("seed_artist", "")),
                discovery = config.optDouble("discovery", obj.optDouble("discovery", 0.35)).toFloat(),
                seedInfluence = config.optDouble("seed_influence", obj.optDouble("seed_influence", 0.75)).toFloat(),
                thumbnailUrl = obj.optString("thumbnail_url", ""),
            )
        )
    }
    return out
}

private fun parseStationProviders(json: String): List<StationProviderUi> {
    val arr = JSONArray(json)
    val out = ArrayList<StationProviderUi>(arr.length())
    for (i in 0 until arr.length()) {
        val providerObj = arr.optJSONObject(i) ?: continue
        val optionsArr = providerObj.optJSONArray("config_options") ?: JSONArray()
        val options = ArrayList<StationConfigOptionUi>(optionsArr.length())
        for (j in 0 until optionsArr.length()) {
            val opt = optionsArr.optJSONObject(j) ?: continue
            val choicesArr = opt.optJSONArray("choices") ?: JSONArray()
            val choices = ArrayList<StationChoiceUi>(choicesArr.length())
            for (k in 0 until choicesArr.length()) {
                val choice = choicesArr.optJSONObject(k) ?: continue
                val value = choice.optString("value", "")
                choices.add(StationChoiceUi(value = value, label = choice.optString("label", value)))
            }
            options.add(
                StationConfigOptionUi(
                    key = opt.optString("key", ""),
                    label = opt.optString("label", opt.optString("key", "")),
                    type = opt.optString("type", "string"),
                    description = opt.optString("description", ""),
                    required = opt.optBoolean("required", false),
                    defaultValue = opt.opt("default"),
                    min = opt.optNullableDouble("min"),
                    max = opt.optNullableDouble("max"),
                    step = opt.optNullableDouble("step"),
                    choices = choices,
                    minItems = opt.optNullableInt("min_items"),
                    maxItems = opt.optNullableInt("max_items"),
                    category = opt.optString("category", "").ifBlank { "options" },
                    categoryLabel = opt.optString("category_label", ""),
                    categoryOrder = opt.optNullableInt("category_order") ?: 999,
                    order = opt.optNullableInt("order") ?: 999,
                )
            )
        }
        out.add(
            StationProviderUi(
                stationType = providerObj.optString("station_type", ""),
                displayName = providerObj.optString("display_name", providerObj.optString("station_type", "")),
                description = providerObj.optString("description", ""),
                configOptions = options.filter { it.key.isNotBlank() },
            )
        )
    }
    return out.filter { it.stationType.isNotBlank() }
}

private fun legacySongRadioSeedOption() = StationConfigOptionUi(
    key = LEGACY_SONG_RADIO_SEED_KEY,
    label = "Seed song",
    type = "track_search",
    description = "Search YouTube Music and choose the exact song this radio should be built around.",
    required = true,
    defaultValue = JSONArray(),
    min = null,
    max = null,
    step = null,
    choices = emptyList(),
    minItems = 1,
    maxItems = 1,
    category = "seeds",
    categoryLabel = "Seeds",
    categoryOrder = 10,
    order = 0,
)

private fun legacySimilarArtistSeedOption() = StationConfigOptionUi(
    key = LEGACY_SIMILAR_ARTIST_SEED_KEY,
    label = "Seed artist",
    type = "artist_search",
    description = "Search YouTube Music and choose the exact artist this radio should be built around.",
    required = true,
    defaultValue = JSONArray(),
    min = null,
    max = null,
    step = null,
    choices = emptyList(),
    minItems = 1,
    maxItems = 1,
    category = "seeds",
    categoryLabel = "Seeds",
    categoryOrder = 10,
    order = 0,
)

private fun applyLegacySearchSeeds(
    provider: StationProviderUi?,
    config: JSONObject,
    artistValues: Map<String, List<StationArtistSeedUi>>,
    trackValues: Map<String, List<StationTrackSeedUi>>,
) {
    if (provider?.stationType == "song_radio" && provider.hasSearchOption("track_search").not()) {
        trackValues[LEGACY_SONG_RADIO_SEED_KEY].orEmpty().firstOrNull()?.let { track ->
            config.put("seed_type", "track")
            config.put("seed_title", track.title)
            config.put("seed_artist", track.artist)
            config.put("seed_video_id", track.videoId)
            config.put("seed_album", track.album)
        }
    }
    if (provider?.stationType == "similar_artist" && provider.hasSearchOption("artist_search").not()) {
        artistValues[LEGACY_SIMILAR_ARTIST_SEED_KEY].orEmpty().firstOrNull()?.let { artist ->
            config.put("seed_type", "artist")
            config.put("seed_artist", artist.name)
            config.put("seed_artist_id", artist.browseId)
        }
    }
}

private fun groupStationOptions(options: List<StationConfigOptionUi>): List<StationConfigSectionUi> {
    if (options.isEmpty()) return emptyList()
    return options
        .groupBy { it.category.ifBlank { "options" } }
        .map { (categoryId, categoryOptions) ->
            val first = categoryOptions.minWithOrNull(
                compareBy<StationConfigOptionUi>({ it.categoryOrder }, { it.order }, { it.label.lowercase() })
            ) ?: categoryOptions.first()
            StationConfigSectionUi(
                id = categoryId,
                label = first.categoryLabel.ifBlank { humanizeCategory(categoryId) },
                order = first.categoryOrder,
                options = categoryOptions.sortedWith(compareBy({ it.order }, { it.label.lowercase() })),
            )
        }
        .sortedWith(compareBy({ it.order }, { it.label.lowercase() }))
}

private fun seedOptionState(
    option: StationConfigOptionUi,
    config: JSONObject,
    values: MutableMap<String, String>,
    boolValues: MutableMap<String, Boolean>,
    multiValues: MutableMap<String, Set<String>>,
    artistValues: MutableMap<String, List<StationArtistSeedUi>>,
    trackValues: MutableMap<String, List<StationTrackSeedUi>>,
) {
    when (option.type) {
        "boolean" -> boolValues[option.key] = config.optBoolean(option.key, option.defaultAsBoolean())
        "multiselect" -> {
            val selected = mutableSetOf<String>()
            val arr = config.optJSONArray(option.key)
            if (arr != null) {
                for (i in 0 until arr.length()) selected.add(arr.optString(i))
            } else {
                option.defaultValue?.toString()
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.let(selected::addAll)
            }
            multiValues[option.key] = selected
        }
        "artist_search" -> {
            artistValues[option.key] = parseArtistSeedSelections(config, option)
        }
        "track_search" -> {
            trackValues[option.key] = parseTrackSeedSelections(config, option)
        }
        else -> {
            values[option.key] = if (config.has(option.key) && !config.isNull(option.key)) {
                config.opt(option.key).toString()
            } else {
                option.defaultAsString()
            }
        }
    }
}

private fun buildConfigPayload(
    options: List<StationConfigOptionUi>,
    values: Map<String, String>,
    boolValues: Map<String, Boolean>,
    multiValues: Map<String, Set<String>>,
    artistValues: Map<String, List<StationArtistSeedUi>>,
    trackValues: Map<String, List<StationTrackSeedUi>>,
): JSONObject {
    val config = JSONObject()
    options.forEach { option ->
        when (option.type) {
            "boolean" -> config.put(option.key, boolValues[option.key] ?: option.defaultAsBoolean())
            "integer" -> config.put(option.key, values[option.key]?.toIntOrNull() ?: option.defaultAsInt())
            "number" -> config.put(option.key, values[option.key]?.toDoubleOrNull() ?: option.defaultAsDouble())
            "multiselect" -> {
                val arr = JSONArray()
                multiValues[option.key].orEmpty().forEach { arr.put(it) }
                config.put(option.key, arr)
            }
            "artist_search" -> {
                val arr = JSONArray()
                artistValues[option.key].orEmpty().forEach { artist ->
                    arr.put(
                        JSONObject()
                            .put("name", artist.name)
                            .put("browse_id", artist.browseId)
                            .put("art_url", artist.artUrl)
                            .put("thumbnail_url", artist.thumbnailUrl)
                    )
                }
                config.put(option.key, arr)
            }
            "track_search" -> {
                val arr = JSONArray()
                trackValues[option.key].orEmpty().forEach { track ->
                    arr.put(
                        JSONObject()
                            .put("title", track.title)
                            .put("artist", track.artist)
                            .put("album", track.album)
                            .put("video_id", track.videoId)
                            .put("art_url", track.artUrl)
                            .put("thumbnail_url", track.thumbnailUrl)
                    )
                }
                config.put(option.key, arr)
            }
            else -> config.put(option.key, values[option.key].orEmpty())
        }
    }
    return config
}

private fun hasRequiredOptions(
    options: List<StationConfigOptionUi>,
    config: JSONObject,
): Boolean {
    return options.all { option ->
        when (option.type) {
            "boolean" -> true
            "multiselect" -> {
                val count = config.optJSONArray(option.key)?.length() ?: 0
                if (option.required && count == 0) false else count >= (option.minItems ?: if (option.required) 1 else 0)
            }
            "artist_search", "track_search" -> {
                val count = config.optJSONArray(option.key)?.length() ?: 0
                val minRequired = option.minItems ?: if (option.required) 1 else 0
                count >= minRequired
            }
            else -> {
                if (!option.required) true else config.optString(option.key, "").trim().isNotBlank()
            }
        }
    }
}

private fun addLegacyStationMirrors(payload: JSONObject, config: JSONObject) {
    val seedArtist = seedArtistFromConfig(config)
    if (seedArtist.isNotBlank()) payload.put("seed_artist", seedArtist)

    val seedTitle = seedTitleFromConfig(config)
    if (seedTitle.isNotBlank()) payload.put("seed_title", seedTitle)

    payload.put("seed_type", deriveSeedType(config))

    if (config.has("discovery")) payload.put("discovery", config.optDouble("discovery", 0.35))
    if (config.has("seed_influence")) payload.put("seed_influence", config.optDouble("seed_influence", 0.75))
    if (config.has("popular_track_pool_size")) payload.put("popular_track_pool_size", config.optInt("popular_track_pool_size", 10))
    if (config.has("artist_blacklist")) payload.put("artist_blacklist", config.optString("artist_blacklist", ""))
}

private fun deriveSeedType(config: JSONObject): String {
    return if (firstTrackFromConfig(config) != null) "track" else "artist"
}

private fun seedArtistFromConfig(config: JSONObject): String {
    firstTrackFromConfig(config)?.artist?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
    firstArtistFromConfig(config)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
    val direct = config.optString("seed_artist", "").trim()
    if (direct.isNotBlank()) return direct
    val seedArtists = config.optString("seed_artists", "").trim()
    if (seedArtists.isBlank()) return ""
    return seedArtists.split(',', '\n').firstOrNull { it.trim().isNotBlank() }?.trim().orEmpty()
}

private fun seedTitleFromConfig(config: JSONObject): String {
    firstTrackFromConfig(config)?.title?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
    return config.optString("seed_title", "").trim()
}

private fun firstArtistFromConfig(config: JSONObject): String? {
    listOf("seed_artists", "seed_artist", "artists").forEach { key ->
        val arr = config.optJSONArray(key)
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i)
                val name = item?.optString("name", item.optString("artist", ""))?.trim().orEmpty()
                if (name.isNotBlank()) return name
                val raw = arr.optString(i).trim()
                if (raw.isNotBlank()) return raw
            }
        }
    }
    return null
}

private fun firstTrackFromConfig(config: JSONObject): StationTrackSeedUi? {
    listOf("seed_tracks", "tracks").forEach { key ->
        val arr = config.optJSONArray(key)
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val title = item.optString("title", "").trim()
                val artist = item.optString("artist", item.optString("name", "")).trim()
                if (title.isNotBlank() || artist.isNotBlank()) {
                    return StationTrackSeedUi(
                        title = title,
                        artist = artist,
                        album = item.optString("album", ""),
                        videoId = item.optString("video_id", item.optString("videoId", "")),
                        artUrl = item.optString("art_url", item.optString("thumbnail_url", "")),
                        thumbnailUrl = item.optString("thumbnail_url", item.optString("art_url", "")),
                    )
                }
            }
        }
    }
    val title = config.optString("seed_title", "").trim()
    val artist = config.optString("seed_artist", "").trim()
    if (title.isBlank() && artist.isBlank()) return null
    return StationTrackSeedUi(
        title = title,
        artist = artist,
        album = "",
        videoId = "",
        artUrl = "",
        thumbnailUrl = "",
    )
}

private fun stationSeedSummary(station: StationUi): String {
    val config = station.config
    val artistSelections = parseArtistSeedSelections(config, null)
    if (artistSelections.isNotEmpty()) {
        val names = artistSelections.mapNotNull { nameItem -> nameItem.name.takeIf { it.isNotBlank() } }
        if (names.isNotEmpty()) return names.take(2).joinToString(", ") + if (names.size > 2) " +${names.size - 2}" else ""
    }
    val trackSelections = parseTrackSeedSelections(config, null)
    if (trackSelections.isNotEmpty()) {
        val first = trackSelections.first()
        return listOf(first.title, first.artist).filter { it.isNotBlank() }.joinToString(" — ")
    }
    return when (station.seedType) {
        "artist" -> station.seedArtist.ifBlank { station.seedTitle }
        else -> listOf(station.seedTitle, station.seedArtist).filter { it.isNotBlank() }.joinToString(" — ")
    }
}

private fun parseArtistSeedSelections(
    config: JSONObject,
    option: StationConfigOptionUi?,
): List<StationArtistSeedUi> {
    val out = mutableListOf<StationArtistSeedUi>()
    val candidates = buildList {
        option?.key?.takeIf { it.isNotBlank() }?.let(::add)
        add("seed_artists")
        add("seed_artist")
    }.distinct()

    candidates.forEach { key ->
        val arr = config.optJSONArray(key)
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val itemObj = arr.optJSONObject(i)
                if (itemObj != null) {
                    val name = itemObj.optString("name", itemObj.optString("artist", "")).trim()
                    if (name.isBlank()) continue
                    out.add(
                        StationArtistSeedUi(
                            name = name,
                            browseId = itemObj.optString("browse_id", itemObj.optString("browseId", "")),
                            artUrl = itemObj.optString("art_url", itemObj.optString("thumbnail_url", "")),
                            thumbnailUrl = itemObj.optString("thumbnail_url", itemObj.optString("art_url", "")),
                        )
                    )
                } else {
                    val raw = arr.optString(i).trim()
                    if (raw.isNotBlank()) {
                        out.add(StationArtistSeedUi(raw, "", "", ""))
                    }
                }
            }
        }
    }

    if (out.isEmpty()) {
        val direct = config.optString("seed_artist", "").trim()
        if (direct.isNotBlank()) {
            out.add(
                StationArtistSeedUi(
                    name = direct,
                    browseId = config.optString("seed_artist_id", ""),
                    artUrl = config.optString("seed_artist_art_url", ""),
                    thumbnailUrl = config.optString("seed_artist_thumbnail_url", config.optString("seed_artist_art_url", "")),
                )
            )
        }
        val listString = config.optString("seed_artists", "").trim()
        if (listString.isNotBlank()) {
            listString.split(',', '\n')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { out.add(StationArtistSeedUi(it, "", "", "")) }
        }
    }

    return out.distinctBy { artistSeedKey(it) }
}

private fun parseTrackSeedSelections(
    config: JSONObject,
    option: StationConfigOptionUi?,
): List<StationTrackSeedUi> {
    val out = mutableListOf<StationTrackSeedUi>()
    val candidates = buildList {
        option?.key?.takeIf { it.isNotBlank() }?.let(::add)
        add("seed_tracks")
    }.distinct()

    candidates.forEach { key ->
        val arr = config.optJSONArray(key)
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val title = item.optString("title", "").trim()
                val artist = item.optString("artist", item.optString("name", "")).trim()
                if (title.isBlank() && artist.isBlank()) continue
                out.add(
                    StationTrackSeedUi(
                        title = title,
                        artist = artist,
                        album = item.optString("album", ""),
                        videoId = item.optString("video_id", item.optString("videoId", "")),
                        artUrl = item.optString("art_url", item.optString("thumbnail_url", "")),
                        thumbnailUrl = item.optString("thumbnail_url", item.optString("art_url", "")),
                    )
                )
            }
        }
    }

    if (out.isEmpty()) {
        val title = config.optString("seed_title", "").trim()
        val artist = config.optString("seed_artist", "").trim()
        if (title.isNotBlank() || artist.isNotBlank()) {
            out.add(
                StationTrackSeedUi(
                    title = title,
                    artist = artist,
                    album = config.optString("seed_album", ""),
                    videoId = config.optString("seed_video_id", config.optString("yt_video_id", "")),
                    artUrl = config.optString("seed_art_url", config.optString("seed_thumbnail_url", "")),
                    thumbnailUrl = config.optString("seed_thumbnail_url", config.optString("seed_art_url", "")),
                )
            )
        }
    }

    return out.distinctBy { trackSeedKey(it) }
}

private fun parseArtistSearchResults(json: String): List<SearchArtist> {
    val root = JSONObject(json)
    val artists = root.optJSONArray("artists") ?: JSONArray()
    val out = ArrayList<SearchArtist>(artists.length())
    for (i in 0 until artists.length()) {
        val obj = artists.optJSONObject(i) ?: continue
        val name = obj.optString("name", obj.optString("artist", ""))
        val browseId = obj.optString("browse_id", obj.optString("browseId", obj.optString("artist_id", "")))
        val thumb = when {
            obj.has("thumbnail_url") -> obj.optString("thumbnail_url", "")
            obj.has("thumbnail") -> obj.optString("thumbnail", "")
            obj.has("thumb") -> obj.optString("thumb", "")
            else -> ""
        }
        out.add(
            SearchArtist(
                name = name,
                thumbnailUrl = thumb,
                browseId = browseId,
                subscriberCount = obj.optString("subscriber_count", obj.optString("subscribers", "")),
                monthlyListeners = obj.optString("monthly_listeners", ""),
            )
        )
    }
    return out
}

private fun parseTrackSearchResults(json: String): List<SearchSong> {
    val root = JSONObject(json)
    val songs = root.optJSONArray("songs") ?: JSONArray()
    val out = ArrayList<SearchSong>(songs.length())
    for (i in 0 until songs.length()) {
        val obj = songs.optJSONObject(i) ?: continue
        val title = obj.optString("title", "")
        val artist = obj.optString("artist", obj.optString("artists", ""))
        val album = obj.optString("album", "")
        val videoId = obj.optString("video_id", obj.optString("videoId", ""))
        val source = obj.optString("source", "ytmusic")
        val subsonicSongId = obj.optString("subsonic_song_id", obj.optString("subsonicSongId", ""))
        val thumb = when {
            obj.has("thumbnail_url") -> obj.optString("thumbnail_url", "")
            obj.has("thumbnail") -> obj.optString("thumbnail", "")
            obj.has("thumb") -> obj.optString("thumb", "")
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

private fun artistSeedKey(artist: StationArtistSeedUi): String {
    return artist.browseId.ifBlank { artist.name.trim().lowercase() }
}

private fun artistSeedKey(artist: SearchArtist): String {
    return artist.browseId.ifBlank { artist.name.trim().lowercase() }
}

private fun trackSeedKey(track: StationTrackSeedUi): String {
    return track.videoId.ifBlank {
        listOf(track.title.trim().lowercase(), track.artist.trim().lowercase()).joinToString("|")
    }
}

private fun trackSeedKey(song: SearchSong): String {
    return song.videoId.ifBlank {
        listOf(song.title.trim().lowercase(), song.artist.trim().lowercase()).joinToString("|")
    }
}

private fun StationConfigOptionUi.defaultAsString(): String {
    if (defaultValue == null || defaultValue == JSONObject.NULL) return ""
    return defaultValue.toString()
}

private fun StationConfigOptionUi.defaultAsBoolean(): Boolean {
    return when (defaultValue) {
        is Boolean -> defaultValue
        is Number -> defaultValue.toInt() != 0
        else -> defaultValue?.toString()?.equals("true", ignoreCase = true) == true
    }
}

private fun StationConfigOptionUi.defaultAsInt(): Int {
    return when (defaultValue) {
        is Number -> defaultValue.toInt()
        else -> defaultValue?.toString()?.toIntOrNull() ?: 0
    }
}

private fun StationConfigOptionUi.defaultAsDouble(): Double {
    return when (defaultValue) {
        is Number -> defaultValue.toDouble()
        else -> defaultValue?.toString()?.toDoubleOrNull() ?: 0.0
    }
}

private fun JSONObject.optNullableDouble(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return optDouble(key)
}

private fun JSONObject.optNullableInt(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return optInt(key)
}

private fun trimFloatString(value: Float): String {
    val rounded = kotlin.math.round(value * 100f) / 100f
    return rounded.toString().trimEnd('0').trimEnd('.')
}

private fun formatNumberForDisplay(value: Double, type: String): String {
    return if (type == "integer") value.toInt().toString() else {
        val rounded = kotlin.math.round(value * 100.0) / 100.0
        rounded.toString().trimEnd('0').trimEnd('.')
    }
}

private fun humanizeCategory(category: String): String {
    return category
        .replace('_', ' ')
        .replace('-', ' ')
        .trim()
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { part -> part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
        .ifBlank { "Options" }
}
