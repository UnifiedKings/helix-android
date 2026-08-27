package com.example.helixapp

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import com.example.helixapp.playback.HelixTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import com.example.helixapp.HelixTextButton
import com.example.helixapp.ui.theme.HelixAccent
import com.example.helixapp.ui.theme.HelixBorder
import com.example.helixapp.ui.theme.HelixMuted
import com.example.helixapp.ui.theme.HelixSurfaceSoft
import com.example.helixapp.ui.theme.HelixSurfaceRaised

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
)

data class StationProviderUi(
    val stationType: String,
    val displayName: String,
    val description: String,
    val configOptions: List<StationConfigOptionUi>,
)

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

    var editing by remember { mutableStateOf<StationUi?>(null) }
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
                val typeResp = withContext(Dispatchers.IO) { api.listStationTypes() }
                if (typeResp.isSuccessful) {
                    providers = parseStationProviders(typeResp.body().orEmpty())
                }

                val resp = withContext(Dispatchers.IO) { api.listStations() }
                val body = resp.body().orEmpty()
                if (resp.code() == 401) {
                    status = "Unauthorized (401) — session expired? Login again."
                    stations = emptyList()
                    return@launch
                }
                if (!resp.isSuccessful) {
                    status = "Failed (HTTP ${resp.code()})"
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
                if (now - lastRefreshMs > 30_000L && !loading) {
                    refresh()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val statusMessage = when {
        loading -> "Loading stations…"
        status.startsWith("Unauthorized") || status.startsWith("Failed") || status.startsWith("Error") || status.startsWith("Not logged") -> status
        stations.isEmpty() && status == "No stations" -> "No stations yet"
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
        } else if (statusMessage != null && stations.isNotEmpty()) {
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = if (status.startsWith("Unauthorized") || status.startsWith("Failed") || status.startsWith("Error") || status.startsWith("Not logged")) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        if (stations.isEmpty() && !loading) {
            Box(modifier = Modifier.padding(top = 12.dp, start = 2.dp)) {
                Text(
                    text = if (status.startsWith("Not logged")) "Log in from Settings to load your stations." else "You have no stations yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn {
                itemsIndexed(stations) { index, st ->
                    StationRow(
                        st = st,
                        provider = providers.firstOrNull { it.stationType == st.stationType },
                        baseUrl = HelixPrefs.getBaseUrl(ctx),
                        onEdit = { editing = st },
                        onPlay = {
                            scope.launch {
                                showLoadingOverlay(
                                    "Starting station…\nStations can take up to 30 seconds to load."
                                )
                                try {
                                    val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                                    val payload = JSONObject().put("reset", true).toString()
                                    val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
                                    val resp = withContext(Dispatchers.IO) { api.playStation(st.id, body) }
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
                                    // Station creation deliberately leaves backend playback paused.
                                    // Match the web player behavior by explicitly resuming before
                                    // syncing the current Media3 item, so station playback starts
                                    // immediately without requiring a pause/play tap.
                                    withContext(Dispatchers.IO) { api.resume() }
                                    HelixTransport.refreshAndPlayCurrent(ctx, forceRestart = true)
                                    status = "Playing station: ${st.name}"
                                } catch (e: Exception) {
                                    status = "Play error: ${e.javaClass.simpleName}: ${e.message}"
                                } finally {
                                    onNavigateToNowPlaying()
                                    hideLoadingOverlay()
                                }
                            }
                        }
                    )
                    if (index < stations.lastIndex) {
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

    if (editing != null) {
        val station = editing!!
        StationEditDialog(
            station = station,
            provider = providers.firstOrNull { it.stationType == station.stationType },
            onDismiss = { editing = null },
            onSave = { updated, configPayload ->
                stations = stations.map { if (it.id == updated.id) updated else it }
                editing = null

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
            onDelete = { st ->
                stations = stations.filterNot { it.id == st.id }
                editing = null

                scope.launch {
                    try {
                        val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                        val resp = withContext(Dispatchers.IO) { api.deleteStation(st.id) }
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
            }
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
            }
        )
    }
}

@Composable
private fun StationRow(
    st: StationUi,
    provider: StationProviderUi?,
    baseUrl: String,
    onEdit: () -> Unit,
    onPlay: () -> Unit,
) {
    val ctx = LocalContext.current
    val cover = HelixImages.absoluteUrl(baseUrl, st.thumbnailUrl)
    var menuExpanded by remember(st.id) { mutableStateOf(false) }

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
                .clip(RoundedCornerShape(10.dp))
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = st.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val seed = stationSeedSummary(st)
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
                text = provider?.displayName ?: st.stationType,
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
                    text = { Text("Edit station") },
                    onClick = {
                        menuExpanded = false
                        onEdit()
                    },
                )
            }
        }
    }
}

@Composable
private fun StationEditDialog(
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
    var menuExpanded by remember(station.id) { mutableStateOf(false) }
    var confirmDelete by remember(station.id) { mutableStateOf(false) }

    LaunchedEffect(station.id, provider?.stationType) {
        values.clear()
        boolValues.clear()
        multiValues.clear()
        provider?.configOptions.orEmpty().forEach { option ->
            seedOptionState(option, station.config, values, boolValues, multiValues)
        }
    }

    val configPayload = buildConfigPayload(provider?.configOptions.orEmpty(), values, boolValues, multiValues)
    val canSave = name.trim().isNotBlank() && hasRequiredOptions(provider?.configOptions.orEmpty(), configPayload)

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Edit station", style = MaterialTheme.typography.headlineSmall)
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
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 590.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "General",
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
                }

                provider?.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val options = provider?.configOptions.orEmpty()
                if (options.isEmpty()) {
                    Text(
                        "This station type does not expose editable settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            "Tuning",
                            style = MaterialTheme.typography.labelLarge,
                            color = HelixAccent,
                        )
                        options.forEach { option ->
                            StationConfigField(
                                option = option,
                                values = values,
                                boolValues = boolValues,
                                multiValues = multiValues,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val mergedConfig = JSONObject(station.config.toString())
                    provider?.configOptions.orEmpty().forEach { option ->
                        mergedConfig.put(option.key, configPayload.opt(option.key))
                    }
                    onSave(
                        station.copy(
                            name = name.trim().ifBlank { station.name },
                            config = mergedConfig,
                            discovery = mergedConfig.optDouble("discovery", station.discovery.toDouble()).toFloat(),
                            seedInfluence = mergedConfig.optDouble("seed_influence", station.seedInfluence.toDouble()).toFloat(),
                        ),
                        configPayload,
                    )
                },
                enabled = canSave,
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            HelixTextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )

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
                    }
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

    LaunchedEffect(selectedProviderType) {
        values.clear()
        boolValues.clear()
        multiValues.clear()
        selectedProvider.configOptions.forEach { option ->
            seedOptionState(option, JSONObject(), values, boolValues, multiValues)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create station") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Box {
                    Button(onClick = { providerMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(selectedProvider.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

                selectedProvider.description.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }

                if (selectedProvider.configOptions.isEmpty()) {
                    Text("This station type does not expose configurable settings.", style = MaterialTheme.typography.bodySmall)
                } else {
                    selectedProvider.configOptions.forEach { option ->
                        StationConfigField(
                            option = option,
                            values = values,
                            boolValues = boolValues,
                            multiValues = multiValues,
                        )
                    }
                }
            }
        },
        confirmButton = {
            val configPayload = buildConfigPayload(selectedProvider.configOptions, values, boolValues, multiValues)
            val canCreate = name.trim().isNotBlank() && hasRequiredOptions(selectedProvider.configOptions, configPayload)
            Button(
                onClick = {
                    val payload = JSONObject()
                        .put("name", name.trim())
                        .put("station_type", selectedProvider.stationType)
                        .put("config", configPayload)
                        .put("seed_type", "artist")

                    val seedArtist = seedArtistFromConfig(configPayload)
                    payload.put("seed_artist", seedArtist)
                    payload.put("seed_title", "")
                    addLegacyStationMirrors(payload, configPayload)
                    onCreate(payload.toString())
                },
                enabled = canCreate,
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            HelixTextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun StationConfigField(
    option: StationConfigOptionUi,
    values: MutableMap<String, String>,
    boolValues: MutableMap<String, Boolean>,
    multiValues: MutableMap<String, Set<String>>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
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
                val currentLabel = option.choices.firstOrNull { it.value == current }?.label
                    ?: current.ifBlank { "Choose…" }

                Text(option.label, style = MaterialTheme.typography.titleMedium)
                Box {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .clickable { expanded = true },
                        color = HelixSurfaceSoft,
                        shape = RoundedCornerShape(9.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, HelixBorder),
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

private fun parseStations(json: String): List<StationUi> {
    val arr = JSONArray(json)
    val out = ArrayList<StationUi>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val config = o.optJSONObject("config") ?: JSONObject()
        out.add(
            StationUi(
                id = o.optString("id", ""),
                name = o.optString("name", ""),
                stationType = o.optString("station_type", "listenbrainz_similar_artist"),
                config = config,
                seedType = config.optString("seed_type", o.optString("seed_type", "")),
                seedTitle = config.optString("seed_title", o.optString("seed_title", "")),
                seedArtist = config.optString("seed_artist", o.optString("seed_artist", "")),
                discovery = config.optDouble("discovery", o.optDouble("discovery", 0.35)).toFloat(),
                seedInfluence = config.optDouble("seed_influence", o.optDouble("seed_influence", 0.75)).toFloat(),
                thumbnailUrl = o.optString("thumbnail_url", ""),
            )
        )
    }
    return out
}

private fun parseStationProviders(json: String): List<StationProviderUi> {
    val arr = JSONArray(json)
    val out = ArrayList<StationProviderUi>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val optionsArr = o.optJSONArray("config_options") ?: JSONArray()
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
                )
            )
        }
        out.add(
            StationProviderUi(
                stationType = o.optString("station_type", ""),
                displayName = o.optString("display_name", o.optString("station_type", "")),
                description = o.optString("description", ""),
                configOptions = options.filter { it.key.isNotBlank() },
            )
        )
    }
    return out.filter { it.stationType.isNotBlank() }
}

private fun seedOptionState(
    option: StationConfigOptionUi,
    config: JSONObject,
    values: MutableMap<String, String>,
    boolValues: MutableMap<String, Boolean>,
    multiValues: MutableMap<String, Set<String>>,
) {
    when (option.type) {
        "boolean" -> boolValues[option.key] = config.optBoolean(option.key, option.defaultAsBoolean())
        "multiselect" -> {
            val selected = mutableSetOf<String>()
            val arr = config.optJSONArray(option.key)
            if (arr != null) {
                for (i in 0 until arr.length()) selected.add(arr.optString(i))
            } else {
                option.defaultValue?.toString()?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.let(selected::addAll)
            }
            multiValues[option.key] = selected
        }
        else -> values[option.key] = if (config.has(option.key) && !config.isNull(option.key)) {
            config.opt(option.key).toString()
        } else {
            option.defaultAsString()
        }
    }
}

private fun buildConfigPayload(
    options: List<StationConfigOptionUi>,
    values: Map<String, String>,
    boolValues: Map<String, Boolean>,
    multiValues: Map<String, Set<String>>,
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
            else -> config.put(option.key, values[option.key].orEmpty())
        }
    }
    return config
}

private fun hasRequiredOptions(options: List<StationConfigOptionUi>, config: JSONObject): Boolean {
    return options.filter { it.required }.all { option ->
        when (option.type) {
            "boolean" -> true
            "multiselect" -> (config.optJSONArray(option.key)?.length() ?: 0) > 0
            else -> config.optString(option.key, "").trim().isNotBlank()
        }
    }
}

private fun addLegacyStationMirrors(payload: JSONObject, config: JSONObject) {
    val seedArtist = seedArtistFromConfig(config)
    if (seedArtist.isNotBlank()) payload.put("seed_artist", seedArtist)
    if (config.has("seed_title")) payload.put("seed_title", config.optString("seed_title", ""))
    if (config.has("discovery")) payload.put("discovery", config.optDouble("discovery", 0.35))
    if (config.has("seed_influence")) payload.put("seed_influence", config.optDouble("seed_influence", 0.75))
    if (config.has("popular_track_pool_size")) payload.put("popular_track_pool_size", config.optInt("popular_track_pool_size", 10))
    if (config.has("artist_blacklist")) payload.put("artist_blacklist", config.optString("artist_blacklist", ""))
}

private fun seedArtistFromConfig(config: JSONObject): String {
    val direct = config.optString("seed_artist", "").trim()
    if (direct.isNotBlank()) return direct
    val seedArtists = config.optString("seed_artists", "").trim()
    if (seedArtists.isBlank()) return ""
    return seedArtists.split(',', '\n').firstOrNull { it.trim().isNotBlank() }?.trim().orEmpty()
}

private fun stationSeedSummary(st: StationUi): String {
    val seedArtists = st.config.optString("seed_artists", "").trim()
    if (seedArtists.isNotBlank()) return seedArtists.lines().firstOrNull { it.trim().isNotBlank() }?.trim()
        ?: seedArtists.split(',').firstOrNull()?.trim().orEmpty()
    return when (st.seedType) {
        "artist" -> st.seedArtist.ifBlank { st.seedTitle }
        else -> listOf(st.seedTitle, st.seedArtist).filter { it.isNotBlank() }.joinToString(" — ")
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
