package com.coulombpainter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coulombpainter.ui.theme.CpAccent
import com.coulombpainter.ui.theme.CpDim
import com.coulombpainter.ui.theme.CpInk
import com.coulombpainter.ui.theme.CpPanel
import com.coulombpainter.ui.theme.CpPanel2

/**
 * New Canvas dialog. M3a lists the fields from mockup screen 4 with the
 * values shown; a real edit surface (dropdowns, sliders) lands with M4.
 * The Create button is wired to onConfirm so the flow closes cleanly.
 */
@Composable
fun NewCanvasDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) {
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
                Row2("preset", "blank")
                Row2("aspect", "1920 x 1080")
                Row2("resolution (long edge)", "1024")
                Row2("initial fill fraction", "0.35")
                Row2("charge sign", "mixed")
            }
        },
        containerColor = CpPanel,
        shape = RoundedCornerShape(14.dp),
    )
}

@Composable
private fun Row2(key: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, color = CpDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(
            value,
            color = CpInk,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(CpPanel2)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
