package com.example.helixapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.helixapp.ui.theme.HelixAccent
import com.example.helixapp.ui.theme.HelixBorder
import com.example.helixapp.ui.theme.HelixMuted
import com.example.helixapp.ui.theme.HelixSurfaceRaised
import com.example.helixapp.ui.theme.HelixSurfaceSoft
import org.json.JSONObject


// Creating a station, and tuning (editing or deleting) one.

@Composable
internal fun StationTuneDialog(
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
internal fun StationCreateDialog(
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
    var step by remember { mutableStateOf(0) }
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
    val canContinue = name.trim().isNotBlank()
    val canCreate = canContinue && hasRequiredOptions(options, configPayload) && hasSpecialSeed

    fun createStation() {
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
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 8.dp)
                .widthIn(max = 640.dp),
            shape = RoundedCornerShape(18.dp),
            color = HelixSurfaceRaised,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Create station", style = MaterialTheme.typography.headlineSmall)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = if (step == 0) HelixAccent else HelixSurfaceSoft,
                            border = BorderStroke(1.dp, if (step == 0) HelixAccent else HelixBorder),
                        ) {
                            Text(
                                text = "1",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                color = if (step == 0) MaterialTheme.colorScheme.onPrimary else HelixMuted,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        Text(
                            "Details",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (step == 0) HelixAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(1.dp)
                                .background(HelixBorder),
                        )
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = if (step == 1) HelixAccent else HelixSurfaceSoft,
                            border = BorderStroke(1.dp, if (step == 1) HelixAccent else HelixBorder),
                        ) {
                            Text(
                                text = "2",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                color = if (step == 1) MaterialTheme.colorScheme.onPrimary else HelixMuted,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        Text(
                            "Options",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (step == 1) HelixAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                HorizontalDivider(color = HelixBorder)

                if (step == 0) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 18.dp, vertical = 18.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Station name", style = MaterialTheme.typography.titleMedium)
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                placeholder = { Text("Give your station a name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Station type", style = MaterialTheme.typography.titleMedium)
                            Box {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { providerMenuExpanded = true },
                                    color = HelixSurfaceSoft,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, HelixBorder),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            selectedProvider.displayName,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
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
                            selectedProvider.description.takeIf { it.isNotBlank() }?.let { description ->
                                Text(
                                    description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                    ) {
                        Text(
                            selectedProvider.displayName,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            name.trim(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                            showProviderDescription = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                        )
                    }
                }

                HorizontalDivider(color = HelixBorder)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HelixTextButton(
                        onClick = {
                            if (step == 0) onDismiss() else step = 0
                        },
                    ) {
                        Text(if (step == 0) "Cancel" else "Back")
                    }

                    Button(
                        onClick = {
                            if (step == 0) step = 1 else createStation()
                        },
                        enabled = if (step == 0) canContinue else canCreate,
                    ) {
                        Text(if (step == 0) "Next" else "Create")
                    }
                }
            }
        }
    }
}

@Composable
internal fun StationFormContent(
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
    showProviderDescription: Boolean = true,
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

        if (showProviderDescription) {
            provider?.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    description,
                    modifier = Modifier.padding(horizontal = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
