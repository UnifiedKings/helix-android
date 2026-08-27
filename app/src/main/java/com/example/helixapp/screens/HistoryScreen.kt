package com.example.helixapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class HistoryItemUi(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val event: String,
    val createdAt: String,
    val artUrl: String,
    val stationId: String,
)

@Composable
fun HistoryScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf("Idle") }
    var loading by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf(emptyList<HistoryItemUi>()) }

    var lastRefreshMs by remember { mutableStateOf(0L) }

    fun refresh() {
        lastRefreshMs = System.currentTimeMillis()
        if (HelixPrefs.getSessionToken(ctx).isNullOrBlank()) {
            status = "Not logged in — go to Settings"
            items = emptyList()
            return
        }
        loading = true
        status = "Loading…"
        scope.launch {
            try {
                val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                val resp = withContext(Dispatchers.IO) { api.history() }
                val body = resp.body().orEmpty()
                if (resp.code() == 401) {
                    status = "Unauthorized (401) — session expired? Login again."
                    items = emptyList()
                    return@launch
                }
                if (!resp.isSuccessful) {
                    status = "Failed (HTTP ${resp.code()})"
                    items = emptyList()
                    return@launch
                }
                items = parseHistory(body)
                status = if (items.isEmpty()) "No history" else "Done"
            } catch (e: Exception) {
                status = "Error: ${e.javaClass.simpleName}: ${e.message}"
                items = emptyList()
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

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

    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (loading) CircularProgressIndicator()
        }
        Text("Status: $status", style = MaterialTheme.typography.bodySmall)

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items) { it ->
                HistoryRow(item = it, baseUrl = HelixPrefs.getBaseUrl(ctx))
            }
        }
    }
}

@Composable
private fun HistoryRow(item: HistoryItemUi, baseUrl: String) {
    val ctx = LocalContext.current
    val art = HelixImages.absoluteUrl(baseUrl, item.artUrl)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AsyncImage(
            model = HelixImages.request(ctx, art),
            contentDescription = null,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOf(item.artist, item.album).filter { it.isNotBlank() }.joinToString(" • ")
            if (sub.isNotBlank()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                "${item.event} • ${item.createdAt}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun parseHistory(json: String): List<HistoryItemUi> {
    val root = JSONObject(json)
    val arr = root.optJSONArray("items") ?: JSONArray()
    val out = ArrayList<HistoryItemUi>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        out.add(
            HistoryItemUi(
                id = o.optString("id", ""),
                title = o.optString("title", ""),
                artist = o.optString("artist", ""),
                album = o.optString("album", ""),
                event = o.optString("event", ""),
                createdAt = o.optString("created_at", ""),
                artUrl = o.optString("art_url", ""),
                stationId = o.optString("station_id", ""),
            )
        )
    }
    return out
}

// normalizeUrl() removed; use HelixImages.absoluteUrl().
