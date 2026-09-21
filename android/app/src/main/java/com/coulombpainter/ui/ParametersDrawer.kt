package com.coulombpainter.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * Accordion drawer that lists every desktop param group. M3a shows the whole
 * surface so the layout is judgeable; individual controls become live in M4
 * as the parameter routing matures. Every row has a `?` chip so the help
 * pattern is set now, not retrofitted later.
 */
@Composable
fun ParametersDrawer(
    params: ParamsSnapshot,
    onParamChange: (String, Double) -> Unit,
    onHelp: (String) -> Unit,
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
            Text(
                "Coulomb Painter",
                color = CpInk,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            AccordionGroup(title = "Source & Lattice", initiallyOpen = true) {
                ParamRow("image", "wire_mesh", onHelp = { onHelp("source.image") })
                ParamRow("resolution", params.resolution.toString(),
                    onHelp = { onHelp("source.resolution") })
                ParamRow("line charge density", "%.2f".format(params.lineDensity),
                    onHelp = { onHelp("source.line_density") })
                ParamRow("line blocks particles", "on", onHelp = { onHelp("source.line_blocks") })
                ParamRow("line threshold", "0.50", onHelp = { onHelp("source.threshold") })
                ParamRow("periodic boundary", if (params.periodic) "on" else "off",
                    onHelp = { onHelp("source.periodic") })
            }
            AccordionGroup(title = "Mobile charges", initiallyOpen = false) {
                ParamRow("initial fill", "0.35", onHelp = { onHelp("mobile.fill") })
                ParamRow("charge per particle", "%.2f".format(params.charge),
                    onHelp = { onHelp("mobile.charge") })
            }
            AccordionGroup(title = "Interaction", initiallyOpen = false) {
                ParamRow("strength", "%.2f".format(params.strength),
                    onHelp = { onHelp("interaction.strength") })
                ParamRow("screening length", "%.2f".format(params.screening),
                    onHelp = { onHelp("interaction.screening") })
                ParamRow("cutoff", "%.1f".format(params.cutoff),
                    onHelp = { onHelp("interaction.cutoff") })
            }
            AccordionGroup(title = "Short-range attraction", initiallyOpen = false) {
                ParamRow("well depth", "%.2f".format(params.attractDepth),
                    onHelp = { onHelp("attract.depth") })
                ParamRow("range", "%.2f".format(params.attractRange),
                    onHelp = { onHelp("attract.range") })
            }
            AccordionGroup(title = "Annealing", initiallyOpen = true) {
                ParamRow("temperature (K)", "%.2f".format(params.temperature),
                    onHelp = { onHelp("anneal.temperature") })
                ParamRow("cooling active", if (params.coolingActive) "on" else "off",
                    onHelp = { onHelp("anneal.cooling_active") })
                ParamRow("schedule", params.schedule,
                    onHelp = { onHelp("anneal.schedule") })
                ParamRow("cooling rate / 1000", "%.2f".format(params.coolingRate),
                    onHelp = { onHelp("anneal.rate") })
                ParamRow("batch", params.batch.toString(),
                    onHelp = { onHelp("anneal.batch") })
                ParamRow("batch_min", params.batchMin.toString(),
                    onHelp = { onHelp("anneal.batch_min") })
                ParamRow("batch_decrement", params.batchDecrement.toString(),
                    onHelp = { onHelp("anneal.batch_decrement") })
                ParamRow("fail_limit", params.failLimit.toString(),
                    onHelp = { onHelp("anneal.fail_limit") })
            }
            AccordionGroup(title = "Compute", initiallyOpen = false) {
                ParamRow("step size", params.stepSize.toString(),
                    onHelp = { onHelp("compute.step_size") })
                ParamRow("gpu backend", "wgpu (Vulkan)",
                    onHelp = { onHelp("compute.backend") })
            }
            AccordionGroup(title = "Display", initiallyOpen = false) {
                ParamRow("show painted overlay", "off", onHelp = { onHelp("display.painted") })
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
    // The onParamChange parameter is kept in the signature so M4 can wire a
    // slider row here without a call-site change. It is intentionally unused
    // in M3a; the drawer is a layout-check surface first.
    @Suppress("UNUSED_EXPRESSION")
    onParamChange
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

@Composable
private fun ParamRow(
    key: String,
    value: String,
    onHelp: () -> Unit,
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
        )
        HelpChip(onHelp)
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
