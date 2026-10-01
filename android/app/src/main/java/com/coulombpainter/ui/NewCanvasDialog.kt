package com.coulombpainter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coulombpainter.ui.theme.CpAccent
import com.coulombpainter.ui.theme.CpAccentHot
import com.coulombpainter.ui.theme.CpDim
import com.coulombpainter.ui.theme.CpDimmer
import com.coulombpainter.ui.theme.CpInk
import com.coulombpainter.ui.theme.CpPanel
import com.coulombpainter.ui.theme.CpPanel2

/**
 * Captain-level canvas spec built up in the New Canvas dialog and handed to
 * `SimViewModel.recreate` / `rebuildWithCoverage`. `coverageBytes` is set
 * when the artist uploaded an image for the line-charge layer; null means
 * "blank canvas with the preset" below.
 */
data class NewCanvasSpec(
    val h: Int,
    val w: Int,
    val fillFraction: Double,
    val chargeSign: Int,
    val seed: Long,
    val preset: String,
    val coverageBytes: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NewCanvasSpec) return false
        if (h != other.h || w != other.w) return false
        if (fillFraction != other.fillFraction) return false
        if (chargeSign != other.chargeSign) return false
        if (seed != other.seed) return false
        if (preset != other.preset) return false
        // ByteArray identity by reference length + sampled bytes is enough
        // for the dialog's state comparison; a full contentEquals would
        // scan the whole bitmap on every recomposition.
        return (coverageBytes?.size ?: 0) == (other.coverageBytes?.size ?: 0)
    }

    override fun hashCode(): Int {
        var r = h
        r = 31 * r + w
        r = 31 * r + fillFraction.hashCode()
        r = 31 * r + chargeSign
        r = 31 * r + seed.hashCode()
        r = 31 * r + preset.hashCode()
        r = 31 * r + (coverageBytes?.size ?: 0)
        return r
    }
}

private enum class Aspect(val label: String, val ratio: Pair<Int, Int>) {
    Square("1:1 square", 1 to 1),
    Wide16x9("16:9 landscape", 16 to 9),
    Tall9x16("9:16 portrait", 9 to 16),
    Standard4x3("4:3 standard", 4 to 3),
}

/**
 * Real New Canvas dialog (parity gap H9/H8). The artist picks preset, aspect,
 * resolution, initial fill, and charge sign; Create hands a `NewCanvasSpec`
 * to the caller which pushes it into the sim via `recreate` (or
 * `rebuildWithCoverage` when an image was uploaded through `onPickImage`).
 *
 * `coverageBytes` is a readback of the most recently picked image, scaled by
 * the caller to match the currently selected resolution. It is passed in by
 * the caller so a configuration change does not drop the uploaded cov.
 */
@Composable
fun NewCanvasDialog(
    initial: NewCanvasSpec,
    coverageLabel: String?,
    onDismiss: () -> Unit,
    onPickImage: () -> Unit,
    onConfirm: (NewCanvasSpec) -> Unit,
) {
    var preset by remember { mutableStateOf(initial.preset) }
    var aspect by remember { mutableStateOf(aspectFromDims(initial.h, initial.w)) }
    var resolution by remember { mutableStateOf(longEdge(initial.h, initial.w)) }
    var fill by remember { mutableStateOf(initial.fillFraction.toFloat()) }
    var sign by remember { mutableStateOf(initial.chargeSign) }

    val (hNew, wNew) = aspectToDims(aspect, resolution)
    val particles = ((fill.toDouble() * hNew * wNew).toInt()).coerceAtLeast(0)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val spec = NewCanvasSpec(
                    h = hNew,
                    w = wNew,
                    fillFraction = fill.toDouble(),
                    chargeSign = sign,
                    seed = initial.seed,
                    preset = preset,
                    coverageBytes = initial.coverageBytes,
                )
                onConfirm(spec)
            }) {
                Text("Create", color = CpAccent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = CpDim)
            }
        },
        title = {
            Text("New canvas", color = CpInk, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column {
                SegmentRow(
                    label = "preset",
                    options = listOf("blank", "wire_mesh"),
                    selected = preset,
                    onSelect = { preset = it },
                )
                SegmentRow(
                    label = "aspect",
                    options = Aspect.entries.map { it.label },
                    selected = aspect.label,
                    onSelect = { s -> aspect = Aspect.entries.first { it.label == s } },
                )
                SegmentRow(
                    label = "resolution (long edge)",
                    options = listOf("256", "512", "1024"),
                    selected = resolution.toString(),
                    onSelect = { resolution = it.toInt() },
                )
                SliderRow(
                    label = "initial fill fraction",
                    value = fill,
                    range = 0.05f..0.60f,
                    valueText = "%.2f".format(fill),
                    onChange = { fill = it },
                )
                SegmentRow(
                    label = "charge sign",
                    options = listOf("+", "-"),
                    selected = if (sign >= 0) "+" else "-",
                    onSelect = { sign = if (it == "+") 1 else -1 },
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "image upload",
                        color = CpDim,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onPickImage) {
                        Text(
                            if (coverageLabel == null) "pick image" else "replace",
                            color = CpAccent,
                        )
                    }
                }
                if (coverageLabel != null) {
                    Text(
                        "loaded: $coverageLabel",
                        color = CpInk,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("lattice", color = CpDim, fontSize = 12.sp)
                    Text(
                        "${wNew} x ${hNew}",
                        color = CpInk,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text("particles", color = CpDim, fontSize = 12.sp)
                    Text(
                        particles.toString(),
                        color = CpInk,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        },
        containerColor = CpPanel,
        shape = RoundedCornerShape(14.dp),
    )
}

/**
 * Translate the saved spec's h/w pair back to the `Aspect` enum the dialog
 * selects between. Falls back to Square when the dims do not match any
 * preset, which lets the dialog open cleanly on a free-form session.
 */
private fun aspectFromDims(h: Int, w: Int): Aspect {
    val best = Aspect.entries.minByOrNull { a ->
        val (ah, aw) = aspectToDims(a, longEdge(h, w))
        kotlin.math.abs(ah - h) + kotlin.math.abs(aw - w)
    }
    return best ?: Aspect.Square
}

/**
 * Compute (h, w) that honour the aspect ratio with `longEdge` on the longer
 * side. Even multiples of 4 so a wgpu copy can align without a row pad.
 */
private fun aspectToDims(aspect: Aspect, longEdge: Int): Pair<Int, Int> {
    val (num, den) = aspect.ratio
    return if (num >= den) {
        val w = longEdge
        val h = ((longEdge.toLong() * den) / num).toInt().coerceAtLeast(16).roundToMultipleOf4()
        h to w.roundToMultipleOf4()
    } else {
        val h = longEdge
        val w = ((longEdge.toLong() * num) / den).toInt().coerceAtLeast(16).roundToMultipleOf4()
        h.roundToMultipleOf4() to w
    }
}

private fun Int.roundToMultipleOf4(): Int = (this / 4) * 4

private fun longEdge(h: Int, w: Int): Int = maxOf(h, w)

@Composable
private fun SegmentRow(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = CpDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(CpPanel2)
                .padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            options.forEach { opt ->
                val isSelected = opt == selected
                val bg = if (isSelected) CpAccent else androidx.compose.ui.graphics.Color.Transparent
                val fg = if (isSelected) CpInk else CpDim
                Text(
                    opt,
                    color = fg,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(bg.copy(alpha = if (isSelected) 0.25f else 0f))
                        .clickable { onSelect(opt) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(label, color = CpDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(
                valueText,
                color = CpInk,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
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
 * Kept around for the dialog unused warning-free; `CpAccentHot` provides the
 * destructive-action hint in the Clear paint dialog below.
 */
@Suppress("unused") private val DestructiveTint = CpAccentHot
