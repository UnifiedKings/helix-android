package com.example.helixapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.helixapp.playback.HelixTransport
import com.example.helixapp.playback.NowPlayingUi
import com.example.helixapp.playback.QueueItemUi
import com.example.helixapp.ui.theme.HelixAccent
import com.example.helixapp.ui.theme.HelixBorder
import com.example.helixapp.ui.theme.HelixMuted
import com.example.helixapp.ui.theme.HelixSurfaceRaised
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

@Composable
fun QueueScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf("Idle") }
    var loading by remember { mutableStateOf(false) }
    var nowPlaying by remember { mutableStateOf<NowPlayingUi?>(null) }
    var queue by remember { mutableStateOf(emptyList<QueueItemUi>()) }

    fun refresh() {
        if (HelixPrefs.getSessionToken(ctx).isNullOrBlank()) {
            status = "Not logged in — go to Settings"
            nowPlaying = null
            queue = emptyList()
            return
        }

        loading = true
        status = "Loading…"
        scope.launch {
            try {
                val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                val resp = withContext(Dispatchers.IO) { api.playerState() }
                if (!resp.isSuccessful) {
                    status = "Failed (HTTP ${resp.code()})"
                    return@launch
                }

                val (now, items) = HelixTransport.parseQueueFromState(resp.body().orEmpty())
                nowPlaying = now
                queue = items
                status = if (items.isEmpty()) "Queue is empty" else "Done"
            } catch (e: Exception) {
                status = "Error: ${e.javaClass.simpleName}: ${e.message}"
                nowPlaying = null
                queue = emptyList()
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Text(
                    text = "Current Queue",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (queue.size == 1) "1 song" else "${queue.size} songs",
                    style = MaterialTheme.typography.bodyMedium,
                    color = HelixMuted,
                )
            }
        }

        if (loading && queue.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        } else if (queue.isEmpty()) {
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = HelixMuted,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(queue, key = { it.queueItemId }) { item ->
                    QueueRow(
                        item = item,
                        baseUrl = HelixPrefs.getBaseUrl(ctx),
                        isNowPlaying = nowPlaying?.queueItemId == item.queueItemId,
                        onJump = {
                            scope.launch {
                                try {
                                    val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                                    val mt = "application/json; charset=utf-8".toMediaType()
                                    val body = JSONObject().put("index", item.index).toString().toRequestBody(mt)
                                    val resp = withContext(Dispatchers.IO) { api.jump(body) }
                                    if (!resp.isSuccessful) {
                                        status = "Jump failed (HTTP ${resp.code()})"
                                        return@launch
                                    }
                                    HelixTransport.refreshAndPlayCurrent(ctx)
                                    refresh()
                                } catch (e: Exception) {
                                    status = "Jump error: ${e.javaClass.simpleName}: ${e.message}"
                                }
                            }
                        },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun QueueRow(
    item: QueueItemUi,
    baseUrl: String,
    isNowPlaying: Boolean,
    onJump: () -> Unit,
) {
    val ctx = LocalContext.current
    val art = HelixImages.absoluteUrl(baseUrl, item.artUrl)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onJump() },
        color = if (isNowPlaying) HelixAccent.copy(alpha = 0.10f) else HelixSurfaceRaised,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (isNowPlaying) HelixAccent.copy(alpha = 0.55f) else HelixBorder),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (art.isNotBlank()) {
                    AsyncImage(
                        model = HelixImages.request(ctx, art),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (isNowPlaying) {
                    Surface(
                        color = MaterialTheme.colorScheme.background.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Now playing",
                            tint = HelixAccent,
                            modifier = Modifier.padding(4.dp).size(16.dp),
                        )
                    }
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isNowPlaying) HelixAccent else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.artist.isNotBlank()) {
                    Text(
                        text = item.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = HelixMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Text(
                text = "${item.index + 1}",
                style = MaterialTheme.typography.labelMedium,
                color = HelixMuted,
            )
        }
    }
}
