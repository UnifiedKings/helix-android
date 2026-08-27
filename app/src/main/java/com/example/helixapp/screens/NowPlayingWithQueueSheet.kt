package com.example.helixapp

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.example.helixapp.ui.theme.HelixMuted
import com.example.helixapp.ui.theme.HelixSurface
import kotlinx.coroutines.launch

/**
 * The queue is intentionally invisible while closed.
 *
 * There is no queue button, peek row, persistent handle, or dedicated queue tab on the player.
 * Dragging upward anywhere on Now Playing opens the queue drawer; dragging the sheet down closes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingWithQueueSheet() {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val showSheet = remember { mutableStateOf(false) }

    LaunchedEffect(sheetState.isVisible) {
        if (!sheetState.isVisible) showSheet.value = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (!showSheet.value && !sheetState.isVisible) {
                    Modifier.pointerInput(Unit) {
                        var totalDrag = 0f
                        detectVerticalDragGestures(
                            onVerticalDrag = { _, dragAmount ->
                                totalDrag += dragAmount
                                if (!showSheet.value && totalDrag < -42f) {
                                    showSheet.value = true
                                    scope.launch { sheetState.show() }
                                }
                            },
                            onDragEnd = { totalDrag = 0f },
                            onDragCancel = { totalDrag = 0f },
                        )
                    }
                } else Modifier
            )
    ) {
        NowPlayingScreen()

        if (showSheet.value) {
            ModalBottomSheet(
                onDismissRequest = {
                    showSheet.value = false
                    scope.launch { sheetState.hide() }
                },
                sheetState = sheetState,
                containerColor = HelixSurface,
                scrimColor = Color.Black.copy(alpha = 0.54f),
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                dragHandle = {
                    Box(
                        modifier = Modifier
                            .width(44.dp)
                            .height(4.dp)
                            .background(HelixMuted.copy(alpha = 0.65f), RoundedCornerShape(999.dp))
                    )
                },
            ) {
                QueueScreen()
            }
        }
    }
}
