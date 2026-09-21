package com.coulombpainter.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coulombpainter.ui.theme.CpAccent
import com.coulombpainter.ui.theme.CpInk
import com.coulombpainter.ui.theme.CpPanel

/**
 * On-tap help popover. One line of text per topic. The map is deliberately
 * flat and short; a real content review (voice, wording, translation) belongs
 * with M6 (release polish). The topic keys match the strings passed from
 * ParametersDrawer and BrushDetailsSheet, so a missing topic surfaces
 * visibly rather than silently.
 */
@Composable
fun HelpTooltip(topic: String, onDismiss: () -> Unit) {
    val text = HELP_TEXT[topic] ?: "No help written yet for `$topic`."
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK", color = CpAccent) }
        },
        title = { Text(topic, color = CpInk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) },
        text = { Text(text, color = CpInk, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) },
        containerColor = CpPanel,
    )
}

// One line per topic per the plan's spec: no multi-sentence descriptions.
private val HELP_TEXT: Map<String, String> = mapOf(
    "source.image" to "Image used to place fixed line charges.",
    "source.resolution" to "Lattice grid resolution along the long edge.",
    "source.line_density" to "Charge per pixel along the drawn line.",
    "source.line_blocks" to "Line pixels block mobile particles when on.",
    "source.threshold" to "Grayscale threshold that decides which pixels count as line.",
    "source.periodic" to "Wraps the canvas edges into a torus when on.",
    "mobile.fill" to "Fraction of lattice sites initially occupied.",
    "mobile.charge" to "Charge magnitude carried by each mobile particle.",
    "interaction.strength" to "Overall Coulomb coupling scale.",
    "interaction.screening" to "Yukawa screening length (0 = pure Coulomb).",
    "interaction.cutoff" to "Radius beyond which pair terms are dropped.",
    "attract.depth" to "Well depth of the short-range mobile-mobile attraction.",
    "attract.range" to "Range of the short-range mobile-mobile attraction.",
    "anneal.temperature" to "Current annealing temperature in Kelvin.",
    "anneal.cooling_active" to "Turns automatic cooling on or off.",
    "anneal.schedule" to "Cooling schedule: geometric, linear, or hybrid.",
    "anneal.rate" to "Per-thousand-steps cooling multiplier.",
    "anneal.batch" to "Metropolis batch size (proposals per iteration).",
    "anneal.batch_min" to "Floor the adaptive batch will not go below.",
    "anneal.batch_decrement" to "How much the batch shrinks on a fail streak.",
    "anneal.fail_limit" to "Fail streak length that triggers a batch shrink.",
    "compute.step_size" to "Max cell distance a proposal can move a particle.",
    "compute.backend" to "GPU backend the physics runs on.",
    "display.painted" to "Overlay the painted charge layer on the canvas.",
    "display.lens" to "Magnifier lens follows the pointer when on.",

    "brush.magnitude" to "Charge magnitude laid down per painted cell.",
    "brush.density" to "Fraction of cells within the stroke that get charge.",
    "brush.thickness" to "Stroke half-width in lattice cells.",
    "brush.flow" to "How fast charge accumulates along a slow stroke.",
    "brush.hardness" to "How sharply the stroke edge falls off (0=soft, 1=hard).",
    "brush.penetrability" to "Coverage above which paint blocks mobile particles.",
    "brush.coupling" to "Radius over which stroke charge couples to u_edge.",
    "brush.budget" to "Max stroke length before the brush is cut off.",
)
