package com.example.cameraapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PanelBackground = Color.Black.copy(alpha = 0.88f)
private val Muted = Color.White.copy(alpha = 0.55f)

/** One manual-exposure control in the PRO panel. */
class ProControl(
    val label: String,
    val value: String,
    /** Currently automatic (shows the "A" badge). */
    val auto: Boolean,
    val options: List<String>,
    val selected: Int,
    /** Why it can't be changed right now, if it can't. */
    val note: String? = null,
    val onSelect: (Int) -> Unit = {},
)

/**
 * PRO controls in the same panel style as the recipe chooser: each value with its current
 * setting and a row of choices underneath. Changes apply live; the panel stays open.
 */
@Composable
fun ProPanel(
    controls: List<ProControl>,
    onAllAuto: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.background(PanelBackground)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "PRO", color = HudAmber, fontFamily = FontFamily.Monospace,
                fontSize = 14.sp, letterSpacing = 1.sp, modifier = Modifier.weight(1f)
            )
            EditorButton("ALL AUTO", onClick = onAllAuto)
            EditorButton("✕", onClick = onClose)
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(controls, key = { it.label }) { c ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Row(
                        Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(c.label, color = Muted, fontSize = 12.sp, letterSpacing = 1.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.weight(1f))
                        if (c.auto) {
                            Box(
                                Modifier.size(16.dp).clip(CircleShape).background(Color.White),
                                contentAlignment = Alignment.Center
                            ) { Text("A", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            c.value,
                            color = if (c.auto) Color.White else HudAmber,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 18.sp
                        )
                    }
                    if (c.note != null) {
                        Text(c.note, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    } else if (c.options.isNotEmpty()) {
                        PickerRow(
                            labels = c.options,
                            selectedIndex = c.selected,
                            onSelect = c.onSelect,
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
