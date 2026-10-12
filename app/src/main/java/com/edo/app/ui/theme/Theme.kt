package com.edo.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Neutral, monochrome palette modelled on the ChatGPT Android app:
// white / near-black canvas, light-grey bubbles and sheets, black/white primary actions.
private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF0D0D0D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF4F4F4),
    onPrimaryContainer = Color(0xFF0D0D0D),
    secondary = Color(0xFF5D5D5D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFECECEC),
    onSecondaryContainer = Color(0xFF0D0D0D),
    tertiary = Color(0xFF10A37F), // OpenAI green accent
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD9F2EA),
    onTertiaryContainer = Color(0xFF05382B),
    background = Color.White,
    onBackground = Color(0xFF0D0D0D),
    surface = Color.White,
    onSurface = Color(0xFF0D0D0D),
    surfaceVariant = Color(0xFFF4F4F4),
    onSurfaceVariant = Color(0xFF676767),
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF9F9F9),
    surfaceContainer = Color(0xFFF4F4F4),
    surfaceContainerHigh = Color(0xFFEFEFEF),
    surfaceContainerHighest = Color(0xFFE8E8E8),
    outline = Color(0xFFB4B4B4),
    outlineVariant = Color(0xFFE5E5E5),
    error = Color(0xFFD9363E),
    onError = Color.White,
    errorContainer = Color(0xFFFCE4E5),
    onErrorContainer = Color(0xFF5C0F13),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color(0xFF0D0D0D),
    primaryContainer = Color(0xFF2F2F2F),
    onPrimaryContainer = Color(0xFFECECEC),
    secondary = Color(0xFFB4B4B4),
    onSecondary = Color(0xFF0D0D0D),
    secondaryContainer = Color(0xFF303030),
    onSecondaryContainer = Color(0xFFECECEC),
    tertiary = Color(0xFF19C37D),
    onTertiary = Color(0xFF00281A),
    tertiaryContainer = Color(0xFF0E3B2B),
    onTertiaryContainer = Color(0xFFBDEFD8),
    background = Color(0xFF212121),
    onBackground = Color(0xFFECECEC),
    surface = Color(0xFF212121),
    onSurface = Color(0xFFECECEC),
    surfaceVariant = Color(0xFF2F2F2F),
    onSurfaceVariant = Color(0xFFB4B4B4),
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color(0xFF171717),
    surfaceContainerLow = Color(0xFF1C1C1C),
    surfaceContainer = Color(0xFF2A2A2A),
    surfaceContainerHigh = Color(0xFF303030),
    surfaceContainerHighest = Color(0xFF383838),
    outline = Color(0xFF676767),
    outlineVariant = Color(0xFF3A3A3A),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3B0508),
    errorContainer = Color(0xFF4A1519),
    onErrorContainer = Color(0xFFFFD9DA),
)

private val EdoShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun EdoTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = EdoShapes,
        content = content,
    )
}
