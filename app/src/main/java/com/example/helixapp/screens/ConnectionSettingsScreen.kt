package com.example.helixapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.helixapp.data.HelixAccountRepository
import com.example.helixapp.prefs.AppPrefs
import kotlinx.coroutines.launch

@Composable
fun ConnectionSettingsScreen(onBack: () -> Unit, onDisconnected: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var baseUrl by remember { mutableStateOf(HelixPrefs.getBaseUrl(ctx)) }
    var username by remember { mutableStateOf(HelixPrefs.getUsername(ctx) ?: "") }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(!HelixPrefs.getSessionToken(ctx).isNullOrBlank()) }

    val sessionExpired by AuthState.sessionExpired.collectAsState()
    LaunchedEffect(Unit) {
        status = when {
            connected && sessionExpired -> "Session expired. Enter your password and tap Connect."
            connected -> "Connected to Helix"
            else -> "Not connected"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Column {
                Text("Connection", style = MaterialTheme.typography.headlineSmall)
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        connected && sessionExpired -> MaterialTheme.colorScheme.error
                        connected -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        Text(
            "The Android app keeps its own server connection. Your Helix account and server data remain shared.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Helix server URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                enabled = !busy,
                onClick = {
                    val b = baseUrl.trim()
                    val u = username.trim()
                    if (b.isBlank() || u.isBlank()) {
                        status = "Enter a server URL and username"
                        return@Button
                    }

                    val previousUrl = HelixPrefs.getBaseUrl(ctx).trim().trimEnd('/')
                    val serverChanged = !previousUrl.equals(b.trimEnd('/'), ignoreCase = true)
                    if (serverChanged && connected) {
                        // The saved session cookie was issued by the old server. Never send it to
                        // the new one (API calls, streams and the realtime socket all would).
                        AppPrefs.clearSession(ctx)
                        connected = false
                    }

                    AppPrefs.saveBaseUrl(ctx, b)
                    HelixPrefs.setUsername(ctx, u)
                    if (password.isBlank()) {
                        status = if (serverChanged) {
                            "Server changed. Enter your password to connect."
                        } else {
                            "Settings saved"
                        }
                        return@Button
                    }

                    busy = true
                    status = "Connecting…"
                    scope.launch {
                        try {
                            HelixLogin.logIn(ctx, b, u, password)
                            password = ""
                            connected = true
                            status = "Connected as $u"
                        } catch (e: LoginException) {
                            status = e.message ?: "Login failed"
                        } finally {
                            busy = false
                        }
                    }
                },
            ) {
                Text(if (password.isBlank()) "Save" else "Connect")
            }

            OutlinedButton(
                enabled = !busy && connected,
                onClick = {
                    busy = true
                    status = "Disconnecting…"
                    scope.launch {
                        try {
                            HelixAccountRepository(ctx).logout()
                        } catch (_: Exception) {
                        } finally {
                            AppPrefs.clearSession(ctx)
                            connected = false
                            status = "Disconnected"
                            busy = false
                            onDisconnected()
                        }
                    }
                },
            ) {
                Text("Disconnect")
            }
        }

        Text(
            "Leave the password blank if you only want to update the saved server URL or username.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
