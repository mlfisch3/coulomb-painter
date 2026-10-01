package com.coulombpainter.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.SkipNext
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
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoulombPainterApp(vm: SimViewModel) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    val stats by vm.stats.collectAsState()
    val mode by vm.mode.collectAsState()
    val brush by vm.brush.collectAsState()
    val params by vm.params.collectAsState()
    val running by vm.running.collectAsState()

    var showBrushSheet by remember { mutableStateOf(false) }
    var showNewCanvasDialog by remember { mutableStateOf(false) }
    var showResetCanvasDialog by remember { mutableStateOf(false) }
    var showClearPaintDialog by remember { mutableStateOf(false) }
    var helpTopic by remember { mutableStateOf<String?>(null) }

    val lattice by vm.lattice.collectAsState()
    val showDiagnostics by vm.showDiagnostics.collectAsState()
    val drawerIsOpen = drawerState.isOpen || drawerState.targetValue == DrawerValue.Open

    // New-canvas dialog state - preserved across the image-picker roundtrip
    // so an uploaded coverage image survives the launcher's own recomposition.
    var canvasSpec by remember {
        mutableStateOf(
            NewCanvasSpec(
                h = 512,
                w = 512,
                fillFraction = 0.35,
                chargeSign = 1,
                seed = 0L,
                preset = "blank",
            )
        )
    }
    var coverageLabel by remember { mutableStateOf<String?>(null) }

    // Captain parity gap H7: pending snapshot bytes queued while the SAF
    // CreateDocument launcher opens. Kept as a composable-scope reference
    // because the SAF result callback fires after the ViewModel's suspend
    // snapshot has returned, so the bytes need somewhere to live.
    val pendingSnapshotRef = remember { object { var bytes: ByteArray? = null } }

    // SAF: save the snapshot to a user-chosen location. Mime image/png is
    // the only one the Rust encoder produces; the suggested filename is
    // generated at launch time.
    val snapshotLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri: Uri? ->
        val bytes = pendingSnapshotRef.bytes
        pendingSnapshotRef.bytes = null
        if (uri != null && bytes != null) {
            coroutineScope.launch(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                }.onFailure { Log.w("CoulombPainter", "snapshot write failed: $it") }
            }
        }
    }

    // Captain parity gap H8: image upload. The picker returns a content URI;
    // we decode the full-resolution bitmap, downscale to the New Canvas
    // dialog's current resolution, and read luminance into a ByteArray that
    // the dialog surfaces to the Create button.
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                val decoded = decodeCoverageBytes(context, uri, canvasSpec.h, canvasSpec.w)
                if (decoded != null) {
                    canvasSpec = canvasSpec.copy(coverageBytes = decoded)
                    coverageLabel = uri.lastPathSegment ?: "image"
                } else {
                    Log.w("CoulombPainter", "image decode failed for $uri")
                }
            }
        }
    }

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
                onLoadWireMesh = {
                    vm.loadPreset("wire_mesh")
                    coroutineScope.launch { drawerState.close() }
                },
                onLoadStripes = {
                    vm.loadPreset("stripes")
                    coroutineScope.launch { drawerState.close() }
                },
                onLoadDisc = {
                    vm.loadPreset("disc")
                    coroutineScope.launch { drawerState.close() }
                },
                onSnapshot = {
                    coroutineScope.launch {
                        val bytes = vm.snapshotPng()
                        if (bytes != null && bytes.isNotEmpty()) {
                            pendingSnapshotRef.bytes = bytes
                            val name = "coulomb-painter-${System.currentTimeMillis()}.png"
                            snapshotLauncher.launch(name)
                        } else {
                            Log.w("CoulombPainter", "snapshot returned empty bytes")
                        }
                    }
                    coroutineScope.launch { drawerState.close() }
                },
                onUndoStroke = {
                    vm.undo()
                    coroutineScope.launch { drawerState.close() }
                },
                onClearPaint = {
                    // Destructive op: route through confirmation dialog per
                    // captain rule. The drawer stays open so the artist can
                    // see the Clear request in context.
                    showClearPaintDialog = true
                },
                // Close-X in the drawer header: without gestures the artist
                // previously had no way off this panel. The X sits at the
                // top-right per captain report and closes the drawer via the
                // same DrawerState the hamburger opens.
                onClose = { coroutineScope.launch { drawerState.close() } },
            )
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Column-stack so the SurfaceView only lays out between the top
            // bar and the stats + bottom bars. The scout's M6 finding was
            // that the SurfaceView previously fillMaxSize'd under the
            // chrome, swallowing touches that the chrome composables also
            // claimed. Keeping the SurfaceView in its own weighted slot
            // means Compose and the SurfaceView partition the pointer
            // stream cleanly without an explicit inset pass.
            Column(modifier = Modifier.fillMaxSize()) {
                TopBar(
                    running = running,
                    onMenu = { coroutineScope.launch { drawerState.open() } },
                    onToggleRun = { vm.setRunning(!running) },
                    onStep = { vm.step() },
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    PhysicsSurface(
                        vm = vm,
                        lattice = lattice,
                        // Firstmate bug #2: refuse touches when the drawer
                        // is open, so the tap that dismisses the drawer is
                        // not also read as a paint stroke.
                        touchEnabled = !drawerIsOpen,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
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
            initial = canvasSpec,
            coverageLabel = coverageLabel,
            onDismiss = { showNewCanvasDialog = false },
            onPickImage = { imagePickerLauncher.launch("image/*") },
            onConfirm = { spec ->
                canvasSpec = spec
                val nParticles = (spec.fillFraction * spec.h * spec.w).toInt().coerceAtLeast(0)
                val cov = spec.coverageBytes
                when {
                    cov != null && cov.size == spec.h * spec.w -> {
                        vm.rebuildWithCoverage(
                            h = spec.h,
                            w = spec.w,
                            seed = spec.seed,
                            nParticles = nParticles,
                            chargeSign = spec.chargeSign,
                            cov = cov,
                        )
                    }
                    spec.preset == "wire_mesh" -> {
                        // The wire-mesh preset still goes through the Rust
                        // preset path so coverage is procedural; recreate
                        // first to pick up the aspect/resolution change,
                        // then load the preset to overlay the rails.
                        vm.recreate(spec.h, spec.w, spec.seed, nParticles, spec.chargeSign)
                        vm.loadPreset("wire_mesh")
                    }
                    else -> {
                        vm.recreate(spec.h, spec.w, spec.seed, nParticles, spec.chargeSign)
                    }
                }
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
    if (showClearPaintDialog) {
        ClearPaintDialog(
            onCancel = { showClearPaintDialog = false },
            onConfirm = {
                vm.clearPaint()
                showClearPaintDialog = false
                coroutineScope.launch { drawerState.close() }
            },
        )
    }
    helpTopic?.let {
        HelpTooltip(topic = it, onDismiss = { helpTopic = null })
    }
}

/**
 * Decode a picked image into a raw grayscale coverage buffer of exactly
 * `h * w` bytes. The incoming bitmap is downsampled on load with
 * `inSampleSize` to keep peak memory bounded before the final `createScaledBitmap`
 * matches the target lattice. Luminance uses the Rec. 601 weights so a
 * photo's blacks (desired as line charge) and whites (desired as empty) map
 * the way an artist expects. Returns null on any failure.
 */
private suspend fun decodeCoverageBytes(
    context: android.content.Context,
    uri: Uri,
    h: Int,
    w: Int,
): ByteArray? = withContext(Dispatchers.IO) {
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val longest = maxOf(bounds.outWidth, bounds.outHeight, 1)
        val target = maxOf(h, w)
        var sample = 1
        while (longest / (sample * 2) >= target) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded: Bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: return@runCatching null
        val scaled = Bitmap.createScaledBitmap(decoded, w, h, true)
        if (scaled !== decoded) decoded.recycle()
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        scaled.recycle()
        val out = ByteArray(w * h)
        // Invert luminance so dark pixels (ink) become high coverage. A
        // photographic line drawing then maps to the "line charges here"
        // expectation without the user flipping colors first.
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val luma = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
            out[i] = (255 - luma).toByte()
        }
        out
    }.getOrNull()
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
    onStep: () -> Unit,
) {
    // Top bar honours WindowInsets.statusBars so the icons are not under
    // the notch. Canvas stays edge-to-edge behind it.
    //
    // Firstmate bug #5: no lightning-bolt icons anywhere. The former "New
    // canvas" bolt is gone (Reset canvas now lives in the hamburger menu);
    // the former "Auto temperature" bolt is a DeviceThermostat. Menu and
    // pause/step icons remain distinct.
    //
    // Captain parity gap H19: Freeze (same affordance as the previous
    // Pause/Resume toggle - paint still writes while physics ticks are
    // suppressed) plus a Step button that advances exactly one iteration
    // whether or not the annealer is frozen. The desktop reference uses
    // Freeze + Step together as the artist's "paint without physics" surface.
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
        IconButton(onClick = {
            Log.d("CoulombPainter", "freeze tapped (running=$running)")
            onToggleRun()
        }) {
            if (running) {
                Icon(Icons.Filled.Pause, contentDescription = "Freeze", tint = CpInk)
            } else {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Resume", tint = CpInk)
            }
        }
        IconButton(onClick = {
            Log.d("CoulombPainter", "step tapped")
            onStep()
        }) {
            Icon(
                Icons.Filled.SkipNext,
                contentDescription = "Step one iteration",
                tint = CpInk,
            )
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

/**
 * Captain destructive-action rule: Clear paint discards every painted
 * stroke, so the confirmation reads out what will be lost and offers an
 * explicit Cancel next to a destructively-tinted Clear button.
 */
@Composable
private fun ClearPaintDialog(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Clear painted charges?") },
        text = {
            Text(
                "Every painted stroke on this canvas will be removed. " +
                    "The mobile particles stay in place; only the paint layer is cleared. " +
                    "This cannot be undone.",
                fontSize = 13.sp,
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                Text("Clear", color = CpAccentHot)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        },
    )
}
