package com.example.helixapp

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.example.helixapp.ui.theme.AppearancePrefs
import com.example.helixapp.ui.theme.HelixAccent
import com.example.helixapp.ui.theme.HelixBorder
import com.example.helixapp.ui.theme.HelixSurfaceRaised
import kotlin.math.roundToInt

private data class ColorPreset(val name: String, val hex: String)

private enum class AppearanceColorTarget(val title: String) {
    Accent("Accent color"),
    Background("Background color"),
    Surface("Surface color"),
}

private val accentPresets = listOf(
    ColorPreset("Helix", "#A95F18"),
    ColorPreset("Gold", "#D18B26"),
    ColorPreset("Red", "#C6504B"),
    ColorPreset("Blue", "#4F7FC7"),
    ColorPreset("Violet", "#8568C7"),
    ColorPreset("Green", "#4E8F68"),
)

private val backgroundPresets = listOf(
    ColorPreset("Black", "#050607"),
    ColorPreset("Helix", "#080A0D"),
    ColorPreset("Slate", "#101318"),
)

private val surfacePresets = listOf(
    ColorPreset("Black", "#0B0D0F"),
    ColorPreset("Helix", "#0D1014"),
    ColorPreset("Slate", "#161A20"),
)

@Composable
fun AppearanceSettingsScreen(onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var accentHex by remember { mutableStateOf(AppearancePrefs.accentHex(ctx)) }
    var backgroundHex by remember { mutableStateOf(AppearancePrefs.backgroundHex(ctx)) }
    var surfaceHex by remember { mutableStateOf(AppearancePrefs.surfaceHex(ctx)) }
    var pickerTarget by remember { mutableStateOf<AppearanceColorTarget?>(null) }
    var status by remember { mutableStateOf("") }

    fun saveCurrent(nextAccent: String = accentHex, nextBackground: String = backgroundHex, nextSurface: String = surfaceHex) {
        if (AppearancePrefs.save(ctx, nextAccent, nextBackground, nextSurface)) {
            accentHex = AppearancePrefs.accentHex(ctx)
            backgroundHex = AppearancePrefs.backgroundHex(ctx)
            surfaceHex = AppearancePrefs.surfaceHex(ctx)
            status = ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Column {
                Text("Appearance", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Android app only",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            "These colors are stored on this device and do not change your Helix web theme.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AppearanceSection("Accent", "Selected tabs, buttons, active controls, and the Helix logo.") {
            ColorPresetRow(
                presets = accentPresets,
                selectedHex = accentHex,
                onSelect = { preset -> saveCurrent(nextAccent = preset.hex) },
            )
            CustomColorButton(
                hex = accentHex,
                label = "Custom accent",
                onClick = { pickerTarget = AppearanceColorTarget.Accent },
            )
        }

        AppearanceSection("Background", "The main canvas behind every native screen.") {
            ColorPresetRow(
                presets = backgroundPresets,
                selectedHex = backgroundHex,
                onSelect = { preset ->
                    val nextSurface = when (preset.hex) {
                        "#050607" -> "#0B0D0F"
                        "#101318" -> "#161A20"
                        else -> AppearancePrefs.DEFAULT_SURFACE
                    }
                    saveCurrent(nextBackground = preset.hex, nextSurface = nextSurface)
                },
            )
            CustomColorButton(
                hex = backgroundHex,
                label = "Custom background",
                onClick = { pickerTarget = AppearanceColorTarget.Background },
            )
        }

        AppearanceSection("Surface", "Menus, bottom navigation, sheets, and raised areas.") {
            ColorPresetRow(
                presets = surfacePresets,
                selectedHex = surfaceHex,
                onSelect = { preset -> saveCurrent(nextSurface = preset.hex) },
            )
            CustomColorButton(
                hex = surfaceHex,
                label = "Custom surface",
                onClick = { pickerTarget = AppearanceColorTarget.Surface },
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = HelixSurfaceRaised,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, HelixBorder),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Preview", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "This is a raised native surface",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(HelixAccent, RoundedCornerShape(5.dp))
                    )
                }
            }
        }

        if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        OutlinedButton(
            onClick = {
                AppearancePrefs.reset(ctx)
                accentHex = AppearancePrefs.DEFAULT_ACCENT
                backgroundHex = AppearancePrefs.DEFAULT_BACKGROUND
                surfaceHex = AppearancePrefs.DEFAULT_SURFACE
                status = ""
            },
        ) {
            Text("Reset to Helix defaults")
        }
    }

    pickerTarget?.let { target ->
        val initialHex = when (target) {
            AppearanceColorTarget.Accent -> accentHex
            AppearanceColorTarget.Background -> backgroundHex
            AppearanceColorTarget.Surface -> surfaceHex
        }
        ColorPickerDialog(
            title = target.title,
            initialHex = initialHex,
            onDismiss = { pickerTarget = null },
            onApply = { pickedHex ->
                when (target) {
                    AppearanceColorTarget.Accent -> saveCurrent(nextAccent = pickedHex)
                    AppearanceColorTarget.Background -> saveCurrent(nextBackground = pickedHex)
                    AppearanceColorTarget.Surface -> saveCurrent(nextSurface = pickedHex)
                }
                pickerTarget = null
            },
        )
    }
}

@Composable
private fun AppearanceSection(
    title: String,
    description: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

@Composable
private fun ColorPresetRow(
    presets: List<ColorPreset>,
    selectedHex: String,
    onSelect: (ColorPreset) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.forEach { preset ->
            val color = AppearancePrefs.parseHexOrNull(preset.hex) ?: Color.Gray
            val selected = preset.hex.equals(selectedHex, ignoreCase = true)
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(preset) },
                color = color,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else HelixBorder),
            ) {
                Box(modifier = Modifier.height(42.dp), contentAlignment = Alignment.Center) {
                    if (selected) {
                        Text("✓", color = if (color.luminance() > 0.5f) Color.Black else Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomColorButton(
    hex: String,
    label: String,
    onClick: () -> Unit,
) {
    val color = AppearancePrefs.parseHexOrNull(hex) ?: Color.Gray
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = Color.Transparent,
        shape = RoundedCornerShape(9.dp),
        border = BorderStroke(1.dp, HelixBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(color, RoundedCornerShape(7.dp))
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(hex.uppercase(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ColorLens, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ColorPickerDialog(
    title: String,
    initialHex: String,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
) {
    val initial = AppearancePrefs.parseHexOrNull(initialHex) ?: HelixAccent
    val hsv = remember(initialHex) { FloatArray(3).also { AndroidColor.colorToHSV(initial.toArgb(), it) } }
    var hue by remember(initialHex) { mutableFloatStateOf(hsv[0]) }
    var saturation by remember(initialHex) { mutableFloatStateOf(hsv[1]) }
    var value by remember(initialHex) { mutableFloatStateOf(hsv[2]) }

    val selectedColor = Color.hsv(hue, saturation, value)
    val selectedHex = colorToHex(selectedColor)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SaturationValuePicker(
                    hue = hue,
                    saturation = saturation,
                    value = value,
                    onChanged = { s, v ->
                        saturation = s
                        value = v
                    },
                )
                HuePicker(hue = hue, onHueChanged = { hue = it })

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        modifier = Modifier.size(42.dp),
                        color = selectedColor,
                        shape = RoundedCornerShape(9.dp),
                        border = BorderStroke(1.dp, HelixBorder),
                    ) {}
                    Column {
                        Text(selectedHex, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Drag anywhere in the picker to fine-tune",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onApply(selectedHex) }) { Text("Apply") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SaturationValuePicker(
    hue: Float,
    saturation: Float,
    value: Float,
    onChanged: (Float, Float) -> Unit,
) {
    var widthPx by remember { mutableIntStateOf(1) }
    var heightPx by remember { mutableIntStateOf(1) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .onSizeChanged {
                widthPx = it.width.coerceAtLeast(1)
                heightPx = it.height.coerceAtLeast(1)
            }
            .pointerInput(hue, widthPx, heightPx) {
                detectTapGestures { offset ->
                    onChanged(
                        (offset.x / widthPx).coerceIn(0f, 1f),
                        (1f - offset.y / heightPx).coerceIn(0f, 1f),
                    )
                }
            }
            .pointerInput(hue, widthPx, heightPx) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onChanged(
                        (change.position.x / widthPx).coerceIn(0f, 1f),
                        (1f - change.position.y / heightPx).coerceIn(0f, 1f),
                    )
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.horizontalGradient(
                    listOf(Color.White, Color.hsv(hue, 1f, 1f))
                )
            )
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black)
                )
            )

            val x = saturation * size.width
            val y = (1f - value) * size.height
            drawCircle(Color.Black.copy(alpha = 0.55f), radius = 10.dp.toPx(), center = Offset(x, y))
            drawCircle(Color.White, radius = 8.dp.toPx(), center = Offset(x, y), style = Stroke(width = 2.dp.toPx()))
        }
    }
}

@Composable
private fun HuePicker(
    hue: Float,
    onHueChanged: (Float) -> Unit,
) {
    var widthPx by remember { mutableIntStateOf(1) }
    val colors = remember {
        listOf(
            Color.Red,
            Color.Yellow,
            Color.Green,
            Color.Cyan,
            Color.Blue,
            Color.Magenta,
            Color.Red,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(widthPx) {
                detectTapGestures { offset ->
                    onHueChanged(((offset.x / widthPx).coerceIn(0f, 1f)) * 360f)
                }
            }
            .pointerInput(widthPx) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onHueChanged(((change.position.x / widthPx).coerceIn(0f, 1f)) * 360f)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRoundRect(
                brush = Brush.horizontalGradient(colors),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx(), 7.dp.toPx()),
            )
            val x = (hue / 360f).coerceIn(0f, 1f) * size.width
            drawCircle(Color.Black.copy(alpha = 0.55f), radius = 9.dp.toPx(), center = Offset(x, size.height / 2f))
            drawCircle(Color.White, radius = 7.dp.toPx(), center = Offset(x, size.height / 2f), style = Stroke(width = 2.dp.toPx()))
        }
    }
}

private fun colorToHex(color: Color): String {
    val argb = color.toArgb()
    val r = AndroidColor.red(argb)
    val g = AndroidColor.green(argb)
    val b = AndroidColor.blue(argb)
    return "#%02X%02X%02X".format(r, g, b)
}
