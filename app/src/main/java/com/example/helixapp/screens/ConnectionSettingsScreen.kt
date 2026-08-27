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
import com.example.helixapp.prefs.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

@Composable
fun ConnectionSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var baseUrl by remember { mutableStateOf(HelixPrefs.getBaseUrl(ctx)) }
    var username by remember { mutableStateOf(HelixPrefs.getUsername(ctx) ?: "") }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(!HelixPrefs.getSessionToken(ctx).isNullOrBlank()) }

    LaunchedEffect(Unit) {
        status = if (connected) "Connected to Helix" else "Not connected"
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
                    color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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

                    AppPrefs.saveBaseUrl(ctx, b)
                    HelixPrefs.setUsername(ctx, u)
                    if (password.isBlank()) {
                        HelixWebSession.sync(ctx)
                        status = "Settings saved"
                        return@Button
                    }

                    busy = true
                    status = "Connecting…"
                    scope.launch {
                        try {
                            val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                            val json = JSONObject().put("username", u).put("password", password).toString()
                            val requestBody = json.toRequestBody("application/json; charset=utf-8".toMediaType())
                            val resp = withContext(Dispatchers.IO) { api.login(requestBody) }
                            if (!resp.isSuccessful) {
                                status = "Login failed (HTTP ${resp.code()})"
                                return@launch
                            }
                            val token = extractSessionTokenForSettings(resp.headers())
                            if (token.isNullOrBlank()) {
                                status = "Login succeeded, but no session cookie was returned"
                                return@launch
                            }
                            AppPrefs.saveSessionCookie(ctx, token)
                            password = ""
                            connected = true
                            status = "Connected as $u"
                        } catch (e: Exception) {
                            status = "Connection error: ${e.message ?: e.javaClass.simpleName}"
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
                            val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                            withContext(Dispatchers.IO) { api.logout() }
                        } catch (_: Exception) {
                        } finally {
                            AppPrefs.clearSession(ctx)
                            connected = false
                            status = "Disconnected"
                            busy = false
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

private fun extractSessionTokenForSettings(headers: Headers): String? {
    for (setCookie in headers.values("Set-Cookie")) {
        val idx = setCookie.indexOf("mr_session=")
        if (idx >= 0) {
            val token = setCookie.substring(idx + "mr_session=".length).substringBefore(';').trim()
            if (token.isNotBlank()) return token
        }
    }
    return null
}
