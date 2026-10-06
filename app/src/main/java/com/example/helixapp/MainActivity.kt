package com.example.helixapp
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.helixapp.playback.PlayerRealtime
import com.example.helixapp.ui.theme.AppearancePrefs
import com.example.helixapp.ui.theme.HelixAccent
import com.example.helixapp.ui.theme.HelixBackground
import com.example.helixapp.ui.theme.HelixMuted
import com.example.helixapp.ui.theme.HelixSurface
import com.example.helixapp.ui.theme.HelixTheme
import androidx.compose.ui.text.style.TextAlign
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

class MainActivity : ComponentActivity() {
    private val openNowPlayingSignal = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra(EXTRA_OPEN_NOW_PLAYING, false) == true) {
            openNowPlayingSignal.intValue++
        }
        AppearancePrefs.load(this)
        setContent {
            HelixTheme {
                HelixApp(openNowPlayingSignal = openNowPlayingSignal.intValue)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The realtime connection runs while the app is on screen (or audio is playing).
        PlayerRealtime.ensureStarted(applicationContext)
        PlayerRealtime.setAppVisible(true)
    }

    override fun onStop() {
        PlayerRealtime.setAppVisible(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_NOW_PLAYING, false)) {
            openNowPlayingSignal.intValue++
        }
    }

    companion object {
        const val EXTRA_OPEN_NOW_PLAYING = "com.example.helixapp.extra.OPEN_NOW_PLAYING"
    }
}

@Composable
private fun SessionExpiredBanner(onLogIn: () -> Unit) {
    Surface(color = HelixSurface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // The app draws edge to edge; a Scaffold topBar must clear the status bar itself.
                .statusBarsPadding()
                .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Your Helix session expired. Log in again to keep listening.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            HelixTextButton(onClick = onLogIn) { Text("Log in", color = HelixAccent) }
        }
    }
}

private sealed class TabDest(
    val route: String,
    val label: String,
    val icon: @Composable () -> Unit
) {
    data object NowPlaying : TabDest(
        route = "nowplaying",
        label = "Player",
        icon = { Icon(Icons.Default.LibraryMusic, contentDescription = null) }
    )
    data object Library : TabDest(
        route = "library",
        label = "Library",
        icon = { Icon(Icons.Default.Tune, contentDescription = null) }
    )
    data object Search : TabDest(
        route = "search",
        label = "Search",
        icon = { Icon(Icons.Default.Search, contentDescription = null) }
    )
    data object Settings : TabDest(
        route = "settings",
        label = "Settings",
        icon = { Icon(Icons.Default.Settings, contentDescription = null) }
    )
}

@Composable
private fun HelixApp(openNowPlayingSignal: Int) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val tabs = listOf(
        TabDest.NowPlaying,
        TabDest.Library,
        TabDest.Search,
        TabDest.Settings,
    )

    fun isTabRoute(route: String?): Boolean = tabs.any { it.route == route }

    fun navigateToTab(dest: TabDest) {
        nav.navigate(dest.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun navigateToNowPlaying() {
        navigateToTab(TabDest.NowPlaying)
    }

    val ctx = LocalContext.current
    // No saved session (fresh install, or after Disconnect): show sign-in instead of the app.
    var needsLogin by remember { mutableStateOf(HelixPrefs.getSessionToken(ctx).isNullOrBlank()) }
    if (needsLogin) {
        // A Surface (not a bare background) so text gets the theme's content color.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = HelixBackground,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            WelcomeScreen(onConnected = { needsLogin = false })
        }
        return
    }

    val sessionExpired by AuthState.sessionExpired.collectAsState()
    val messageHost = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        UserMessages.messages.collect { messageHost.showSnackbar(it) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = HelixBackground,
            snackbarHost = { SnackbarHost(messageHost) },
            topBar = {
                // Stays up until the user logs in again (not on the login screen itself).
                if (sessionExpired && currentRoute != "settings/connection") {
                    SessionExpiredBanner(onLogIn = { nav.navigate("settings/connection") })
                }
            },
            bottomBar = {
                if (isTabRoute(currentRoute)) {
                    Surface(color = HelixSurface) {
                        NavigationBar(
                            containerColor = HelixSurface,
                            tonalElevation = 0.dp,
                        ) {
                            val itemColors = NavigationBarItemDefaults.colors(
                                selectedIconColor = HelixAccent,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = Color.Transparent,
                                unselectedIconColor = HelixMuted,
                                unselectedTextColor = HelixMuted,
                            )
                            tabs.forEach { tab ->
                                val selected = currentRoute == tab.route
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateToTab(tab) },
                                    icon = tab.icon,
                                    label = {
                                        Text(
                                            tab.label,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth(),
                                            maxLines = 1,
                                        )
                                    },
                                    alwaysShowLabel = true,
                                    colors = itemColors,
                                )
                            }
                        }
                    }
                }
            }
        ) { padding ->
            LaunchedEffect(openNowPlayingSignal) {
                if (openNowPlayingSignal > 0) {
                    nav.navigate(TabDest.NowPlaying.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }

            NavHost(
                navController = nav,
                startDestination = TabDest.NowPlaying.route,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                composable(TabDest.NowPlaying.route) { NowPlayingWithQueueSheet() }
                composable(TabDest.Library.route) {
                    StationsAndPlaylistsScreen(
                        onOpenPlaylist = { pid -> nav.navigate("playlist/$pid") },
                        onNavigateToNowPlaying = { navigateToNowPlaying() },
                    )
                }
                composable(TabDest.Search.route) {
                    SearchScreen(
                        onOpenAlbum = { album ->
                            val bid = album.browseId
                            if (bid.isNotBlank()) {
                                nav.navigate("album/${Uri.encode(bid)}")
                            }
                        },
                        onOpenArtist = { artist ->
                            val bid = artist.browseId
                            if (bid.isNotBlank()) {
                                nav.navigate("artist/${Uri.encode(bid)}")
                            }
                        },
                        onNavigateToNowPlaying = { navigateToNowPlaying() },
                    )
                }
                composable(TabDest.Settings.route) {
                    SettingsScreen(
                        onOpenConnection = { nav.navigate("settings/connection") },
                        onOpenPlayback = { nav.navigate("settings/playback") },
                        onOpenAppearance = { nav.navigate("settings/appearance") },
                        onOpenAdminUsers = { nav.navigate("settings/admin/users") },
                    )
                }
                composable("settings/connection") {
                    ConnectionSettingsScreen(
                        onBack = { nav.popBackStack() },
                        onDisconnected = {
                            // Reset to the Player so signing back in starts there.
                            navigateToTab(TabDest.NowPlaying)
                            needsLogin = true
                        },
                    )
                }
                composable("settings/playback") {
                    PlaybackSettingsScreen(onBack = { nav.popBackStack() })
                }
                composable("settings/appearance") {
                    AppearanceSettingsScreen(onBack = { nav.popBackStack() })
                }
                composable("settings/admin/users") {
                    AdminUsersScreen(onBack = { nav.popBackStack() })
                }

                composable("playlist/{playlistId}") { backStackEntry ->
                    val pid = backStackEntry.arguments?.getString("playlistId") ?: ""
                    PlaylistDetailScreen(
                        playlistId = pid,
                        onNavigateToNowPlaying = { navigateToNowPlaying() },
                        onClose = { nav.popBackStack() },
                    )
                }
                composable("album/{browseId}") { backStackEntry ->
                    val bid = backStackEntry.arguments?.getString("browseId") ?: ""
                    AlbumScreen(
                        browseId = bid,
                        onNavigateToNowPlaying = { navigateToNowPlaying() },
                    )
                }
                composable("artist/{browseId}") { backStackEntry ->
                    val bid = backStackEntry.arguments?.getString("browseId") ?: ""
                    ArtistScreen(
                        browseId = bid,
                        onOpenAlbum = { album ->
                            val albumBrowseId = album.browseId
                            if (albumBrowseId.isNotBlank()) {
                                nav.navigate("album/${Uri.encode(albumBrowseId)}")
                            }
                        },
                        onOpenArtist = { artist ->
                            val artistBrowseId = artist.browseId
                            if (artistBrowseId.isNotBlank()) {
                                nav.navigate("artist/${Uri.encode(artistBrowseId)}")
                            }
                        },
                        onNavigateToNowPlaying = { navigateToNowPlaying() },
                    )
                }
            }
        }
        GlobalLoadingOverlay()
    }
}
