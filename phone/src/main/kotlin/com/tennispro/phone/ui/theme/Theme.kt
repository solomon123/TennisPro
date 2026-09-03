package com.tennispro.phone.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val BallYellow = Color(0xFFD8F544)
private val CourtGreen = Color(0xFF1B4332)
private val OutRed = Color(0xFFFF5252)

/**
 * Dark by default: this app is used outdoors on a bright court, where a dark UI
 * with a high-chroma accent stays readable at arm's length far better than a
 * light one, and it costs less battery over a two-hour match on OLED.
 */
private val Dark = darkColorScheme(
    primary = BallYellow,
    onPrimary = Color(0xFF12200A),
    secondary = Color(0xFF7FD1AE),
    onSecondary = Color(0xFF00382A),
    background = Color(0xFF0B0F0C),
    onBackground = Color(0xFFE6EAE4),
    surface = Color(0xFF141A15),
    onSurface = Color(0xFFE6EAE4),
    surfaceVariant = Color(0xFF212B22),
    onSurfaceVariant = Color(0xFFB9C4B7),
    error = OutRed,
    onError = Color(0xFF2B0000),
)

private val Light = lightColorScheme(
    primary = CourtGreen,
    secondary = Color(0xFF2D6A4F),
    error = OutRed,
)

/** Tabular figures matter: a score or a speed readout should not jitter as it ticks. */
private val AppTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        ),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
    )
}

val MonoStat = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 28.sp,
)

@Composable
fun TennisProTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) Dark else Light,
        typography = AppTypography,
        content = content,
    )
}
