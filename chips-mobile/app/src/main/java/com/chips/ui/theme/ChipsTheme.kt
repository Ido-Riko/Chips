package com.chips.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val ChipsAccent = Color(0xFFB5A7F8)
val ChipsMint = Color(0xFF9BD5BE)
val ChipsWarning = Color(0xFFE4BE89)

private val ChipsColors = darkColorScheme(
    primary = ChipsAccent,
    onPrimary = Color(0xFF211855),
    secondary = ChipsMint,
    onSecondary = Color(0xFF00382B),
    tertiary = ChipsWarning,
    background = Color(0xFF101115),
    onBackground = Color(0xFFF4F1EB),
    surface = Color(0xFF191B21),
    onSurface = Color(0xFFF4F1EB),
    surfaceVariant = Color(0xFF23252D),
    onSurfaceVariant = Color(0xFFA8A8B5),
    outline = Color(0xFF72727F),
    error = Color(0xFFFFB4B9)
)

private val ChipsTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 38.sp, lineHeight = 42.sp, letterSpacing = (-1.4).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.8).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.4).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp)
)

@Composable
fun ChipsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ChipsColors, typography = ChipsTypography, content = content)
}
