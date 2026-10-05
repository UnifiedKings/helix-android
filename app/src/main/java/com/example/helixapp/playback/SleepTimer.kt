package com.example.helixapp.playback

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Sleep timer: stop playback after a number of minutes, or when the current track ends.
 *
 * Process-wide rather than tied to a screen, so it keeps running with the app swiped away or
 * the screen locked (playback keeps the process alive). A countdown fades the volume out over
 * the last [FADE_MS], then pauses exactly like pressing Pause on the phone.
 */
object SleepTimer {
    sealed class State {
        data object Off : State()
        data class Countdown(val endsAtElapsedMs: Long) : State()
        data object EndOfTrack : State()
    }

    const val FADE_MS = 10_000L
    private const val FADE_STEP_MS = 200L

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    fun startCountdown(ctx: Context, minutes: Int) {
        val appCtx = ctx.applicationContext
        cancelJob(appCtx)
        val endsAt = SystemClock.elapsedRealtime() + minutes * 60_000L
        _state.value = State.Countdown(endsAt)
        Log.i("HELIX_PLAYER", "Sleep timer set for $minutes min")
        job = scope.launch { runCountdown(appCtx, endsAt) }
    }

    fun stopAtEndOfTrack(ctx: Context) {
        cancelJob(ctx.applicationContext)
        _state.value = State.EndOfTrack
        Log.i("HELIX_PLAYER", "Sleep timer set for end of track")
    }

    fun turnOff(ctx: Context) {
        cancelJob(ctx.applicationContext)
        _state.value = State.Off
        Log.i("HELIX_PLAYER", "Sleep timer turned off")
    }

    /**
     * Called by PlaybackService when a track ends. Returns true when the timer was waiting for
     * the end of the track, meaning playback should stop instead of continuing.
     */
    fun consumeTrackEnd(): Boolean {
        if (_state.value != State.EndOfTrack) return false
        _state.value = State.Off
        Log.i("HELIX_PLAYER", "Sleep timer: track ended, stopping playback")
        return true
    }

    private suspend fun runCountdown(ctx: Context, endsAt: Long) {
        delay((endsAt - FADE_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        while (true) {
            val remaining = endsAt - SystemClock.elapsedRealtime()
            if (remaining <= 0L) break
            setVolume(ctx, fadeVolume(remaining))
            delay(FADE_STEP_MS)
        }
        Log.i("HELIX_PLAYER", "Sleep timer finished; pausing")
        runCatching { PlayerCommandCoordinator.pause(ctx) }
            .onFailure { Log.w("HELIX_PLAYER", "Sleep timer pause failed", it) }
        setVolume(ctx, 1f)
        _state.value = State.Off
        job = null
    }

    private fun cancelJob(ctx: Context) {
        val wasFading = job != null
        job?.cancel()
        job = null
        // Undo a fade that was in progress.
        if (wasFading) setVolume(ctx, 1f)
    }

    private fun setVolume(ctx: Context, volume: Float) {
        PlaybackController.get(ctx) { it.volume = volume }
    }

    /** Player volume during the final fade: 1 until [FADE_MS] remain, then linear to 0. */
    internal fun fadeVolume(remainingMs: Long): Float = (remainingMs.toFloat() / FADE_MS).coerceIn(0f, 1f)

    /** Countdown text, e.g. "23:41" or "1:00:00". */
    internal fun formatRemaining(remainingMs: Long): String {
        val totalSec = ((remainingMs.coerceAtLeast(0L) + 999L) / 1000L).toInt()
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
