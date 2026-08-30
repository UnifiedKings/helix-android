package com.example.helixapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.helixapp.ui.theme.AppearancePrefs
import com.example.helixapp.ui.theme.HelixAccent
import com.example.helixapp.ui.theme.HelixBorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun SettingsScreen(
    onOpenConnection: () -> Unit,
    onOpenPlayback: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenAdminUsers: () -> Unit,
) {
    val ctx = LocalContext.current
    val username = HelixPrefs.getUsername(ctx).orEmpty()
    val connected = !HelixPrefs.getSessionToken(ctx).isNullOrBlank()
    val host = HelixPrefs.getBaseUrl(ctx)
    var role by remember { mutableStateOf<String?>(null) }

    val version = runCatching {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        info.versionName ?: "Unknown"
    }.getOrDefault("Unknown")

    LaunchedEffect(connected, host) {
        role = null
        if (!connected) return@LaunchedEffect
        runCatching {
            val api = HelixClient.create(ctx, host)
            val resp = withContext(Dispatchers.IO) { api.me() }
            if (resp.isSuccessful) {
                JSONObject(resp.body().orEmpty()).optString("role", "")
            } else {
                ""
            }
        }.onSuccess {
            role = it
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        SettingsSection("Account") {
            SettingsRow(
                title = if (connected && username.isNotBlank()) username else "Connection",
                subtitle = if (connected) host else "Connect this app to your Helix server",
                value = if (connected) "Connected" else null,
                onClick = onOpenConnection,
            )
        }

        SettingsSection("Playback") {
            SettingsRow(
                title = "Playback & queue",
                subtitle = "Queue behavior, station buffering, and default volume",
                onClick = onOpenPlayback,
            )
        }

        SettingsSection("Appearance") {
            SettingsRow(
                title = "App appearance",
                subtitle = "Native app colors; separate from the web theme",
                value = AppearancePrefs.accentHex(ctx),
                accentValue = true,
                onClick = onOpenAppearance,
            )
        }

        if (role == "admin") {
            SettingsSection("Administration") {
                SettingsRow(
                    title = "Users",
                    subtitle = "Create accounts and manage server-level user access",
                    value = "Admin",
                    accentValue = true,
                    onClick = onOpenAdminUsers,
                )
            }
        }

        SettingsSection("App") {
            SettingsRow(
                title = "Helix for Android",
                subtitle = "Native playback client",
                value = "v$version",
            )
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        content()
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    value: String? = null,
    accentValue: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 13.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!value.isNullOrBlank()) {
                Text(
                    value,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (accentValue) HelixAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onClick != null) {
                Text(
                    "›",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 13.dp)
                .height(1.dp)
                .background(HelixBorder)
        )
    }
}
