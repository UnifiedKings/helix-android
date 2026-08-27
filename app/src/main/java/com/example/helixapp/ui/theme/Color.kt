package com.example.helixapp.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

private val DefaultAccent = Color(0xFFA95F18)
private val DefaultBackground = Color(0xFF080A0D)
private val DefaultSurface = Color(0xFF0D1014)

// These are mutable app-local theme tokens. They are intentionally independent from
// Helix's web appearance settings and are persisted by AppearancePrefs.
var HelixAccent by mutableStateOf(DefaultAccent)
var HelixBackground by mutableStateOf(DefaultBackground)
var HelixSurface by mutableStateOf(DefaultSurface)

var HelixAccentStrong by mutableStateOf(Color(0xFFC87522))
var HelixAccentBright by mutableStateOf(Color(0xFFE18A36))
var HelixAccentContrast by mutableStateOf(Color(0xFFFFF8EF))

var HelixSurfaceSoft by mutableStateOf(Color(0xFF12161B))
var HelixSurfaceRaised by mutableStateOf(Color(0xFF171B20))
var HelixControl by mutableStateOf(Color(0xFF10141A))

var HelixText by mutableStateOf(Color(0xFFF5F2EC))
var HelixMuted by mutableStateOf(Color(0xFFAAA9A5))
var HelixFaint by mutableStateOf(Color(0xFF747570))
var HelixBorder by mutableStateOf(Color(0xFF252A31))
var HelixDanger by mutableStateOf(Color(0xFFFF647D))
var HelixSuccess by mutableStateOf(Color(0xFF35E09B))

// Compatibility aliases for older Compose screens while they are migrated.
val HelixPurple: Color get() = HelixAccent
val HelixNavy: Color get() = HelixSurfaceRaised
val HelixBackgroundDark: Color get() = HelixBackground
val HelixSurfaceDark: Color get() = HelixSurface

fun applyNativeAppearance(accent: Color, background: Color, surface: Color) {
    HelixAccent = accent
    HelixBackground = background
    HelixSurface = surface

    // Derive the rest of the native palette from the three user-facing choices.
    HelixAccentStrong = blend(accent, Color.White, 0.12f)
    HelixAccentBright = blend(accent, Color.White, 0.28f)
    HelixAccentContrast = if (relativeLuminance(accent) > 0.58f) Color(0xFF090909) else Color(0xFFFFF8EF)

    HelixSurfaceSoft = blend(surface, Color.White, 0.025f)
    HelixSurfaceRaised = blend(surface, Color.White, 0.06f)
    HelixControl = blend(surface, Color.White, 0.035f)
    HelixBorder = blend(surface, Color.White, 0.12f)

    val dark = relativeLuminance(background) < 0.45f
    HelixText = if (dark) Color(0xFFF5F2EC) else Color(0xFF171717)
    HelixMuted = if (dark) Color(0xFFAAA9A5) else Color(0xFF565656)
    HelixFaint = if (dark) Color(0xFF747570) else Color(0xFF777777)
}

private fun blend(a: Color, b: Color, amount: Float): Color {
    val t = amount.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = 1f,
    )
}

private fun relativeLuminance(color: Color): Float {
    fun channel(v: Float): Float = if (v <= 0.03928f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    return 0.2126f * channel(color.red) + 0.7152f * channel(color.green) + 0.0722f * channel(color.blue)
}
