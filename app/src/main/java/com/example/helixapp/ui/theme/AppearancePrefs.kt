package com.example.helixapp.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color

object AppearancePrefs {
    private const val PREFS = "helix_native_appearance"
    private const val KEY_ACCENT = "accent"
    private const val KEY_BACKGROUND = "background"
    private const val KEY_SURFACE = "surface"

    const val DEFAULT_ACCENT = "#A95F18"
    const val DEFAULT_BACKGROUND = "#080A0D"
    const val DEFAULT_SURFACE = "#0D1014"

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val accent = parseHex(prefs.getString(KEY_ACCENT, DEFAULT_ACCENT), Color(0xFFA95F18))
        val background = parseHex(prefs.getString(KEY_BACKGROUND, DEFAULT_BACKGROUND), Color(0xFF080A0D))
        val surface = parseHex(prefs.getString(KEY_SURFACE, DEFAULT_SURFACE), Color(0xFF0D1014))
        applyNativeAppearance(accent, background, surface)
    }

    fun accentHex(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACCENT, DEFAULT_ACCENT) ?: DEFAULT_ACCENT
    fun backgroundHex(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_BACKGROUND, DEFAULT_BACKGROUND) ?: DEFAULT_BACKGROUND
    fun surfaceHex(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SURFACE, DEFAULT_SURFACE) ?: DEFAULT_SURFACE

    fun save(context: Context, accentHex: String, backgroundHex: String, surfaceHex: String): Boolean {
        val accent = parseHexOrNull(accentHex) ?: return false
        val background = parseHexOrNull(backgroundHex) ?: return false
        val surface = parseHexOrNull(surfaceHex) ?: return false

        val a = normalizeHex(accentHex)
        val b = normalizeHex(backgroundHex)
        val s = normalizeHex(surfaceHex)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCENT, a)
            .putString(KEY_BACKGROUND, b)
            .putString(KEY_SURFACE, s)
            .apply()
        applyNativeAppearance(accent, background, surface)
        return true
    }

    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        applyNativeAppearance(Color(0xFFA95F18), Color(0xFF080A0D), Color(0xFF0D1014))
    }

    fun parseHexOrNull(value: String?): Color? {
        val raw = value?.trim()?.removePrefix("#") ?: return null
        if (raw.length != 6 || raw.any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }) return null
        return runCatching { Color(0xFF000000 or raw.toLong(16)) }.getOrNull()
    }

    private fun parseHex(value: String?, fallback: Color): Color = parseHexOrNull(value) ?: fallback

    fun normalizeHex(value: String): String = "#" + value.trim().removePrefix("#").uppercase()
}
