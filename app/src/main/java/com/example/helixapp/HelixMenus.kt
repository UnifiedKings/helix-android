package com.example.helixapp

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

val HelixMenuShape = RoundedCornerShape(18.dp)

@Composable
fun HelixTrackOverflowMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onPlay: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToSubsonic: () -> Unit,
    showAddToSubsonic: Boolean = true,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = HelixMenuShape,
    ) {
        DropdownMenuItem(
            text = { Text("Play") },
            leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
            onClick = onPlay,
        )
        DropdownMenuItem(
            text = { Text("Add to Queue") },
            leadingIcon = { Icon(Icons.Default.QueueMusic, contentDescription = null) },
            onClick = onAddToQueue,
        )
        if (showAddToSubsonic) {
            DropdownMenuItem(
                text = { Text("Add to Subsonic") },
                leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                onClick = onAddToSubsonic,
            )
        }
    }
}
