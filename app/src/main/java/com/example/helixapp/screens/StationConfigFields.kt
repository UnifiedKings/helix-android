package com.example.helixapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.helixapp.ui.theme.HelixAccent
import com.example.helixapp.ui.theme.HelixBorder
import com.example.helixapp.ui.theme.HelixMuted
import com.example.helixapp.ui.theme.HelixSurfaceRaised
import kotlinx.coroutines.delay

import com.example.helixapp.data.HelixLibraryRepository
import kotlinx.coroutines.CancellationException

// The station editor's option fields: artist and song seed search, numbers, choices, toggles.

@Composable
internal fun StationConfigField(
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
internal fun ArtistSearchConfigField(
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
            val existingKeys = selected.map { artistSeedKey(it) }.toSet()
            results = HelixLibraryRepository(ctx).searchArtists(q)
                .filter { artistSeedKey(it) !in existingKeys }
                .take(8)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.toUserMessage("Artist search")
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
internal fun TrackSearchConfigField(
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
            val existingKeys = selected.map { trackSeedKey(it) }.toSet()
            results = HelixLibraryRepository(ctx).search(q).songs
                .filter { trackSeedKey(it) !in existingKeys }
                .take(8)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.toUserMessage("Track search")
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
internal fun SelectionCountHint(
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
internal fun SelectedArtistRow(
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
internal fun SelectedTrackRow(
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
internal fun SearchArtistResultRow(
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
internal fun SearchTrackResultRow(
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
