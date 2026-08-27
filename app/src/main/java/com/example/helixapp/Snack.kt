package com.example.helixapp

import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Show a snackbar without ever blocking the caller coroutine.
 *
 * SnackbarHostState.showSnackbar(...) is a suspend function that normally waits until the snackbar
 * is dismissed. This helper launches it in a separate coroutine so Play/queue flows can proceed
 * immediately (e.g., to hide the loading overlay / navigate).
 */
fun SnackbarHostState.showNonBlocking(scope: CoroutineScope, message: String) {
    scope.launch {
        try {
            showSnackbar(message)
        } catch (_: Throwable) {
            // Ignore snackbar failures (e.g., if host is not present).
        }
    }
}
