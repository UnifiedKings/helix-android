package com.example.helixapp

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.helixapp.ui.theme.HelixAccent
import com.example.helixapp.ui.theme.HelixBorder
import com.example.helixapp.ui.theme.HelixMuted
import com.example.helixapp.ui.theme.HelixSurface

@Composable
fun StationsAndPlaylistsScreen(
    onOpenPlaylist: (String) -> Unit,
    onNavigateToNowPlaying: () -> Unit = {},
) {
    var tab by remember { mutableIntStateOf(0) } // 0 = stations, 1 = playlists
    var stationCreateRequest by remember { mutableIntStateOf(0) }
    var playlistCreateRequest by remember { mutableIntStateOf(0) }

    Surface(modifier = Modifier.fillMaxSize(), color = HelixSurface) {
        Column(modifier = Modifier.fillMaxSize()) {
            LibraryHeader(
                onAdd = {
                    if (tab == 0) stationCreateRequest++ else playlistCreateRequest++
                }
            )

            LibraryTabs(
                selectedTab = tab,
                onSelectTab = { tab = it },
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (tab == 0) {
                    StationsScreen(
                        onNavigateToNowPlaying = onNavigateToNowPlaying,
                        createRequestKey = stationCreateRequest,
                    )
                } else {
                    PlaylistsScreen(
                        onOpenPlaylist = onOpenPlaylist,
                        onNavigateToNowPlaying = onNavigateToNowPlaying,
                        createRequestKey = playlistCreateRequest,
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryHeader(onAdd: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(
                painter = painterResource(id = R.drawable.helix_logo),
                contentDescription = "Helix",
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(HelixAccent),
                modifier = Modifier.size(38.dp),
            )
            Text(
                text = "Library",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
        }

        IconButton(onClick = onAdd) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Create",
                tint = HelixAccent,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

@Composable
private fun LibraryTabs(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        LibraryTab(
            label = "Stations",
            selected = selectedTab == 0,
            onClick = { onSelectTab(0) },
            modifier = Modifier.weight(1f),
        )
        LibraryTab(
            label = "Playlists",
            selected = selectedTab == 1,
            onClick = { onSelectTab(1) },
            modifier = Modifier.weight(1f),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(HelixBorder)
    )
}

@Composable
private fun LibraryTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) HelixAccent else HelixMuted,
        )
        Box(
            modifier = Modifier
                .padding(top = 10.dp)
                .fillMaxWidth(0.74f)
                .height(2.dp)
                .background(if (selected) HelixAccent else androidx.compose.ui.graphics.Color.Transparent)
        )
    }
}
