package com.example.cameraapp

import androidx.camera.core.ImageCapture
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ControlBackground = Color.Black.copy(alpha = 0.4f)
private val ControlBorder = Color.White.copy(alpha = 0.8f)

/** Lightning bolt (same geometry as Material's flash_on), kept local to avoid the extended icon set. */
private val FlashBolt: ImageVector = materialIcon(name = "FlashBolt") {
    materialPath {
        moveTo(7f, 2f)
        verticalLineToRelative(11f)
        horizontalLineToRelative(3f)
        verticalLineToRelative(9f)
        lineToRelative(7f, -12f)
        horizontalLineToRelative(-4f)
        lineToRelative(4f, -8f)
        close()
    }
}

enum class FlashSetting(val mode: Int, val label: String) {
    OFF(ImageCapture.FLASH_MODE_OFF, "OFF"),
    AUTO(ImageCapture.FLASH_MODE_AUTO, "AUTO"),
    ON(ImageCapture.FLASH_MODE_ON, "ON");

    fun next(): FlashSetting = entries[(ordinal + 1) % entries.size]
}

enum class CaptureFormat(val outputFormat: Int, val label: String) {
    JPEG(ImageCapture.OUTPUT_FORMAT_JPEG, "JPG"),
    RAW(ImageCapture.OUTPUT_FORMAT_RAW, "RAW"),
    RAW_JPEG(ImageCapture.OUTPUT_FORMAT_RAW_JPEG, "RAW+JPG");

    /** Next format in the cycle that [lens] supports (JPEG is always supported). */
    fun nextFor(lens: Lens): CaptureFormat {
        for (i in 1..entries.size) {
            val candidate = entries[(ordinal + i) % entries.size]
            if (candidate.outputFormat in lens.supportedFormats) return candidate
        }
        return JPEG
    }
}

@Composable
fun ShutterButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(80.dp)
            .border(4.dp, Color.White, CircleShape)
            .padding(8.dp)
            .clip(CircleShape)
            .background(if (enabled) Color.White else Color.Gray)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = "Take photo" }
    )
}

@Composable
fun SwitchCameraButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(ControlBackground)
            .border(1.5.dp, ControlBorder, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Refresh,
            contentDescription = "Switch camera",
            tint = Color.White,
            modifier = Modifier.size(28.dp)
        )
    }
}

@Composable
fun FlashButton(setting: FlashSetting, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tint = if (setting == FlashSetting.OFF) Color.White.copy(alpha = 0.6f) else HudAmber
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(ControlBackground)
            .border(1.dp, ControlBorder.copy(alpha = 0.5f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics { contentDescription = "Flash ${setting.label.lowercase()}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(FlashBolt, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(setting.label, color = tint, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

/** JPG / RAW / RAW+JPG toggle. RAW is saved as DNG. */
@Composable
fun FormatButton(format: CaptureFormat, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tint = if (format == CaptureFormat.JPEG) Color.White else HudAmber
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(ControlBackground)
            .border(1.dp, ControlBorder.copy(alpha = 0.5f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .semantics { contentDescription = "Capture format ${format.label}" }
    ) {
        Text(format.label, color = tint, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

/** Lens options (35mm-equivalent focal lengths) shown when the HUD focal length is tapped. */
@Composable
fun LensOptions(
    lenses: List<Lens>,
    selectedKey: String?,
    onSelect: (Lens) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(ControlBackground)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        lenses.forEach { lens ->
            val selected = lens.key == selectedKey
            Box(
                modifier = Modifier
                    .height(36.dp)
                    .widthIn(min = 56.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) HudAmber.copy(alpha = 0.25f) else Color.Transparent)
                    .clickable { onSelect(lens) }
                    .semantics { contentDescription = "${lens.equivFocalMm ?: "Unknown"} millimetre lens" },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = lens.equivFocalMm?.let { "$it mm" } ?: "? mm",
                    modifier = Modifier.padding(horizontal = 10.dp),
                    color = if (selected) HudAmber else Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
        }
    }
}
