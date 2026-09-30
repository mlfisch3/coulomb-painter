package com.coulombpainter.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coulombpainter.ParamsSnapshot
import com.coulombpainter.ui.theme.CpAccent
import com.coulombpainter.ui.theme.CpDim
import com.coulombpainter.ui.theme.CpDimmer
import com.coulombpainter.ui.theme.CpInk
import com.coulombpainter.ui.theme.CpLine
import com.coulombpainter.ui.theme.CpPanel
import com.coulombpainter.ui.theme.CpPanel2

/**
 * Accordion drawer that lists every desktop param group.
 *
 * M3c wires the sliders through: temperature was live in M3b, the rest
 * (interaction, short-range attraction, annealing scalars, boundary) reach
 * the physics kernel via `nativeSimSetParam` / the core's `set_param`. Rows
 * that need a full rebuild (image, resolution, fill, line-blocking) still
 * open the New Canvas dialog because the sim's shape has to change.
 */
@Composable
fun ParametersDrawer(
    params: ParamsSnapshot,
    onParamChange: (String, Double) -> Unit,
    onHelp: (String) -> Unit,
    showDiagnostics: Boolean = false,
    onToggleDiagnostics: (Boolean) -> Unit = {},
    onNewCanvas: () -> Unit = {},
    onResetCanvas: () -> Unit = {},
    onClose: () -> Unit = {},
) {
    ModalDrawerSheet(
        drawerContainerColor = CpPanel,
        drawerContentColor = CpInk,
        modifier = Modifier.width(320.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp),
        ) {
            // Captain report: on the S24 there was no way to close the drawer
            // once opened (gesturesEnabled=false at the parent, plus no visible
            // dismiss affordance). An X in the upper-right corner does the
            // Compose thing (drawerState.close via `onClose`) and takes the
            // whole drawer with it. `Icons.Filled.Close` is deliberately not
            // reused anywhere else in the app - per captain rule "no icon may
            // mean two things".
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Coulomb Painter",
                    color = CpInk,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close panel",
                        tint = CpInk,
                    )
                }
            }
            // Captain split: 'New canvas' rebuilds from scratch (opens the
            // New Canvas dialog with resolution, aspect, fill fraction);
            // 'Reset canvas' keeps the current params but re-seeds charges
            // and clears the painted layer. Distinct icons per captain: Add
            // for creating a fresh canvas, Refresh for reseeding.
            MenuRow(
                icon = Icons.Filled.Add,
                iconDescription = "New canvas",
                label = "New canvas",
                onClick = onNewCanvas,
            )
            MenuRow(
                icon = Icons.Filled.Refresh,
                iconDescription = "Reset canvas",
                label = "Reset canvas",
                onClick = onResetCanvas,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(CpLine),
            )
            AccordionGroup(title = "Source & Lattice", initiallyOpen = true) {
                ParamRow("image", "wire_mesh", onHelp = { onHelp("source.image") })
                ParamRow("resolution", params.resolution.toString(),
                    onHelp = { onHelp("source.resolution") })
                ParamRow("line charge density", "%.2f".format(params.lineDensity),
                    onHelp = { onHelp("source.line_density") })
                ParamRow("line blocks particles", "on", onHelp = { onHelp("source.line_blocks") })
                ParamRow("line threshold", "0.50", onHelp = { onHelp("source.threshold") })
                ParamRow(
                    key = "periodic boundary",
                    value = if (params.periodic) "on" else "off",
                    onHelp = { onHelp("source.periodic") },
                    onClickValue = { onParamChange("periodic", if (params.periodic) 0.0 else 1.0) },
                )
            }
            AccordionGroup(title = "Mobile charges", initiallyOpen = false) {
                ParamRow("initial fill", "0.35", onHelp = { onHelp("mobile.fill") })
                LiveSlider(
                    label = "charge per particle",
                    value = params.charge,
                    range = 0.1f..5.0f,
                    key = "charge",
                    onHelp = { onHelp("mobile.charge") },
                    onCommit = onParamChange,
                )
            }
            AccordionGroup(title = "Interaction", initiallyOpen = false) {
                LiveSlider(
                    label = "strength",
                    value = params.strength,
                    range = 0.0f..5.0f,
                    key = "strength",
                    onHelp = { onHelp("interaction.strength") },
                    onCommit = onParamChange,
                )
                LiveSlider(
                    label = "screening length",
                    value = params.screening,
                    range = 0.0f..32.0f,
                    key = "screening",
                    onHelp = { onHelp("interaction.screening") },
                    onCommit = onParamChange,
                )
                LiveSlider(
                    label = "cutoff",
                    value = params.cutoff,
                    range = 1.0f..24.0f,
                    key = "cutoff",
                    onHelp = { onHelp("interaction.cutoff") },
                    onCommit = onParamChange,
                )
            }
            AccordionGroup(title = "Short-range attraction", initiallyOpen = false) {
                LiveSlider(
                    label = "well depth",
                    value = params.attractDepth,
                    range = 0.0f..5.0f,
                    key = "attract_depth",
                    onHelp = { onHelp("attract.depth") },
                    onCommit = onParamChange,
                )
                LiveSlider(
                    label = "range",
                    value = params.attractRange,
                    range = 0.5f..8.0f,
                    key = "attract_range",
                    onHelp = { onHelp("attract.range") },
                    onCommit = onParamChange,
                )
            }
            AccordionGroup(title = "Annealing", initiallyOpen = true) {
                LiveSlider(
                    label = "temperature (K)",
                    value = params.temperature,
                    range = 100f..200_000f,
                    key = "temperature",
                    onHelp = { onHelp("anneal.temperature") },
                    onCommit = { k, v ->
                        Log.d("CoulombPainter", "param $k set to $v")
                        onParamChange(k, v)
                    },
                )
                // Cooling active + schedule + rate are decorative for now
                // (the core has no cooling schedule of its own - temperature
                // moves only when the artist sets it or auto-T probes).
                ParamRow("cooling active", if (params.coolingActive) "on" else "off",
                    onHelp = { onHelp("anneal.cooling_active") })
                ParamRow("schedule", params.schedule,
                    onHelp = { onHelp("anneal.schedule") })
                ParamRow("cooling rate / 1000", "%.2f".format(params.coolingRate),
                    onHelp = { onHelp("anneal.rate") })
                LiveSlider(
                    label = "batch",
                    value = params.batch.toDouble(),
                    range = 1f..256f,
                    key = "batch",
                    onHelp = { onHelp("anneal.batch") },
                    onCommit = onParamChange,
                )
                LiveSlider(
                    label = "batch_min",
                    value = params.batchMin.toDouble(),
                    range = 1f..64f,
                    key = "batch_min",
                    onHelp = { onHelp("anneal.batch_min") },
                    onCommit = onParamChange,
                )
                LiveSlider(
                    label = "batch_decrement",
                    value = params.batchDecrement.toDouble(),
                    range = 1f..32f,
                    key = "batch_decrement",
                    onHelp = { onHelp("anneal.batch_decrement") },
                    onCommit = onParamChange,
                )
                LiveSlider(
                    label = "fail_limit",
                    value = params.failLimit.toDouble(),
                    range = 1f..64f,
                    key = "fail_limit",
                    onHelp = { onHelp("anneal.fail_limit") },
                    onCommit = onParamChange,
                )
            }
            AccordionGroup(title = "Compute", initiallyOpen = false) {
                LiveSlider(
                    label = "step size",
                    value = params.stepSize.toDouble(),
                    range = 1f..8f,
                    key = "step_size",
                    onHelp = { onHelp("compute.step_size") },
                    onCommit = onParamChange,
                )
                ParamRow("gpu backend", "wgpu (Vulkan)",
                    onHelp = { onHelp("compute.backend") })
                ParamRow(
                    key = "show diagnostics",
                    value = if (showDiagnostics) "on" else "off",
                    onHelp = { onHelp("compute.diagnostics") },
                    onClickValue = { onToggleDiagnostics(!showDiagnostics) },
                )
            }
            AccordionGroup(title = "Display", initiallyOpen = false) {
                // Firstmate bug #3: painted charges are visible by default
                // so the user sees the lines they drew, not just their
                // repulsive effect. Renderer draws painted cells in
                // teal-cyan on top of the mobile amber layer.
                ParamRow("show painted charge", "on", onHelp = { onHelp("display.painted") })
                ParamRow("lens", "off", onHelp = { onHelp("display.lens") })
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "Preset library (blank, wire mesh, stripes, disc) opens from the top menu.",
                color = CpDimmer,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun AccordionGroup(
    title: String,
    initiallyOpen: Boolean,
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(initiallyOpen) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (open) CpPanel2 else Color.Transparent)
                .clickable { open = !open }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                color = if (open) CpInk else CpDim,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(if (open) "-" else "+", color = CpAccent, fontSize = 16.sp)
        }
        if (open) {
            content()
            Spacer(Modifier.height(4.dp))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(CpLine),
        )
    }
}

/**
 * A slider row that only fires `onCommit` at drag end. Every slider in the
 * drawer uses this so the physics kernel is not rebuilt (cutoff, strength,
 * screening, attract_*) on every intermediate frame of a drag.
 *
 * `draft` follows the finger so the label updates while dragging, but the
 * physics is only touched when the user releases. Matches the desktop
 * reference's LIVE_KEYS / REBUILD_KEYS split.
 */
@Composable
private fun LiveSlider(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Float>,
    key: String,
    onHelp: () -> Unit,
    onCommit: (String, Double) -> Unit,
) {
    var draft by remember(value) { mutableStateOf(value.toFloat()) }
    // If the source of truth moves (Reset canvas, external update), sync the
    // slider so it does not lie about the current physics state.
    LaunchedEffect(value) {
        draft = value.toFloat()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 12.dp, top = 4.dp, bottom = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                label,
                color = CpDim,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (draft >= 100f) "%.0f".format(draft) else "%.2f".format(draft),
                color = CpInk,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            HelpChip(onHelp)
        }
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onCommit(key, draft.toDouble()) },
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = CpAccent,
                activeTrackColor = CpAccent.copy(alpha = 0.6f),
                inactiveTrackColor = CpDimmer,
            ),
        )
    }
}

@Composable
private fun ParamRow(
    key: String,
    value: String,
    onHelp: () -> Unit,
    onClickValue: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            key,
            color = CpDim,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            color = CpInk,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = if (onClickValue != null) Modifier.clickable(onClick = onClickValue) else Modifier,
        )
        HelpChip(onHelp)
    }
}


@Composable
private fun MenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconDescription: String,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = iconDescription, tint = CpAccent)
        Text(
            label,
            color = CpAccent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun HelpChip(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(CpPanel2, shape = androidx.compose.foundation.shape.CircleShape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text("?", color = CpAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}
