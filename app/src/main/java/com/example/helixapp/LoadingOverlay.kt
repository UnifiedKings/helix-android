package com.example.helixapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LoadingOverlayState(val message: String)

object LoadingOverlayController {
    private val _state = MutableStateFlow<LoadingOverlayState?>(null)
    val state: StateFlow<LoadingOverlayState?> = _state.asStateFlow()

    fun show(message: String) {
        _state.value = LoadingOverlayState(message.ifBlank { "Loading…" })
    }

    fun hide() {
        _state.value = null
    }
}

/** Show the global overlay with a message. Safe to call from anywhere. */
fun showLoadingOverlay(message: String) = LoadingOverlayController.show(message)

/** Hide the global overlay. Safe to call from anywhere. */
fun hideLoadingOverlay() = LoadingOverlayController.hide()

@Composable
fun GlobalLoadingOverlay() {
    val state by LoadingOverlayController.state.collectAsState()
    val msg = state?.message ?: return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            // Consume all pointer events so the overlay blocks interaction with the app underneath.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(34.dp))
                Spacer(Modifier.size(12.dp))
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
