package com.nascozz.dictee.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Palette « île » : noir profond, gris discrets, un seul accent rouge comme le point d'enregistrement. */
object Ink {
    val Bg = Color(0xFF000000)
    val Surface = Color(0xFF0E0E10)
    val SurfaceHigh = Color(0xFF18181B)
    val Line = Color(0xFF232327)
    val LineStrong = Color(0xFF34343A)
    val Text = Color(0xFFF5F5F7)
    val Muted = Color(0xFF8E8E93)
    val Faint = Color(0xFF5A5A60)
    val Red = Color(0xFFFF4D6D)
    val RedSoft = Color(0xFFFF8FA3)
    val RedTint = Color(0xFF2A0E15)
    val Green = Color(0xFF4ADE80)
    val Amber = Color(0xFFFFC56B)
}

private val Scheme = darkColorScheme(
    primary = Ink.Red,
    onPrimary = Color.White,
    primaryContainer = Ink.RedTint,
    onPrimaryContainer = Ink.RedSoft,
    secondary = Ink.Muted,
    onSecondary = Ink.Bg,
    secondaryContainer = Ink.SurfaceHigh,
    onSecondaryContainer = Ink.Text,
    tertiary = Ink.Green,
    background = Ink.Bg,
    onBackground = Ink.Text,
    surface = Ink.Bg,
    onSurface = Ink.Text,
    surfaceVariant = Ink.SurfaceHigh,
    onSurfaceVariant = Ink.Muted,
    surfaceContainerLowest = Ink.Bg,
    surfaceContainerLow = Ink.Surface,
    surfaceContainer = Ink.Surface,
    surfaceContainerHigh = Ink.SurfaceHigh,
    surfaceContainerHighest = Ink.SurfaceHigh,
    outline = Ink.LineStrong,
    outlineVariant = Ink.Line,
    error = Ink.Red,
)

/** Chiffres à chasse fixe, comme le chrono de l'île. */
val TabularNums = TextStyle(fontFeatureSettings = "tnum")

private val base = Typography()
private val AppTypography = base.copy(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, fontSize = 40.sp, letterSpacing = (-1).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

@Composable
fun DicteeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, content = content)
}
