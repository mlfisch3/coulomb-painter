package com.coulombpainter.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coulombpainter.CoulombNative
import com.coulombpainter.Mode
import com.coulombpainter.SimViewModel
import com.coulombpainter.ui.theme.CpAccent
import com.coulombpainter.ui.theme.CpAccentHot
import com.coulombpainter.ui.theme.CpDim
import com.coulombpainter.ui.theme.CpDimmer
import com.coulombpainter.ui.theme.CpInk
import com.coulombpainter.ui.theme.CpLine
import com.coulombpainter.ui.theme.CpPanel
import com.coulombpainter.ui.theme.CpPanel2
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoulombPainterApp(vm: SimViewModel) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    val stats by vm.stats.collectAsState()
    val mode by vm.mode.collectAsState()
    val brush by vm.brush.collectAsState()
    val params by vm.params.collectAsState()
    val running by vm.running.collectAsState()

    var showBrushSheet by remember { mutableStateOf(false) }
    var showNewCanvasDialog by remember { mutableStateOf(false) }
    var showResetCanvasDialog by remember { mutableStateOf(false) }
    var helpTopic by remember { mutableStateOf<String?>(null) }

    val lattice by vm.lattice.collectAsState()
    val showDiagnostics by vm.showDiagnostics.collectAsState()
    val drawerIsOpen = drawerState.isOpen || drawerState.targetValue == DrawerValue.Open

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Firstmate bug #1: edge-swipe on the canvas otherwise opens the
        // drawer and swallows any right-going paint stroke. The hamburger
        // icon is the only way in now; a right-drag on the canvas reaches
        // the paint pipeline.
        gesturesEnabled = false,
        drawerContent = {
            ParametersDrawer(
                params = params,
                onParamChange = { key, value -> vm.setParam(key, value) },
                onHelp = { helpTopic = it },
                showDiagnostics = showDiagnostics,
                onToggleDiagnostics = { vm.setShowDiagnostics(it) },
                onNewCanvas = { showNewCanvasDialog = true },
                onResetCanvas = { showResetCanvasDialog = true },
            )
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            PhysicsSurface(
                vm = vm,
                lattice = lattice,
                // Firstmate bug #2: refuse touches when the drawer is open,
                // so the tap that dismisses the drawer is not also read as
                // a paint stroke.
                touchEnabled = !drawerIsOpen,
                modifier = Modifier.fillMaxSize(),
            )

            Column(modifier = Modifier.fillMaxSize()) {
                TopBar(
                    running = running,
                    onMenu = { coroutineScope.launch { drawerState.open() } },
                    onToggleRun = { vm.setRunning(!running) },
                )
                Spacer(Modifier.weight(1f))
                StatsBar(stats = stats)
                BottomBar(
                    mode = mode,
                    brushSign = brush.sign,
                    onMode = { vm.setMode(it) },
                    onSign = { s -> vm.setBrush(brush.copy(sign = s)) },
                    onBrushMore = { showBrushSheet = true },
                )
            }

            if (showDiagnostics) {
                DiagnosticOverlay(vm = vm, onDismiss = { vm.setShowDiagnostics(false) })
            }
        }
    }

    if (showBrushSheet) {
        BrushDetailsSheet(
            brush = brush,
            onDismiss = { showBrushSheet = false },
            onChange = { vm.setBrush(it) },
            onHelp = { helpTopic = it },
            onResetDefaults = { vm.setBrush(com.coulombpainter.BrushSettings()) },
        )
    }
    if (showNewCanvasDialog) {
        NewCanvasDialog(
            onDismiss = { showNewCanvasDialog = false },
            onConfirm = {
                // Real rebuild lands with M4; for now the dialog just closes
                // and takes the drawer with it, matching the reset flow.
                showNewCanvasDialog = false
                coroutineScope.launch { drawerState.close() }
            },
        )
    }
    if (showResetCanvasDialog) {
        ResetCanvasDialog(
            onCancel = { showResetCanvasDialog = false },
            onConfirm = {
                vm.resetCanvas()
                showResetCanvasDialog = false
                coroutineScope.launch { drawerState.close() }
            },
        )
    }
    helpTopic?.let {
        HelpTooltip(topic = it, onDismiss = { helpTopic = null })
    }
}

fun Mode.next(): Mode = when (this) {
    Mode.Paint -> Mode.Heat
    Mode.Heat -> Mode.View
    Mode.View -> Mode.Paint
}

@Composable
private fun TopBar(
    running: Boolean,
    onMenu: () -> Unit,
    onToggleRun: () -> Unit,
) {
    // Top bar honours WindowInsets.statusBars so the icons are not under
    // the notch. Canvas stays edge-to-edge behind it.
    //
    // Firstmate bug #5: no lightning-bolt icons anywhere. The former "New
    // canvas" bolt is gone (Reset canvas now lives in the hamburger menu);
    // the former "Auto temperature" bolt is a DeviceThermostat. Menu and
    // pause icons remain distinct.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CpPanel.copy(alpha = 0.85f))
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMenu) {
            Icon(Icons.Filled.Menu, contentDescription = "Menu", tint = CpInk)
        }
        Text(
            text = "Untitled canvas",
            color = CpInk,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        // Log the tap so logcat proves the click reached the ViewModel.
        // `running` observed and re-rendered means the icon actually flips
        // between pause/play when tapped now that the physics surface
        // honours the running flag.
        IconButton(onClick = {
            Log.d("CoulombPainter", "play tapped (running=$running)")
            onToggleRun()
        }) {
            if (running) {
                Icon(Icons.Filled.Pause, contentDescription = "Pause", tint = CpInk)
            } else {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Resume", tint = CpInk)
            }
        }
        IconButton(onClick = { /* auto-T toggle lands with M4 */ }) {
            Icon(
                Icons.Filled.DeviceThermostat,
                contentDescription = "Auto temperature",
                tint = CpAccentHot,
            )
        }
    }
}

@Composable
private fun StatsBar(stats: CoulombNative.Stats?) {
    // Format matches the mockup: T=<K>, n=<compact>, rate <M/s>, iter <compact>.
    // A null stats block means the physics loop has not produced its first
    // sample yet; we still reserve the row height so the layout does not jump.
    val text = if (stats == null) {
        "T=- - -  n=- - -  rate - - -  iter - - -"
    } else {
        val t = stats.temperature
        val n = stats.particles
        val mps = stats.movesPerSecond
        val iter = stats.iteration
        "T=${formatK(t)}  n=${compact(n)}  rate ${compactMps(mps)}  iter ${compact(iter)}"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = CpDim,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BottomBar(
    mode: Mode,
    brushSign: Double,
    onMode: (Mode) -> Unit,
    onSign: (Double) -> Unit,
    onBrushMore: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CpPanel.copy(alpha = 0.9f))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SegmentedMode(mode = mode, onMode = onMode)
        Spacer(Modifier.weight(1f))
        BrushChip(
            sign = brushSign,
            onSign = onSign,
            onMore = onBrushMore,
        )
    }
}

@Composable
private fun SegmentedMode(mode: Mode, onMode: (Mode) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(CpPanel2)
            .padding(2.dp),
    ) {
        Mode.entries.forEach { m ->
            SegItem(
                label = when (m) {
                    Mode.Paint -> "Paint"
                    Mode.Heat -> "Heat"
                    Mode.View -> "View"
                },
                selected = m == mode,
                onClick = { onMode(m) },
            )
        }
    }
}

@Composable
private fun SegItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) CpAccent.copy(alpha = 0.20f) else Color.Transparent
    val fg = if (selected) CpAccent else CpDim
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() })
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun BrushChip(
    sign: Double,
    onSign: (Double) -> Unit,
    onMore: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(CpPanel2)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SignBadge(label = "+", active = sign >= 0.0, positive = true) { onSign(1.0) }
        SignBadge(label = "-", active = sign < 0.0, positive = false) { onSign(-1.0) }
        // The thickness pill is a visual token in the mockup; the M4 slider
        // will replace it with a real control.
        Box(
            modifier = Modifier
                .width(24.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(CpDimmer),
        )
        IconButton(onClick = onMore, modifier = Modifier.size(28.dp)) {
            Text("...", color = CpInk, fontSize = 14.sp)
        }
    }
}

@Composable
private fun SignBadge(
    label: String,
    active: Boolean,
    positive: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (active) {
        (if (positive) CpAccentHot else Color(0xFF6BA7FF)).copy(alpha = 0.20f)
    } else Color.Transparent
    val fg = if (active) {
        if (positive) CpAccentHot else Color(0xFF6BA7FF)
    } else CpDim
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------- number formatters

private fun formatK(t: Double): String {
    // Ranges the mockup uses: single K under 1000, "18 K" style with a space
    // above; kept compact so the whole stats line fits at 360 dp.
    val abs = kotlin.math.abs(t)
    return when {
        abs < 1_000.0 -> "${t.toInt()}K"
        abs < 100_000.0 -> "${"%.1f".format(t / 1_000.0)}kK"
        else -> "${"%.0f".format(t / 1_000.0)}kK"
    }
}

private fun compact(n: Long): String {
    val abs = kotlin.math.abs(n)
    return when {
        abs < 1_000 -> n.toString()
        abs < 1_000_000 -> "${"%.0f".format(n / 1_000.0)}k"
        else -> "${"%.1f".format(n / 1_000_000.0)}M"
    }
}

private fun compactMps(mps: Double): String {
    val abs = kotlin.math.abs(mps)
    return when {
        abs < 1_000.0 -> "${"%.0f".format(mps)}/s"
        abs < 1_000_000.0 -> "${"%.0f".format(mps / 1_000.0)}k/s"
        else -> "${"%.0f".format(mps / 1_000_000.0)}M/s"
    }
}

// Fallback tokens so a colour reference below stays in one place instead of
// spreading Color(0x...) constants across composables.
private val CpDivider = CpLine

@Composable
private fun ResetCanvasDialog(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Reset canvas?") },
        text = {
            Text(
                "The current painted charges and particle arrangement will be discarded. " +
                    "This cannot be undone.",
                fontSize = 13.sp,
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                Text("Reset")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        },
    )
}
