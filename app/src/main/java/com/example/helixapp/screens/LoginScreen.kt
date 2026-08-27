package com.example.helixapp

import com.example.helixapp.prefs.AppPrefs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

@Composable
fun LoginScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var baseUrl by remember { mutableStateOf(HelixPrefs.getBaseUrl(ctx)) }
    var username by remember { mutableStateOf(HelixPrefs.getUsername(ctx) ?: "") }
    var password by remember { mutableStateOf("") }

    var status by remember { mutableStateOf("Idle") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        status = if (!HelixPrefs.getSessionToken(ctx).isNullOrBlank()) "Already logged in" else "Not logged in"
    }

    fun setStatus(s: String) { status = s }

    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = !busy,
                onClick = {
                    val b = baseUrl.trim()
                    val u = username.trim()
                    if (b.isBlank() || u.isBlank() || password.isBlank()) {
                        setStatus("Please fill base URL, username, and password")
                        return@Button
                    }

                    // Persist base URL so the PlaybackService (and everything else) can use it.
                    AppPrefs.saveBaseUrl(ctx, b)
                    HelixPrefs.setUsername(ctx, u)

                    busy = true
                    setStatus("Logging in…")

                    scope.launch {
                        try {
                            val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                            val json = JSONObject().put("username", u).put("password", password).toString()
                            val bodyReq = json.toRequestBody("application/json; charset=utf-8".toMediaType())
                            val resp = withContext(Dispatchers.IO) { api.login(bodyReq) }
                            val body = resp.body().orEmpty()
                            if (!resp.isSuccessful) {
                                setStatus("Login failed (HTTP ${resp.code()}): ${body.take(200)}")
                                return@launch
                            }

                            val token = extractSessionToken(resp.headers())
                            if (token.isNullOrBlank()) {
                                setStatus("Login succeeded but no session cookie received")
                                return@launch
                            }

                            // Persist session cookie so streaming + thumbnails work outside the UI.
                            AppPrefs.saveSessionCookie(ctx, token)
                            val role = try { JSONObject(body).optString("role", "") } catch (_: Exception) { "" }
                            setStatus("Logged in ✅${if (role.isNotBlank()) " (role=$role)" else ""}")
                        } catch (e: Exception) {
                            setStatus("Login error: ${e.javaClass.simpleName}: ${e.message}")
                        } finally {
                            busy = false
                        }
                    }
                }
            ) {
                Text("Login")
            }

            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    setStatus("Logging out…")
                    scope.launch {
                        try {
                            val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                            withContext(Dispatchers.IO) { api.logout() }
                        } catch (_: Exception) {
                            // best effort
                        } finally {
                            HelixPrefs.clearAuth(ctx)
                            setStatus("Logged out")
                            busy = false
                        }
                    }
                }
            ) {
                Text("Logout")
            }
        }

        Text("Status: $status")
    }
}

private fun extractSessionToken(headers: Headers): String? {
    val setCookies = headers.values("Set-Cookie")
    for (sc in setCookies) {
        val idx = sc.indexOf("mr_session=")
        if (idx >= 0) {
            val after = sc.substring(idx + "mr_session=".length)
            val token = after.substringBefore(';').trim()
            if (token.isNotBlank()) return token
        }
    }
    return null
}
