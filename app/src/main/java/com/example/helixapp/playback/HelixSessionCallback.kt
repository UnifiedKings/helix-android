package com.example.helixapp.playback
import android.content.Context
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.SessionResult
import kotlinx.coroutines.CoroutineScope
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
                scope.launch {
                    runCatching {
                        if (player.isPlaying) {
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
                val elapsedMs = player.currentPosition
                if (elapsedMs > 3_000L) {
                    // Media3 intentionally has no real previous item. Restart the current track
                    // ourselves and consume the transport command.
                    player.seekTo(0L)
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
}
