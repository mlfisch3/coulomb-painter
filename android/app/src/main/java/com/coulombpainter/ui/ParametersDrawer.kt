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
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Reorder
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
    onLoadWireMesh: () -> Unit = {},
    onLoadStripes: () -> Unit = {},
    onLoadDisc: () -> Unit = {},
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
            // Shared parity fixture: a wire-mesh grid the desktop Python
            // engine and the Android Rust engine both run on at matched
            // seed / cutoff / temperature. Lands via `nativeSimLoadPreset`
            // ("wire_mesh") → `Sim::new_wire_mesh` on the core side; the
            // icon is `GridOn` to avoid reusing New-canvas's `Add` or any
            // other icon in the app (captain "no icon may mean two things").
            MenuRow(
                icon = Icons.Filled.GridOn,
                iconDescription = "Load wire mesh test pattern",
                label = "Load wire mesh test pattern",
                onClick = onLoadWireMesh,
            )
            // Horizontal rails only. `Reorder` (three stacked horizontal
            // lines) reads as "stripes" at a glance and is distinct from
            // every other icon in the app per the captain's "no icon may
            // mean two things" rule.
            MenuRow(
                icon = Icons.Filled.Reorder,
                iconDescription = "Load stripes test pattern",
                label = "Load stripes test pattern",
                onClick = onLoadStripes,
            )
            // A single hollow ring: `RadioButtonUnchecked` is literally that
            // shape, and no other row uses it.
            MenuRow(
                icon = Icons.Filled.RadioButtonUnchecked,
                iconDescription = "Load disc test pattern",
                label = "Load disc test pattern",
                onClick = onLoadDisc,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(CpLine),
            )
            AccordionGroup(title = "Source & Lattice", initiallyOpen = true) {
                ParamRow("image", "wire_mesh",
                    onHelp = { onHelp("source.image") }, comingSoon = true)
                ParamRow("resolution", params.resolution.toString(),
                    onHelp = { onHelp("source.resolution") }, comingSoon = true)
                ParamRow("line charge density", "%.2f".format(params.lineDensity),
                    onHelp = { onHelp("source.line_density") }, comingSoon = true)
                ParamRow("line blocks particles", "on",
                    onHelp = { onHelp("source.line_blocks") }, comingSoon = true)
                ParamRow("line threshold", "0.50",
                    onHelp = { onHelp("source.threshold") }, comingSoon = true)
                ParamRow(
                    key = "periodic boundary",
                    value = if (params.periodic) "on" else "off",
                    onHelp = { onHelp("source.periodic") },
                    onClickValue = { onParamChange("periodic", if (params.periodic) 0.0 else 1.0) },
                )
            }
            AccordionGroup(title = "Mobile charges", initiallyOpen = false) {
                ParamRow("initial fill", "0.35",
                    onHelp = { onHelp("mobile.fill") }, comingSoon = true)
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
                // Cooling schedule is owned by the painter-core-feature-port
                // follow-up (the core has no cooling schedule yet). Tagged
                // coming-soon so the user does not expect the rows to react.
                ParamRow("cooling active", if (params.coolingActive) "on" else "off",
                    onHelp = { onHelp("anneal.cooling_active") }, comingSoon = true)
                ParamRow("schedule", params.schedule,
                    onHelp = { onHelp("anneal.schedule") }, comingSoon = true)
                ParamRow("cooling rate / 1000", "%.2f".format(params.coolingRate),
                    onHelp = { onHelp("anneal.rate") }, comingSoon = true)
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
                // Compute still runs on the CPU; the GPU backend selector is
                // owned by the painter-gpu-compute-wiring follow-up.
                ParamRow("gpu backend", "CPU",
                    onHelp = { onHelp("compute.backend") }, comingSoon = true)
                ParamRow(
                    key = "show diagnostics",
                    value = if (showDiagnostics) "on" else "off",
                    onHelp = { onHelp("compute.diagnostics") },
                    onClickValue = { onToggleDiagnostics(!showDiagnostics) },
                )
            }
            AccordionGroup(title = "Display", initiallyOpen = false) {
                // Painted-charge overlay is drawn unconditionally today; a
                // real toggle needs a renderer param plumbed through, which
                // is not in this cleanup's scope.
                ParamRow("show painted charge", "on",
                    onHelp = { onHelp("display.painted") }, comingSoon = true)
                ParamRow("lens", "off",
                    onHelp = { onHelp("display.lens") }, comingSoon = true)
            }
            Spacer(Modifier.height(24.dp))
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

/**
 * Static display row. Pass `comingSoon = true` for a row that shows a value
 * but has no live control yet: the audit called this cluster of rows out as
 * "look live but do nothing", and the fix is a visible tag that stops the
 * user from tapping expecting an action. Interactive rows (periodic boundary,
 * show diagnostics) stay full-contrast and never carry the tag.
 */
@Composable
private fun ParamRow(
    key: String,
    value: String,
    onHelp: () -> Unit,
    onClickValue: (() -> Unit)? = null,
    comingSoon: Boolean = false,
) {
    val keyColor = if (comingSoon) CpDimmer else CpDim
    val valueColor = if (comingSoon) CpDim else CpInk
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                key,
                color = keyColor,
                fontSize = 12.sp,
            )
            if (comingSoon) {
                Text(
                    "(coming soon)",
                    color = CpDimmer,
                    fontSize = 10.sp,
                )
            }
        }
        Text(
            value,
            color = valueColor,
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
