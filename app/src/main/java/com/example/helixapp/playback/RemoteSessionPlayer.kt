package com.example.helixapp.playback

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.example.helixapp.HelixImages
import com.example.helixapp.HelixPrefs
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The media session's player in remote mode ("Play on this device" off): plays no audio, but
 * mirrors the backend's current song and play state from [PlayerStateStore], so the
 * notification and lock screen show what's playing on the user's other devices. Its controls
 * act on the backend through [PlayerCommandCoordinator].
 *
 * The backend has no playback position, so no duration is reported and no seek bar is shown.
 */
@OptIn(UnstableApi::class)
class RemoteSessionPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Called whenever the mirrored play state changes (true = the backend is playing). */
    private val onRemotePlayingChanged: (Boolean) -> Unit,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private var snapshot: PlayerStateSnapshot? = PlayerStateStore.state.value

    private val collector = scope.launch {
        PlayerStateStore.state.collect {
            snapshot = it
            invalidateState()
            onRemotePlayingChanged(it?.isPlaying == true && it.now != null)
        }
    }

    override fun getState(): State {
        val builder = State.Builder().setAvailableCommands(COMMANDS)
        val s = snapshot
        val now = s?.now ?: return builder.setPlaybackState(Player.STATE_IDLE).build()

        val art = HelixImages.absoluteUrl(HelixPrefs.getBaseUrl(context), now.artUrl)
        val metadata = MediaMetadata.Builder()
            .setTitle(now.title)
            .setArtist(now.artist)
            .setAlbumTitle(now.album)
            .apply { if (art.isNotBlank()) setArtworkUri(Uri.parse(art)) }
            .build()
        // A distinct id, so screens never mistake this for locally loaded audio.
        val item = MediaItem.Builder().setMediaId("remote:${now.queueItemId}").setMediaMetadata(metadata).build()

        return builder
            .setPlaylist(
                listOf(
                    MediaItemData.Builder(now.queueItemId)
                        .setMediaItem(item)
                        .setMediaMetadata(metadata)
                        .setIsSeekable(false)
                        .build()
                )
            )
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(s.isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setContentPositionMs(0L)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> =
        runOnBackend("play/pause") {
            if (playWhenReady) PlayerCommandCoordinator.resume(context) else PlayerCommandCoordinator.pause(context)
        }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> =
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ->
                runOnBackend("next") { PlayerCommandCoordinator.next(context) }
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ->
                runOnBackend("previous") { PlayerCommandCoordinator.previous(context) }
            else -> Futures.immediateVoidFuture()
        }

    override fun handleStop(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleRelease(): ListenableFuture<*> {
        collector.cancel()
        return Futures.immediateVoidFuture()
    }

    private fun runOnBackend(what: String, block: suspend () -> Unit): ListenableFuture<*> {
        Log.d("HELIX_PLAYER", "Remote session command: $what")
        val done = SettableFuture.create<Any?>()
        scope.launch {
            runCatching { block() }.onFailure { Log.w("HELIX_PLAYER", "Remote $what failed", it) }
            done.set(null)
        }
        return done
    }

    private companion object {
        val COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_STOP,
                Player.COMMAND_RELEASE,
            )
            .build()
    }
}
