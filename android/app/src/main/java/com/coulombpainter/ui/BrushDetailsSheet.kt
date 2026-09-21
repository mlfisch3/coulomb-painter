package com.coulombpainter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coulombpainter.BrushSettings
import com.coulombpainter.ui.theme.CpAccent
import com.coulombpainter.ui.theme.CpDim
import com.coulombpainter.ui.theme.CpInk
import com.coulombpainter.ui.theme.CpPanel
import com.coulombpainter.ui.theme.CpPanel2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrushDetailsSheet(
    brush: BrushSettings,
    onDismiss: () -> Unit,
    onChange: (BrushSettings) -> Unit,
    onHelp: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CpPanel,
        contentColor = CpInk,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "Brush details",
                color = CpInk,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            BrushRow("magnitude", brush.magnitude, 0.0..20.0,
                onHelp = { onHelp("brush.magnitude") },
                onChange = { onChange(brush.copy(magnitude = it)) })
            BrushRow("density", brush.density, 0.0..1.0,
                onHelp = { onHelp("brush.density") },
                onChange = { onChange(brush.copy(density = it)) })
            BrushRow("thickness (px)", brush.thickness, 1.0..64.0,
                onHelp = { onHelp("brush.thickness") },
                onChange = { onChange(brush.copy(thickness = it)) })
            BrushRow("flow", brush.flow, 0.0..2.0,
                onHelp = { onHelp("brush.flow") },
                onChange = { onChange(brush.copy(flow = it)) })
            BrushRow("hardness", brush.hardness, 0.0..1.0,
                onHelp = { onHelp("brush.hardness") },
                onChange = { onChange(brush.copy(hardness = it)) })
            BrushRow("penetrability", brush.penetrability, 0.0..1.0,
                onHelp = { onHelp("brush.penetrability") },
                onChange = { onChange(brush.copy(penetrability = it)) })
            BrushRow("coupling (px)", brush.coupling, 1.0..64.0,
                onHelp = { onHelp("brush.coupling") },
                onChange = { onChange(brush.copy(coupling = it)) })
            BrushRow("budget (px)", brush.budget, 1.0..512.0,
                onHelp = { onHelp("brush.budget") },
                onChange = { onChange(brush.copy(budget = it)) })
        }
    }
}

@Composable
private fun BrushRow(
    key: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    onHelp: () -> Unit,
    onChange: (Double) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
                "%.2f".format(value),
                color = CpInk,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            Box(
                modifier = Modifier
                    .clickable(onClick = onHelp)
                    .background(CpPanel2, shape = androidx.compose.foundation.shape.CircleShape)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text("?", color = CpAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
        )
    }
}
