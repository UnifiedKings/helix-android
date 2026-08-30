package com.example.helixapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

@Composable
fun QueueScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val playerRefreshTick by RefreshSignals.player.collectAsState()
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val reorderStepPx = with(density) { 82.dp.toPx() }

    var status by remember { mutableStateOf("Idle") }
    var loading by remember { mutableStateOf(false) }
    var nowPlaying by remember { mutableStateOf<NowPlayingUi?>(null) }
    var queue by remember { mutableStateOf(emptyList<QueueItemUi>()) }
    var hasAutoScrolled by remember { mutableStateOf(false) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var dragStartedOrder by remember { mutableStateOf<List<String>>(emptyList()) }
    var reorderError by remember { mutableStateOf<String?>(null) }

    fun refresh(resetScroll: Boolean = false) {
        if (HelixPrefs.getSessionToken(ctx).isNullOrBlank()) {
            status = "Not logged in — go to Settings"
            nowPlaying = null
            queue = emptyList()
            return
        }
        if (resetScroll) hasAutoScrolled = false
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

    fun saveReorder(itemIds: List<String>) {
        reorderError = null
        status = "Saving queue order…"
        scope.launch {
            try {
                val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                val mt = "application/json; charset=utf-8".toMediaType()
                val orderedIds = JSONArray().apply {
                    itemIds.forEach { put(it) }
                }
                val body = JSONObject()
                    .put("item_ids", orderedIds)
                    .toString()
                    .toRequestBody(mt)
                val resp = withContext(Dispatchers.IO) { api.reorderQueue(body) }
                if (!resp.isSuccessful) {
                    reorderError = "HTTP ${resp.code()}"
                    status = "Could not reorder queue"
                    refresh(resetScroll = false)
                    return@launch
                }
                val (now, items) = HelixTransport.parseQueueFromState(resp.body().orEmpty())
                nowPlaying = now
                queue = items
                status = "Done"
            } catch (e: Exception) {
                reorderError = e.message ?: e.javaClass.simpleName
                status = "Could not reorder queue"
                refresh(resetScroll = false)
            }
        }
    }

    fun jumpTo(item: QueueItemUi) {
        if (draggingId != null) return
        scope.launch {
            try {
                val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                val mt = "application/json; charset=utf-8".toMediaType()
                val currentIndex = queue.indexOfFirst { it.queueItemId == item.queueItemId }
                    .takeIf { it >= 0 }
                    ?: item.index
                val body = JSONObject().put("index", currentIndex).toString().toRequestBody(mt)
                val resp = withContext(Dispatchers.IO) { api.jump(body) }
                if (!resp.isSuccessful) {
                    status = "Jump failed (HTTP ${resp.code()})"
                    return@launch
                }
                HelixTransport.refreshAndPlayCurrent(ctx)
                refresh(resetScroll = true)
            } catch (e: Exception) {
                status = "Jump error: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    fun startDrag(itemId: String) {
        draggingId = itemId
        dragOffsetY = 0f
        dragStartedOrder = queue.map { it.queueItemId }
        reorderError = null
    }

    fun moveDraggedItem(deltaY: Float) {
        val activeId = draggingId ?: return
        val currentIndex = queue.indexOfFirst { it.queueItemId == activeId }
        if (currentIndex < 0) return
        dragOffsetY += deltaY
        val thresholdPx = reorderStepPx

        while (dragOffsetY > thresholdPx && currentIndex < queue.lastIndex) {
            val fromIndex = queue.indexOfFirst { it.queueItemId == activeId }
            if (fromIndex < 0 || fromIndex >= queue.lastIndex) break
            val updated = queue.toMutableList()
            val moved = updated.removeAt(fromIndex)
            updated.add(fromIndex + 1, moved)
            queue = updated
            dragOffsetY -= thresholdPx
        }

        while (dragOffsetY < -thresholdPx && currentIndex > 0) {
            val fromIndex = queue.indexOfFirst { it.queueItemId == activeId }
            if (fromIndex <= 0) break
            val updated = queue.toMutableList()
            val moved = updated.removeAt(fromIndex)
            updated.add(fromIndex - 1, moved)
            queue = updated
            dragOffsetY += thresholdPx
        }
    }

    fun finishDrag() {
        val activeId = draggingId ?: return
        val currentOrder = queue.map { it.queueItemId }
        draggingId = null
        dragOffsetY = 0f
        if (dragStartedOrder.isNotEmpty() && dragStartedOrder != currentOrder) {
            saveReorder(currentOrder)
        } else {
            status = if (queue.isEmpty()) "Queue is empty" else "Done"
        }
    }

    LaunchedEffect(Unit) { refresh(resetScroll = true) }

    // /ws/player is the primary source of cross-client changes. Refresh the authoritative
    // queue whenever the process-wide realtime listener receives a player.state snapshot.
    LaunchedEffect(playerRefreshTick) {
        if (playerRefreshTick > 0 && draggingId == null) {
            refresh(resetScroll = false)
        }
    }

    LaunchedEffect(queue, nowPlaying?.queueItemId, hasAutoScrolled) {
        if (!hasAutoScrolled && queue.isNotEmpty()) {
            val nowIndex = queue.indexOfFirst { it.queueItemId == nowPlaying?.queueItemId }
            val targetIndex = if (nowIndex >= 0) max(0, nowIndex - 3) else 0
            listState.scrollToItem(targetIndex)
            hasAutoScrolled = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 12.dp),
        ) {
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
            if (reorderError != null) {
                Text(
                    text = "Could not save queue order: $reorderError",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        if (loading && queue.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                contentAlignment = Alignment.Center,
            ) {
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
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(queue, key = { _, item -> item.queueItemId }) { index, item ->
                    ReorderableQueueRow(
                        item = item,
                        displayIndex = index,
                        baseUrl = HelixPrefs.getBaseUrl(ctx),
                        isNowPlaying = nowPlaying?.queueItemId == item.queueItemId,
                        isDragging = draggingId == item.queueItemId,
                        dragOffsetY = if (draggingId == item.queueItemId) dragOffsetY else 0f,
                        onJump = { jumpTo(item) },
                        onDragStart = { startDrag(item.queueItemId) },
                        onDrag = { delta -> moveDraggedItem(delta) },
                        onDragEnd = { finishDrag() },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun ReorderableQueueRow(
    item: QueueItemUi,
    displayIndex: Int,
    baseUrl: String,
    isNowPlaying: Boolean,
    isDragging: Boolean,
    dragOffsetY: Float,
    onJump: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val ctx = LocalContext.current
    val art = HelixImages.absoluteUrl(baseUrl, item.artUrl)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                translationY = if (isDragging) dragOffsetY else 0f
                alpha = if (isDragging) 0.96f else 1f
            }
            .pointerInput(item.queueItemId) {
                detectTapGestures(onTap = { onJump() })
            }
            .pointerInput(item.queueItemId) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { onDragStart() },
                    onDragCancel = { onDragEnd() },
                    onDragEnd = { onDragEnd() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.y)
                    },
                )
            },
        color = if (isNowPlaying) HelixAccent.copy(alpha = 0.10f) else HelixSurfaceRaised,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (isNowPlaying) HelixAccent.copy(alpha = 0.55f) else HelixBorder),
    ) {
        Box {
            if (isDragging) {
                Surface(
                    modifier = Modifier.matchParentSize(),
                    color = HelixAccent.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(12.dp),
                ) {}
            }
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.DragHandle,
                    contentDescription = "Reorder queue item",
                    tint = HelixMuted,
                    modifier = Modifier.size(18.dp),
                )
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
                                modifier = Modifier
                                    .padding(4.dp)
                                    .size(16.dp),
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
                    text = "${displayIndex + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = HelixMuted,
                )
            }
        }
    }
}
