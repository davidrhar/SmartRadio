package com.example.smartradio.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SmartRadioColorScheme = lightColorScheme(
    primary = TealPrimary,
    onPrimary = SurfaceLight,
    secondary = MintAccent,
    onSecondary = NavyDark,
    secondaryContainer = MintAccent.copy(alpha = 0.18f),
    onSecondaryContainer = TealPrimary,
    background = BackgroundLight,
    onBackground = TextPrimary,
    surface = SurfaceLight,
    onSurface = TextPrimary,
    surfaceVariant = BackgroundLight,
    onSurfaceVariant = TextSecondary,
    error = Color(0xFFDC3545)
)

@Composable
fun SmartRadioTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SmartRadioColorScheme, content = content)
}
