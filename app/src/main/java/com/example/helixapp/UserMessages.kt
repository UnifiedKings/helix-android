package com.example.helixapp

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * App-wide snackbar messages, shown by the main scaffold in MainActivity.
 *
 * Messages outlive the screen that sent them, so an error raised just before navigating
 * (e.g. a failed Play that would have opened Now Playing) is still seen.
 */
object UserMessages {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun show(message: String) {
        _messages.tryEmit(message)
    }
}

/**
 * Run a user-triggered playback/queue action with consistent feedback: an optional loading
 * overlay while it runs, [onSuccess] (typically "open Now Playing") only when it worked, and
 * an app-wide error message when it didn't. Never lets an exception escape the scope.
 */
fun CoroutineScope.launchPlaybackAction(
    failureAction: String,
    overlayMessage: String? = null,
    successMessage: String? = null,
    onSuccess: () -> Unit = {},
    action: suspend () -> Unit,
): Job = launch {
    if (overlayMessage != null) showLoadingOverlay(overlayMessage)
    try {
        action()
        successMessage?.let(UserMessages::show)
        onSuccess()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("HELIX_PLAYER", "$failureAction failed", e)
        UserMessages.show(e.toUserMessage(failureAction))
    } finally {
        if (overlayMessage != null) hideLoadingOverlay()
    }
}
