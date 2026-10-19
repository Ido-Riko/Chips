package com.chips.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val ChipsAccent = Color(0xFFAAA5FF)
val ChipsMint = Color(0xFF62E5C0)
val ChipsWarning = Color(0xFFFFC875)

private val ChipsColors = darkColorScheme(
    primary = ChipsAccent,
    onPrimary = Color(0xFF211855),
    secondary = ChipsMint,
    onSecondary = Color(0xFF00382B),
    tertiary = ChipsWarning,
    background = Color(0xFF0A0A0F),
    onBackground = Color(0xFFEEEEF5),
    surface = Color(0xFF13131A),
    onSurface = Color(0xFFEEEEF5),
    surfaceVariant = Color(0xFF1C1C27),
    onSurfaceVariant = Color(0xFFB5B5CA),
    outline = Color(0xFF76768C),
    error = Color(0xFFFFB4B9)
)

@Composable
fun ChipsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ChipsColors, content = content)
}
