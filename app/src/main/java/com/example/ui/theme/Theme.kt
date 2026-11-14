package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val SpatialHudColorScheme =
  darkColorScheme(
    primary = SpecularWhite,
    background = ObsidianBlack,
    surface = GlassBaseTint,
    onPrimary = ObsidianBlack,
    onBackground = SpecularWhite,
    onSurface = SpecularWhite,
  )

@Composable
fun SpatialHudTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = SpatialHudColorScheme, typography = Typography, content = content)
}
