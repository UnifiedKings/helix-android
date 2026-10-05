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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlin.math.roundToInt

@Composable
fun PlaybackSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("") }
    var queuePosition by remember { mutableStateOf("append") }
    var queueAhead by remember { mutableIntStateOf(3) }
    var queueAheadMax by remember { mutableIntStateOf(10) }
    var defaultVolume by remember { mutableFloatStateOf(1f) }
    var playOnDevice by remember { mutableStateOf(DevicePlayback.isEnabled(ctx)) }
    var keepPlayingWhenClosed by remember { mutableStateOf(DevicePlayback.keepsPlayingWhenClosed(ctx)) }

    fun updateSetting(key: String, value: Any) {
        scope.launch {
            try {
                val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                val payload = JSONObject().put(key, value).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val resp = withContext(Dispatchers.IO) { api.updateUserSettings(payload) }
                if (!resp.isSuccessful) status = "Could not save (HTTP ${resp.code()})" else status = "Saved"
            } catch (e: Exception) {
                status = "Could not save: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    LaunchedEffect(Unit) {
        if (HelixPrefs.getSessionToken(ctx).isNullOrBlank()) {
            status = "Connect to Helix first"
            loading = false
            return@LaunchedEffect
        }
        try {
            val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
            val resp = withContext(Dispatchers.IO) { api.userSettings() }
            if (!resp.isSuccessful) {
                status = "Could not load settings (HTTP ${resp.code()})"
            } else {
                val root = JSONObject(resp.body().orEmpty())
                val settings = root.optJSONObject("settings") ?: JSONObject()
                val limits = root.optJSONObject("limits") ?: JSONObject()
                queuePosition = settings.optString("queue_add_position", "append")
                queueAheadMax = limits.optInt("station_queue_ahead_max", 10).coerceAtLeast(1)
                queueAhead = settings.optInt("station_queue_ahead", 3).coerceIn(1, queueAheadMax)
                defaultVolume = settings.optDouble("playback_default_volume", 1.0).toFloat().coerceIn(0f, 1f)
            }
        } catch (e: Exception) {
            status = "Could not load settings: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            loading = false
        }
    }

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
                        queuePosition = "append"
                        updateSetting("queue_add_position", "append")
                    }
                    ChoiceButton("Play next", queuePosition == "next", Modifier.weight(1f)) {
                        queuePosition = "next"
                        updateSetting("queue_add_position", "next")
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
                    onValueChange = { queueAhead = it.roundToInt().coerceIn(1, queueAheadMax) },
                    onValueChangeFinished = { updateSetting("station_queue_ahead", queueAhead) },
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
                    onValueChange = { defaultVolume = it },
                    onValueChangeFinished = { updateSetting("playback_default_volume", defaultVolume.toDouble()) },
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
