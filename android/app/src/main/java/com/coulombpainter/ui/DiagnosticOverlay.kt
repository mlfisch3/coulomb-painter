package com.coulombpainter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coulombpainter.SimViewModel
import com.coulombpainter.ui.theme.CpAccent
import com.coulombpainter.ui.theme.CpDim
import com.coulombpainter.ui.theme.CpDimmer
import com.coulombpainter.ui.theme.CpInk
import com.coulombpainter.ui.theme.CpPanel
import com.coulombpainter.ui.theme.CpPanel2

/**
 * Bottom-left diagnostic HUD toggled from Compute -> "show diagnostics".
 *
 * Contents required by M3b task #4:
 *   - current MPS (from the CPU physics tick's own moves/sec)
 *   - current FPS (measured from the render loop)
 *   - adapter identity, backend, driver, device type (from wgpu)
 *   - 10 s thermal-headroom forecast (from Android's PowerManager)
 *
 * Tap-anywhere-outside dismisses. Rendered above the canvas at ~90% alpha
 * with a rounded card behind, so the physics under it stays visible while
 * the numbers are being read.
 */
@Composable
fun DiagnosticOverlay(vm: SimViewModel, onDismiss: () -> Unit) {
    val stats by vm.stats.collectAsState()
    val fps by vm.fps.collectAsState()
    val adapter by vm.adapter.collectAsState()
    val thermal by vm.thermal.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomStart,
    ) {
        Box(
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, bottom = 96.dp)
                .widthIn(min = 260.dp, max = 340.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(CpPanel.copy(alpha = 0.92f))
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .clickable(enabled = false, onClick = {}),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Diagnostics",
                    color = CpAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    KVLabel("MPS")
                    KVValue(formatMps(stats?.movesPerSecond))
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    KVLabel("FPS")
                    KVValue("%.1f".format(fps))
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    KVLabel("iter")
                    KVValue(stats?.iteration?.toString() ?: "-")
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    KVLabel("adapter")
                    KVValue(adapter?.name ?: "unbound")
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    KVLabel("backend")
                    KVValue(adapter?.backend ?: "-")
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    KVLabel("driver")
                    KVValue(adapter?.driver?.take(48) ?: "-")
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    KVLabel("thermal(10s)")
                    KVValue(formatHeadroom(thermal.headroom))
                }
                Text(
                    "Tap outside to close. Thermal >=1.0 means throttle is imminent (M5 will act).",
                    color = CpDimmer,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.KVLabel(text: String) {
    Text(
        text,
        color = CpDim,
        fontSize = 11.sp,
        modifier = Modifier.widthIn(min = 78.dp),
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.KVValue(text: String) {
    Text(
        text,
        color = CpInk,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
    )
}

private fun formatMps(mps: Double?): String {
    if (mps == null) return "-"
    val abs = kotlin.math.abs(mps)
    return when {
        abs < 1_000.0 -> "%.0f/s".format(mps)
        abs < 1_000_000.0 -> "%.1fk/s".format(mps / 1_000.0)
        else -> "%.2fM/s".format(mps / 1_000_000.0)
    }
}

private fun formatHeadroom(h: Float): String {
    if (h.isNaN()) return "n/a (API<30)"
    return "%.2f".format(h)
}

// Kept unused for now: a colour reference for a later Compose Preview.
private val Placeholder = CpPanel2
