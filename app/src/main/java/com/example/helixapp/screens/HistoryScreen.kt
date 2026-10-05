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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import org.json.JSONObject

@Composable
fun HistoryScreen(
    onNavigateToNowPlaying: () -> Unit = {},
    viewModel: HistoryViewModel = helixViewModel(::HistoryViewModel),
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val filter = state.filter
    val items = state.items
    val loading = state.loading

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

    // Coming back to the tab or the app after a while: refresh, since more has been played.
    LaunchedEffect(Unit) { viewModel.refreshIfStale() }
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshIfStale()
        onPauseOrDispose {}
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            HistoryFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { viewModel.setFilter(f) },
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

        val message = state.error
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
            if (state.hasMore) {
                item(key = "load-more") {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        HelixTextButton(onClick = viewModel::loadMore, enabled = !loading) {
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
