package com.example.helixapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.helixapp.playback.DevicePlayback
import com.example.helixapp.playback.HelixTransport
import com.example.helixapp.playback.PlayerCommandCoordinator
import com.example.helixapp.ui.theme.HelixAccent
import com.example.helixapp.ui.theme.HelixBorder
import com.example.helixapp.ui.theme.HelixSurfaceRaised
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

@Composable
fun PlaybackSettingsScreen(
    onBack: () -> Unit,
    viewModel: PlaybackSettingsViewModel = helixViewModel { PlaybackSettingsViewModel(it) },
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val loading = state.loading
    val status = state.status
    val queuePosition = state.settings.queueAddPosition
    val queueAhead = state.settings.stationQueueAhead
    val queueAheadMax = state.settings.stationQueueAheadMax
    val defaultVolume = state.settings.defaultVolume
    var playOnDevice by remember { mutableStateOf(DevicePlayback.isEnabled(ctx)) }
    var keepPlayingWhenClosed by remember { mutableStateOf(DevicePlayback.keepsPlayingWhenClosed(ctx)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
            Column {
                Text("Playback & queue", style = MaterialTheme.typography.headlineSmall)
                Text("This phone, plus settings shared with your Helix account", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        SettingBlock(
            "Play on this device",
            "When off, this phone works as a remote: it shows and controls what's playing, including from the lock screen, but your other Helix devices play the audio.",
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (playOnDevice) "Playing audio on this phone" else "Remote control only",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = playOnDevice,
                    onCheckedChange = { enabled ->
                        playOnDevice = enabled
                        DevicePlayback.setEnabled(ctx, enabled)
                        // Turning playback on means "listen here now".
                        if (enabled) HelixTransport.allowLocalPlayback()
                        // Apply right away: unload local audio, or load and join what's playing.
                        scope.launch {
                            runCatching { PlayerCommandCoordinator.syncFromBackend(ctx, forceLoadStream = true) }
                        }
                    },
                )
            }
        }

        SettingBlock(
            "Keep playing after closing the app",
            "When on, music keeps playing after you swipe Helix away from recent apps, with controls in the notification and on the lock screen. When off, closing the app stops playback on all your devices.",
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (keepPlayingWhenClosed) "Keeps playing" else "Stops when closed",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = keepPlayingWhenClosed,
                    onCheckedChange = { keep ->
                        keepPlayingWhenClosed = keep
                        DevicePlayback.setKeepsPlayingWhenClosed(ctx, keep)
                    },
                )
            }
        }

        Text(
            "Account settings",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (loading) {
            CircularProgressIndicator()
        } else {
            SettingBlock("Add to queue", "Choose where Add to queue places a song.") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceButton("End", queuePosition == "append", Modifier.weight(1f)) {
                        viewModel.setQueueAddPosition("append")
                    }
                    ChoiceButton("Play next", queuePosition == "next", Modifier.weight(1f)) {
                        viewModel.setQueueAddPosition("next")
                    }
                }
            }

            SettingBlock("Station queue ahead", "How many station tracks Helix keeps ready ahead of the current song.") {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Tracks ahead", style = MaterialTheme.typography.bodyMedium)
                    Text(queueAhead.toString(), color = HelixAccent, style = MaterialTheme.typography.labelLarge)
                }
                Slider(
                    value = queueAhead.toFloat(),
                    onValueChange = { viewModel.setStationQueueAhead(it.roundToInt()) },
                    onValueChangeFinished = viewModel::saveStationQueueAhead,
                    valueRange = 1f..queueAheadMax.toFloat(),
                    steps = (queueAheadMax - 2).coerceAtLeast(0),
                )
            }

            // Android music apps use the phone's own media volume, so this account setting isn't
            // applied here; it's the web player's starting volume.
            SettingBlock("Default volume", "Starting volume for the Helix web player. On this phone, use your volume buttons.") {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Volume", style = MaterialTheme.typography.bodyMedium)
                    Text("${(defaultVolume * 100).roundToInt()}%", color = HelixAccent, style = MaterialTheme.typography.labelLarge)
                }
                Slider(
                    value = defaultVolume,
                    onValueChange = viewModel::setDefaultVolume,
                    onValueChangeFinished = viewModel::saveDefaultVolume,
                    valueRange = 0f..1f,
                )
            }
        }

        if (status.isNotBlank()) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (status == "Saved") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingBlock(title: String, description: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

@Composable
private fun ChoiceButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = if (selected) HelixSurfaceRaised else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (selected) HelixAccent else HelixBorder),
    ) {
        Text(
            label,
            modifier = Modifier.padding(vertical = 11.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) HelixAccent else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
