package com.example.cameraapp

import androidx.camera.core.ImageCapture
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

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

/** Shows the last photo taken; tapping opens it in the gallery. Empty frame until one exists. */
@Composable
fun ThumbnailButton(thumbnail: ImageBitmap?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .size(52.dp)
            .clip(shape)
            .background(ControlBackground)
            .border(1.5.dp, ControlBorder, shape)
            .clickable(enabled = thumbnail != null, onClick = onClick)
            .semantics { contentDescription = "Open last photo" }
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
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

/** Current zoom ratio; tap to reset to 1x. */
@Composable
fun ZoomChip(ratio: Float, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(ControlBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = String.format(Locale.US, "%.1fx", ratio),
            color = if (ratio > 1.05f) HudAmber else Color.White,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp
        )
    }
}
