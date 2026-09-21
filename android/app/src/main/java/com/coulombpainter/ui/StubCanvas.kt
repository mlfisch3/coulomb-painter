package com.coulombpainter.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import com.coulombpainter.ui.theme.CpAccent
import com.coulombpainter.ui.theme.CpCanvasBg

/**
 * Placeholder canvas used while M3b is in flight. Real physics texture
 * display comes from `nativeSimFrameTextureHandle` through SurfaceControl;
 * until then this composable proves the layout is stable and the frame loop
 * is actually driving Compose recomposition.
 *
 * The pulse is intentionally slow (6 s round trip) and low-contrast; it is
 * a "the app is alive" tell, not a decorative animation.
 */
@Composable
fun StubCanvas(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "stub-canvas-pulse")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "stub-canvas-phase",
    )
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(CpCanvasBg),
    ) {
        val cx = size.width * 0.5f
        val cy = size.height * 0.4f
        val radius = (size.minDimension * 0.35f) * (0.85f + 0.15f * phase)
        val alpha = 0.10f + 0.20f * phase
        drawCircleGradient(cx, cy, radius, alpha)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCircleGradient(
    cx: Float,
    cy: Float,
    radius: Float,
    alpha: Float,
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                CpAccent.copy(alpha = alpha),
                CpAccent.copy(alpha = 0f),
            ),
            center = Offset(cx, cy),
            radius = radius,
        ),
        center = Offset(cx, cy),
        radius = radius,
    )
}
