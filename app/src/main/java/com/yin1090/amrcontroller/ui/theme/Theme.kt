package com.yin1090.amrcontroller.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkTacticalColorScheme = darkColorScheme(
    primary = TacticalBlue,
    onPrimary = TacticalTextPrimary,
    secondary = TacticalCyan,
    onSecondary = TacticalBg,
    tertiary = TacticalEmerald,
    background = TacticalBg,
    onBackground = TacticalTextPrimary,
    surface = TacticalCard,
    onSurface = TacticalTextPrimary,
    surfaceVariant = TacticalTile,
    onSurfaceVariant = TacticalTextMuted,
    error = TacticalRed,
    onError = TacticalTextPrimary,
    outline = TacticalBorder
)

@Composable
fun AmrControllerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkTacticalColorScheme,
        typography = TacticalTypography,
        content = content
    )
}
