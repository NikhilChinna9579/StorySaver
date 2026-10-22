package com.storysaver.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Accent colours used to distinguish the two sources (generic hues, not brand assets). */
object Accents {
    val WhatsApp = Color(0xFF1FA463)
    val Instagram = Color(0xFFD6336C)
    val Success = Color(0xFF1B8A4B)
}

private val Light = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2E0FF),
    onPrimaryContainer = Color(0xFF15106B),
    secondary = Color(0xFF5D5B71),
    secondaryContainer = Color(0xFFE3E0F9),
    onSecondaryContainer = Color(0xFF1A192B),
    background = Color(0xFFF8F7FC),
    surface = Color(0xFFF8F7FC),
    surfaceVariant = Color(0xFFE5E1EC),
    onSurfaceVariant = Color(0xFF47464F),
    surfaceContainerLow = Color(0xFFF2F1F8),
    surfaceContainer = Color(0xFFECEBF3),
    surfaceContainerHigh = Color(0xFFE6E5EE),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    tertiaryContainer = Color(0xFFFFE8C7),
    onTertiaryContainer = Color(0xFF3B2600),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFC2C0FF),
    onPrimary = Color(0xFF241D9E),
    primaryContainer = Color(0xFF3A32C4),
    onPrimaryContainer = Color(0xFFE2E0FF),
    secondary = Color(0xFFC6C4DD),
    secondaryContainer = Color(0xFF45455A),
    onSecondaryContainer = Color(0xFFE3E0F9),
    background = Color(0xFF13121A),
    surface = Color(0xFF13121A),
    surfaceVariant = Color(0xFF47464F),
    onSurfaceVariant = Color(0xFFC8C5D0),
    surfaceContainerLow = Color(0xFF1B1A22),
    surfaceContainer = Color(0xFF201F27),
    surfaceContainerHigh = Color(0xFF2A2931),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    tertiaryContainer = Color(0xFF5A4100),
    onTertiaryContainer = Color(0xFFFFDEA6),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
)

@Composable
fun StorySaverTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = AppShapes,
        content = content,
    )
}
