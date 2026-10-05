package com.example.helixapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.example.helixapp.helix.HelixTrackRequests
import com.example.helixapp.playback.PlaybackActions
import com.example.helixapp.ui.theme.HelixAccent
import com.example.helixapp.ui.theme.HelixBorder
import com.example.helixapp.ui.theme.HelixMuted
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val HISTORY_PAGE_SIZE = 50

data class HistoryItemUi(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    /** "completed" or "skipped". */
    val event: String,
    /** When it was played, in epoch millis; 0 if the server's timestamp couldn't be read. */
    val playedAtMs: Long,
    val artUrl: String,
    val durationMs: Long,
    val source: String,
    val ytVideoId: String,
    val ytBrowseId: String,
    val subsonicSongId: String,
    val mbRecordingId: String,
    val mbArtistId: String,
) {
    val skipped: Boolean get() = event.equals("skipped", ignoreCase = true)
}

data class HistoryPage(val items: List<HistoryItemUi>, val hasMore: Boolean)

private enum class HistoryFilter(val label: String, val event: String?) {
    All("All", null),
    Played("Played", "completed"),
    Skipped("Skipped", "skipped"),
}

@Composable
fun HistoryScreen(onNavigateToNowPlaying: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var filter by remember { mutableStateOf(HistoryFilter.All) }
    var items by remember { mutableStateOf(emptyList<HistoryItemUi>()) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var lastRefreshMs by remember { mutableStateOf(0L) }
    var loadJob by remember { mutableStateOf<Job?>(null) }

    /** Load the first page (replacing the list), or the next page when [append]. */
    fun load(append: Boolean = false) {
        if (HelixPrefs.getSessionToken(ctx).isNullOrBlank()) {
            error = "Log in from Settings to see your listening history."
            items = emptyList()
            return
        }
        val forFilter = filter
        val offset = if (append) items.size else 0
        if (!append) lastRefreshMs = System.currentTimeMillis()
        loadJob?.cancel()
        loading = true
        loadJob = scope.launch {
            try {
                val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                val resp = withContext(Dispatchers.IO) {
                    api.history(event = forFilter.event, limit = HISTORY_PAGE_SIZE, offset = offset)
                }
                if (!resp.isSuccessful) throw HelixHttpException(resp.code())
                val page = parseHistoryPage(resp.body().orEmpty())
                items = if (append) items + page.items else page.items
                hasMore = page.hasMore
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.toUserMessage("Loading history")
            } finally {
                if (forFilter == filter) loading = false
            }
        }
    }

    fun playAgain(item: HistoryItemUi) {
        scope.launchPlaybackAction(
            failureAction = "Play",
            overlayMessage = "Starting track…",
            onSuccess = onNavigateToNowPlaying,
        ) {
            PlaybackActions.playTrack(ctx, item.toTrackRequest())
        }
    }

    fun playNext(item: HistoryItemUi) {
        scope.launchPlaybackAction(failureAction = "Play next", successMessage = "Playing next: ${item.title}") {
            PlaybackActions.playNext(ctx, item.toTrackRequest())
        }
    }

    fun queue(item: HistoryItemUi) {
        scope.launchPlaybackAction(failureAction = "Queue", successMessage = "Queued: ${item.title}") {
            PlaybackActions.queueTrack(ctx, item.toTrackRequest())
        }
    }

    LaunchedEffect(filter) { load() }

    // Coming back to the app after a while: refresh, since more has been played meanwhile.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                System.currentTimeMillis() - lastRefreshMs > 30_000L && !loading
            ) {
                load()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            HistoryFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(f.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = HelixAccent.copy(alpha = 0.18f),
                        selectedLabelColor = HelixAccent,
                    ),
                )
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (loading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }

        val message = error
        if (message != null && items.isEmpty()) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        } else if (items.isEmpty() && !loading) {
            Text(
                when (filter) {
                    HistoryFilter.All -> "Nothing played yet."
                    HistoryFilter.Played -> "No finished songs yet."
                    HistoryFilter.Skipped -> "Nothing skipped yet."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val now = System.currentTimeMillis()
        LazyColumn {
            historySections(items, now).forEach { (label, sectionItems) ->
                item(key = "day-$label") {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = HelixMuted,
                        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                    )
                }
                items(sectionItems, key = { it.id.ifBlank { "${it.playedAtMs}-${it.title}" } }) { item ->
                    HistoryRow(
                        item = item,
                        onPlay = { playAgain(item) },
                        onPlayNext = { playNext(item) },
                        onQueue = { queue(item) },
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 68.dp)
                            .height(1.dp)
                            .background(HelixBorder)
                    )
                }
            }
            if (hasMore) {
                item(key = "load-more") {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        HelixTextButton(onClick = { load(append = true) }, enabled = !loading) {
                            Text("Load more")
                        }
                    }
                }
            }
            if (message != null && items.isNotEmpty()) {
                item(key = "error") {
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(item: HistoryItemUi, onPlay: () -> Unit, onPlayNext: () -> Unit, onQueue: () -> Unit) {
    val ctx = LocalContext.current
    val art = HelixImages.absoluteUrl(HelixPrefs.getBaseUrl(ctx), item.artUrl)
    var menuExpanded by remember(item.id) { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = HelixImages.request(ctx, art),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = listOf(item.artist, item.album).filter { it.isNotBlank() }.joinToString(" • ")
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
        Column(horizontalAlignment = Alignment.End) {
            if (item.playedAtMs > 0) {
                Text(
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.playedAtMs)),
                    style = MaterialTheme.typography.labelMedium,
                    color = HelixMuted,
                )
            }
            if (item.skipped) {
                Text("Skipped", style = MaterialTheme.typography.labelSmall, color = HelixMuted)
            }
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Song options", tint = HelixMuted)
            }
            HelixTrackOverflowMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                onPlay = { menuExpanded = false; onPlay() },
                onPlayNext = { menuExpanded = false; onPlayNext() },
                onAddToQueue = { menuExpanded = false; onQueue() },
                onAddToSubsonic = {},
                showAddToSubsonic = false,
            )
        }
    }
}

private fun HistoryItemUi.toTrackRequest(): JSONObject =
    HelixTrackRequests.playOrQueueBodyFromPlaylistTrack(
        title = title,
        artist = artist,
        album = album,
        artUrl = artUrl,
        durationMs = durationMs,
        source = source,
        subsonicSongId = subsonicSongId,
        ytVideoId = ytVideoId,
        ytBrowseId = ytBrowseId,
        mbRecordingId = mbRecordingId,
        mbArtistId = mbArtistId,
    )

internal fun parseHistoryPage(json: String): HistoryPage {
    val root = JSONObject(json)
    val arr = root.optJSONArray("items") ?: JSONArray()
    val items = (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        HistoryItemUi(
            id = o.optString("id", ""),
            title = o.optString("title", ""),
            artist = o.optString("artist", ""),
            album = o.optString("album", ""),
            event = o.optString("event", ""),
            playedAtMs = parseServerTimestamp(o.optString("created_at", "")),
            artUrl = o.optString("art_url", ""),
            durationMs = o.optLong("duration_ms", 0L),
            source = o.optString("source", ""),
            ytVideoId = o.optString("yt_video_id", ""),
            ytBrowseId = o.optString("yt_browse_id", ""),
            subsonicSongId = o.optString("subsonic_song_id", ""),
            mbRecordingId = o.optString("mb_recording_id", ""),
            mbArtistId = o.optString("mb_artist_id", ""),
        )
    }
    return HistoryPage(items, hasMore = root.optBoolean("has_more", false))
}

/**
 * Parse the server's ISO-8601 UTC timestamps ("2026-10-05T06:28:54.382477Z", with or without
 * fractional seconds or a "Z"/"+00:00" suffix) to epoch millis; 0 if unreadable. Uses
 * SimpleDateFormat because java.time needs API 26 and the app supports 23.
 */
internal fun parseServerTimestamp(value: String): Long {
    val base = value.trim().take(19)
    if (base.length < 19) return 0L
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }
    val seconds = runCatching { format.parse(base)?.time }.getOrNull() ?: return 0L
    val fraction = Regex("""^\.(\d{1,3})""").find(value.trim().drop(19))?.groupValues?.get(1)
    val millis = fraction?.padEnd(3, '0')?.toLongOrNull() ?: 0L
    return seconds + millis
}

/**
 * Group items (newest first, as the server returns them) into day sections labelled
 * "Today", "Yesterday", or a date like "Sat, Oct 3" (with the year if it isn't this year).
 */
internal fun historySections(
    items: List<HistoryItemUi>,
    nowMs: Long,
    timeZone: TimeZone = TimeZone.getDefault(),
    locale: Locale = Locale.getDefault(),
): List<Pair<String, List<HistoryItemUi>>> {
    fun dayStart(ms: Long): Calendar = Calendar.getInstance(timeZone, locale).apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val today = dayStart(nowMs)
    val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }

    fun label(ms: Long): String {
        if (ms <= 0) return "Earlier"
        val day = dayStart(ms)
        return when {
            day.timeInMillis == today.timeInMillis -> "Today"
            day.timeInMillis == yesterday.timeInMillis -> "Yesterday"
            else -> {
                val pattern = if (day.get(Calendar.YEAR) == today.get(Calendar.YEAR)) "EEE, MMM d" else "EEE, MMM d, yyyy"
                SimpleDateFormat(pattern, locale).apply { this.timeZone = timeZone }.format(Date(ms))
            }
        }
    }

    val sections = LinkedHashMap<String, MutableList<HistoryItemUi>>()
    items.forEach { sections.getOrPut(label(it.playedAtMs)) { mutableListOf() }.add(it) }
    return sections.map { (k, v) -> k to v }
}
