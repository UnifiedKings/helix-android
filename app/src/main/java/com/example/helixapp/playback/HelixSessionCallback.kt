package com.example.helixapp.playback
import android.content.Context
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.SessionResult
import com.example.helixapp.HelixClient
import com.example.helixapp.HelixPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
/**
 * Media3 callback wiring lockscreen / headset / notification controls to Helix backend.
 *
 * Important: We intentionally avoid double-applying transport commands. If we handle a command
 * manually (e.g., restart current track), we return RESULT_ERROR_NOT_SUPPORTED so Media3 won't
 * also apply the same command.
 */
class HelixSessionCallback(
    private val ctx: Context,
    private val player: Player,
    private val scope: CoroutineScope,
) : MediaSession.Callback {
    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ConnectionResult {
        val base = super<MediaSession.Callback>.onConnect(session, controller)
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
    override fun onPlayerCommandRequest(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        playerCommand: Int,
    ): Int {
        when (playerCommand) {
            Player.COMMAND_PLAY_PAUSE -> {
                // Helix is the source of truth. Always re-sync before resuming rather than letting
                // Media3 immediately resume whatever item it happens to have cached locally.
                //
                // This matters after the app has been idle/backgrounded for a while: Android may
                // still have an old stream positioned at (or near) EOF. If Media3 resumes that item
                // first, its natural-ended callback can advance Helix to the next queue entry
                // before the backend state has been applied.
                if (player.isPlaying) {
                    scope.launch {
                        runCatching {
                            player.pause()
                            val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                            withContext(Dispatchers.IO) { api.pause() }
                        }.onFailure {
                            Log.e("HELIX_PLAYER", "Pause command failed", it)
                        }
                    }
                } else {
                    scope.launch {
                        runCatching {
                            // Fetch backend truth first and force Media3 to reload the current
                            // Helix queue item. refreshAndSync() also applies backend play/pause.
                            HelixTransport.refreshAndSync(ctx, forceLoadStream = true)
                            val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                            withContext(Dispatchers.IO) { api.resume() }

                            // The state we fetched may have been paused, so explicitly start the
                            // freshly loaded current item only after the backend resume succeeds.
                            player.play()
                            HelixTransport.markInitialSynced()
                        }.onFailure {
                            Log.e("HELIX_PLAYER", "Play command failed", it)
                        }
                    }
                }

                // Consume the command. If we returned RESULT_SUCCESS, Media3 would also apply play
                // immediately, which recreates the stale-item/skip race described above.
                return SessionResult.RESULT_ERROR_NOT_SUPPORTED
            }
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT -> {
                // Match the in-app Now Playing controls:
                // - Call backend /api/playback/next
                // - Force a refreshAndSync() so local playback follows backend truth
                scope.launch {
                    runCatching {
                        val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                        withContext(Dispatchers.IO) { api.next() }
                        HelixTransport.refreshAndSync(ctx, forceLoadStream = true)
                    }.onFailure {
                        Log.e("HELIX_PLAYER", "Next command failed", it)
                    }
                }
                // Consume so Media3 doesn't also advance the local queue (which can desync).
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
                val elapsedMs = player.currentPosition
                if (elapsedMs > 3_000L) {
                    // Media3 intentionally has no real previous item. Restart the current track
                    // ourselves and consume the transport command.
                    player.seekTo(0L)
                    return SessionResult.RESULT_ERROR_NOT_SUPPORTED
                }
                // <= 3s: move backend queue pointer.
                scope.launch {
                    runCatching {
                        val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
                        withContext(Dispatchers.IO) { api.prev() }
                        HelixTransport.refreshAndSync(ctx, forceLoadStream = true)
                    }.onFailure {
                        Log.e("HELIX_PLAYER", "Previous command failed", it)
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
}
