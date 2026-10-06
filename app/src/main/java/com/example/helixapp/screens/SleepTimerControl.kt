package com.example.helixapp

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.helixapp.playback.SleepTimer
import com.example.helixapp.ui.theme.HelixAccent
import com.example.helixapp.ui.theme.HelixBorder
import com.example.helixapp.ui.theme.HelixSurface
import kotlinx.coroutines.delay

private val SLEEP_TIMER_MINUTES = listOf(5, 10, 15, 30, 45, 60)

private fun minutesLabel(minutes: Int) = when {
    minutes == 60 -> "1 hour"
    else -> "$minutes minutes"
}

/** Moon button for Now Playing; shows the time left while a sleep timer runs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerButton() {
    val ctx = LocalContext.current
    val state by SleepTimer.state.collectAsState()
    var showSheet by remember { mutableStateOf(false) }
    var remaining by remember { mutableStateOf("") }

    LaunchedEffect(state) {
        val s = state
        if (s is SleepTimer.State.Countdown) {
            while (true) {
                remaining = SleepTimer.formatRemaining(s.endsAtElapsedMs - SystemClock.elapsedRealtime())
                delay(1_000)
            }
        } else {
            remaining = ""
        }
    }

    val active = state != SleepTimer.State.Off
    val label = when (state) {
        is SleepTimer.State.Countdown -> remaining
        SleepTimer.State.EndOfTrack -> "End of track"
        SleepTimer.State.Off -> null
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable { showSheet = true }
            .semantics { contentDescription = if (active) "Sleep timer on" else "Sleep timer" }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = if (active) Icons.Filled.Bedtime else Icons.Outlined.Bedtime,
            contentDescription = null,
            tint = if (active) HelixAccent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = HelixAccent)
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = HelixSurface,
        ) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Text(
                    "Sleep timer",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                SLEEP_TIMER_MINUTES.forEach { minutes ->
                    SleepTimerOption(minutesLabel(minutes)) {
                        SleepTimer.startCountdown(ctx, minutes)
                        UserMessages.show("Sleep timer set for ${minutesLabel(minutes)}")
                        showSheet = false
                    }
                }
                SleepTimerOption("End of track") {
                    SleepTimer.stopAtEndOfTrack(ctx)
                    UserMessages.show("Playback will stop at the end of this track")
                    showSheet = false
                }
                if (active) {
                    HorizontalDivider(color = HelixBorder, modifier = Modifier.padding(vertical = 4.dp))
                    SleepTimerOption("Turn off timer", color = MaterialTheme.colorScheme.error) {
                        SleepTimer.turnOff(ctx)
                        UserMessages.show("Sleep timer off")
                        showSheet = false
                    }
                }
            }
        }
    }
}

@Composable
private fun SleepTimerOption(
    text: String,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
    )
}
