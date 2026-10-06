package com.example.helixapp.playback
import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
/**
 * Media3 callback wiring lockscreen / headset / notification controls to Helix backend.
 *
 * Important: We intentionally avoid double-applying transport commands. If we handle a command
 * manually (e.g., restart current track), we return RESULT_ERROR_NOT_SUPPORTED so Media3 won't
 * also apply the same command.
 */
class HelixSessionCallback(
    private val ctx: Context,
    private val scope: CoroutineScope,
) : MediaLibrarySession.Callback {
    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ConnectionResult {
        // The service is exported for Android Auto; only let known media surfaces connect.
        if (!isAllowedController(controller)) {
            Log.w("HELIX_PLAYER", "Rejecting media controller from ${controller.packageName}")
            return ConnectionResult.reject()
        }
        val base = super<MediaLibrarySession.Callback>.onConnect(session, controller)
        // Android system UI has very limited action slots. If shuffle/repeat are exposed they can
        // steal the only "extra" slot, hiding Next. Prefer transport actions.
        val b = Player.Commands.Builder().addAll(base.availablePlayerCommands)
        runCatching { b.remove(Player.COMMAND_SET_SHUFFLE_MODE) }
        runCatching { b.remove(Player.COMMAND_SET_REPEAT_MODE) }
        // Expose both MEDIA_ITEM and legacy variants for compatibility.
        b.add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        b.add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        b.add(Player.COMMAND_SEEK_TO_NEXT)
        b.add(Player.COMMAND_SEEK_TO_PREVIOUS)

        return ConnectionResult.accept(
            base.availableSessionCommands,
            b.build(),
        )
    }

    private fun isAllowedController(controller: MediaSession.ControllerInfo): Boolean =
        controller.packageName == ctx.packageName ||
            controller.isTrusted ||
            controller.packageName in ALLOWED_CONTROLLER_PACKAGES

    // ---- Browsing (Android Auto and other media browsers) -----------------------------------

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(LibraryResult.ofItem(HelixLibraryBrowser.root(), params))

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val item = (listOf(HelixLibraryBrowser.root()) + HelixLibraryBrowser.topLevel()).firstOrNull { it.mediaId == mediaId }
        return Futures.immediateFuture(
            if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        )
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
        try {
            val all = HelixLibraryBrowser.children(ctx, parentId)
            LibraryResult.ofItemList(pageOf(all, page, pageSize), params)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("HELIX_PLAYER", "Browsing $parentId failed", e)
            LibraryResult.ofError(SessionError.ERROR_IO)
        }
    }

    /** A browse item was played (e.g. tapped in Android Auto): start it through Helix. */
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        searchQueryOf(mediaItems)?.let { item ->
            return scope.future {
                playFromSearch(item)
                MediaSession.MediaItemsWithStartPosition(currentAsMediaItems(), 0, C.TIME_UNSET)
            }
        }
        // Helix's own controller loads stream items (with a URI); leave those to Media3.
        if (!isBrowseRequest(mediaItems)) {
            return super<MediaLibrarySession.Callback>.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
        }
        return scope.future {
            val target = mediaItems.getOrNull(startIndex) ?: mediaItems.first()
            startFromBrowser(target.mediaId)
            MediaSession.MediaItemsWithStartPosition(currentAsMediaItems(), 0, C.TIME_UNSET)
        }
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> {
        searchQueryOf(mediaItems)?.let { item ->
            return scope.future {
                playFromSearch(item)
                currentAsMediaItems().toMutableList()
            }
        }
        if (!isBrowseRequest(mediaItems)) {
            return super<MediaLibrarySession.Callback>.onAddMediaItems(mediaSession, controller, mediaItems)
        }
        return scope.future {
            startFromBrowser(mediaItems.first().mediaId)
            currentAsMediaItems().toMutableList()
        }
    }

    /** Browsers send bare browse ids; Media3 strips stream URIs from other apps' items. */
    private fun isBrowseRequest(items: List<MediaItem>): Boolean =
        items.isNotEmpty() && items.all { it.localConfiguration == null && HelixLibraryBrowser.isPlayableId(it.mediaId) }

    /**
     * A play-from-search request ("Hey Google, play X on Helix"): Media3 delivers it as a
     * single item with no id and the spoken phrase as its search query.
     */
    private fun searchQueryOf(items: List<MediaItem>): MediaItem? =
        items.singleOrNull()?.takeIf {
            it.mediaId.isBlank() && it.localConfiguration == null && it.requestMetadata.searchQuery != null
        }

    private suspend fun playFromSearch(item: MediaItem) {
        val query = VoiceSearch.parse(item.requestMetadata.searchQuery, VoiceSearch.Hints.from(item.requestMetadata.extras))
        try {
            VoiceSearch.play(ctx, query, scope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("HELIX_PLAYER", "Voice search for $query failed: ${e.javaClass.simpleName}: ${e.message}", e)
            throw e
        }
    }

    // Android Auto's search button: results are fetched in onSearch and paged out in
    // onGetSearchResult.
    private val searchResults = java.util.concurrent.ConcurrentHashMap<String, List<MediaItem>>()

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = scope.future {
        try {
            val items = HelixLibraryBrowser.search(ctx, query)
            searchResults.clear()
            searchResults[query] = items
            session.notifySearchResultChanged(browser, query, items.size, params)
            LibraryResult.ofVoid()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("HELIX_PLAYER", "Search for $query failed", e)
            LibraryResult.ofError(SessionError.ERROR_IO)
        }
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
        try {
            val all = searchResults[query] ?: HelixLibraryBrowser.search(ctx, query).also { searchResults[query] = it }
            LibraryResult.ofItemList(pageOf(all, page, pageSize), params)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("HELIX_PLAYER", "Search for $query failed", e)
            LibraryResult.ofError(SessionError.ERROR_IO)
        }
    }

    private fun pageOf(all: List<MediaItem>, page: Int, pageSize: Int): List<MediaItem> {
        val from = (page * pageSize).coerceAtMost(all.size)
        val to = if (pageSize > 0) (from + pageSize).coerceAtMost(all.size) else all.size
        return all.subList(from, to)
    }

    private suspend fun startFromBrowser(mediaId: String) {
        Log.i("HELIX_PLAYER", "Playing $mediaId from a media browser")
        if (!HelixLibraryBrowser.play(ctx, mediaId)) throw IllegalArgumentException("Unknown media id $mediaId")
    }

    /**
     * The backend's new current item as the Media3 item the player is (or is about to be)
     * playing. Media3 hands this back to the player; it's tagged so HelixForwardingPlayer
     * ignores it instead of reloading what the coordinator already loaded.
     */
    private fun currentAsMediaItems(): List<MediaItem> {
        val now = PlayerStateStore.state.value?.now ?: throw IllegalStateException("Nothing is playing")
        val item = PlaybackController.mediaItemFor(ctx, now)
        return listOf(item.buildUpon().setRequestMetadata(BROWSER_REPEAT).build())
    }

    @OptIn(UnstableApi::class)
    override fun onPlayerCommandRequest(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        playerCommand: Int,
    ): Int {
        // PlaybackController is an in-process MediaController owned by Helix itself. Its commands
        // are the final step of backend -> Media3 synchronization, so they must be allowed to reach
        // ExoPlayer directly. Intercepting them here would recursively route play/pause back through
        // PlayerCommandCoordinator and prevent the local player from ever applying the command.
        //
        // External controllers (SystemUI, lockscreen, headset controls, etc.) still flow through
        // the coordinator below so Helix remains authoritative for queue and playback state.
        //
        // Media3 also runs an in-process media notification controller, and attributes System UI
        // (notification shade + lock screen card) commands to it. It shares our package name, so
        // it must be excluded here or lock screen Next/Previous reach the single-item ExoPlayer
        // timeline directly (Next does nothing, Previous only restarts the track).
        Log.d("HELIX_PLAYER", "Session command $playerCommand from ${controller.packageName} (notification controller: ${session.isMediaNotificationController(controller)})")
        if (controller.packageName == ctx.packageName && !session.isMediaNotificationController(controller)) {
            return SessionResult.RESULT_SUCCESS
        }

        when (playerCommand) {
            Player.COMMAND_PLAY_PAUSE -> {
                scope.launch {
                    runCatching {
                        if (session.player.isPlaying) {
                            PlayerCommandCoordinator.pause(ctx)
                        } else {
                            PlayerCommandCoordinator.resume(ctx)
                        }
                    }.onFailure {
                        Log.e("HELIX_PLAYER", "Play/pause command failed", it)
                        // Repair the local session from authoritative backend state even if a
                        // transport request failed part-way through.
                        runCatching { PlayerCommandCoordinator.syncFromBackend(ctx, forceLoadStream = true) }
                    }
                }

                // Consume the command. Media3 must not independently mutate the local player;
                // the coordinator applies the backend result after the command completes.
                return SessionResult.RESULT_ERROR_NOT_SUPPORTED
            }
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT -> {
                scope.launch {
                    runCatching {
                        PlayerCommandCoordinator.next(ctx)
                    }.onFailure {
                        Log.e("HELIX_PLAYER", "Next command failed", it)
                        runCatching { PlayerCommandCoordinator.syncFromBackend(ctx, forceLoadStream = true) }
                    }
                }
                return SessionResult.RESULT_ERROR_NOT_SUPPORTED
            }
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS -> {
                // Desired behavior (match common music apps):
                // - If >3s elapsed in the current track: restart the track (local seek only)
                // - Otherwise: go to previous track (backend truth) if it exists
                //
                // We only need backend involvement for the "previous track" case.
                // For the "restart current track" case, allowing Media3 to handle it locally
                // avoids unnecessary backend calls and prevents extra refresh latency.
                val elapsedMs = session.player.currentPosition
                if (elapsedMs > 3_000L) {
                    // Media3 intentionally has no real previous item. Restart the current track
                    // ourselves and consume the transport command.
                    session.player.seekTo(0L)
                    return SessionResult.RESULT_ERROR_NOT_SUPPORTED
                }
                // <= 3s: move the backend queue pointer. This shares the same serialization
                // gate as Next, Play/Pause, and websocket-driven Media3 syncs.
                scope.launch {
                    runCatching {
                        PlayerCommandCoordinator.previous(ctx)
                    }.onFailure {
                        Log.e("HELIX_PLAYER", "Previous command failed", it)
                        runCatching { PlayerCommandCoordinator.syncFromBackend(ctx, forceLoadStream = true) }
                    }
                }
                // Consume so Media3 doesn't also advance the local queue (which can desync).
                return SessionResult.RESULT_ERROR_NOT_SUPPORTED
            }

            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> {
                // Let the player handle local seek if it can. We don't mirror scrubbing to backend yet.
                return SessionResult.RESULT_SUCCESS
            }

            else -> return SessionResult.RESULT_SUCCESS
        }
    }

    companion object {
        private const val EXTRA_BROWSER_REPEAT = "com.example.helixapp.BROWSER_REPEAT"
        private val BROWSER_REPEAT = MediaItem.RequestMetadata.Builder()
            .setExtras(android.os.Bundle().apply { putBoolean(EXTRA_BROWSER_REPEAT, true) })
            .build()

        /** True for the item [onSetMediaItems] hands back after the coordinator already loaded it. */
        fun isBrowserRepeat(item: MediaItem): Boolean =
            item.requestMetadata.extras?.getBoolean(EXTRA_BROWSER_REPEAT) == true

        /** Media surfaces allowed to connect besides Helix itself and trusted system callers. */
        private val ALLOWED_CONTROLLER_PACKAGES = setOf(
            "com.google.android.projection.gearhead", // Android Auto
            "com.google.android.carassistant",
            "com.google.android.googlequicksearchbox", // Google Assistant
            "com.google.android.apps.bard", // Gemini
            "com.google.android.apps.googleassistant",
            "com.android.systemui",
        )
    }
}
