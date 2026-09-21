package com.coulombpainter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Tokens mirror .lavish/android-plan.html so mockup, XML, and Compose agree.
val CpBg = Color(0xFF0D1322)
val CpPanel = Color(0xFF111729)
val CpPanel2 = Color(0xFF1A2338)
val CpLine = Color(0xFF1E2A44)
val CpInk = Color(0xFFDCE6F5)
val CpDim = Color(0xFF8496B5)
val CpDimmer = Color(0xFF5A6B8C)
val CpAccent = Color(0xFF40E0D0)
val CpAccentHot = Color(0xFFFFB46B)
val CpChargePos = Color(0xFFFFB46B)
val CpChargeNeg = Color(0xFF6BA7FF)
val CpCanvasBg = Color(0xFF0A0E18)

private val DarkPalette = darkColorScheme(
    primary = CpAccent,
    onPrimary = CpBg,
    secondary = CpAccentHot,
    background = CpBg,
    onBackground = CpInk,
    surface = CpPanel,
    onSurface = CpInk,
    surfaceVariant = CpPanel2,
    onSurfaceVariant = CpDim,
    outline = CpLine,
)

// Light palette exists so a preview or a follower-of-system-theme device
// does not fall back to the default Material palette; the mockups are dark
// so this stays close-tinted to keep components legible during previews.
private val LightPalette = lightColorScheme(
    primary = CpAccent,
    background = CpInk,
    surface = Color(0xFFF3F6FC),
    onSurface = CpBg,
)

@Composable
fun CoulombPainterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    MaterialTheme(
        colorScheme = palette,
        typography = Typography(),
        content = content,
    )
}
