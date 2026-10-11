package com.nascozz.dictee.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

val BrandGradient = Brush.linearGradient(listOf(Color(0xFF5B4BEA), Color(0xFFA35CFF), Color(0xFFFF7AA2)))
val Success = Color(0xFF22C55E)

private val Light = lightColorScheme(
    primary = Color(0xFF5B4BEA),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E3FF),
    onPrimaryContainer = Color(0xFF1C1170),
    secondary = Color(0xFF8E5CF5),
    secondaryContainer = Color(0xFFF0E6FF),
    onSecondaryContainer = Color(0xFF2E0F6B),
    background = Color(0xFFF7F6FB),
    onBackground = Color(0xFF16141F),
    surface = Color(0xFFF7F6FB),
    onSurface = Color(0xFF16141F),
    surfaceVariant = Color(0xFFE6E3F0),
    onSurfaceVariant = Color(0xFF5D5970),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F0F8),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEDEBF5),
    surfaceContainerHighest = Color(0xFFE6E3F0),
    outline = Color(0xFFCAC6D9),
    outlineVariant = Color(0xFFE2DFEC),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFBDB3FF),
    onPrimary = Color(0xFF24158A),
    primaryContainer = Color(0xFF3B2DBE),
    onPrimaryContainer = Color(0xFFE7E3FF),
    secondary = Color(0xFFD3B8FF),
    secondaryContainer = Color(0xFF4A2C8C),
    onSecondaryContainer = Color(0xFFF0E6FF),
    background = Color(0xFF0E0D14),
    onBackground = Color(0xFFE7E5F0),
    surface = Color(0xFF0E0D14),
    onSurface = Color(0xFFE7E5F0),
    surfaceVariant = Color(0xFF2A2836),
    onSurfaceVariant = Color(0xFFB0ACC2),
    surfaceContainerLowest = Color(0xFF0A0910),
    surfaceContainerLow = Color(0xFF15131D),
    surfaceContainer = Color(0xFF1A1823),
    surfaceContainerHigh = Color(0xFF23212E),
    surfaceContainerHighest = Color(0xFF2C2A38),
    outline = Color(0xFF4A4759),
    outlineVariant = Color(0xFF2E2C3A),
)

private val base = Typography()
private val AppTypography = base.copy(
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
)

@Composable
fun DicteeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = AppTypography,
        content = content,
    )
}
