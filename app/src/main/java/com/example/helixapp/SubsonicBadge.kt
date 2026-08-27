package com.example.helixapp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Small overlay badge indicating the item is available on the configured Subsonic server.
 * Designed to be placed on top of cover art (bottom corner).
 */
@Composable
fun SubsonicBadge(
    visible: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(150)),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier.size(size),
            contentAlignment = Alignment.Center,
        ) {
            // Soft green glow behind the icon. Keep it subtle and layout-stable.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF39FF14), CircleShape)
                    .alpha(0.32f)
                    .blur(7.dp)
            )
            Image(
                painter = painterResource(id = R.drawable.on_subsonic),
                contentDescription = "Available on Subsonic",
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
