package com.example.cameraapp

import androidx.camera.core.ImageCapture
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

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

/**
 * Shutter button. It squishes with a spring while pressed. While a long exposure runs, an
 * amber ring sweeps for its duration ([exposureProgress] 0..1); while the photo is being
 * processed and saved ([busy]), a short amber arc spins around the ring.
 */
@Composable
fun ShutterButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    exposureProgress: Float? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 600f),
        label = "shutterScale"
    )
    val discScale by animateFloatAsState(
        targetValue = if (pressed) 0.82f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 700f),
        label = "shutterDisc"
    )
    // Only animate the spinner while it's visible.
    var spinAngle = 0f
    if (busy && exposureProgress == null) {
        val spin = rememberInfiniteTransition(label = "shutterSpin")
        spinAngle = spin.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
            label = "shutterSpinAngle"
        ).value
    }
    Box(
        modifier = modifier
            .size(80.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .semantics { contentDescription = "Take photo" },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(80.dp)) {
            val stroke = 4.dp.toPx()
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(stroke / 2f, stroke / 2f)
            drawCircle(Color.White, radius = (size.minDimension - stroke) / 2f, style = Stroke(stroke))
            when {
                exposureProgress != null -> drawArc(
                    HudAmber, -90f, 360f * exposureProgress.coerceIn(0f, 1f), false,
                    topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round)
                )
                busy -> drawArc(
                    HudAmber, spinAngle - 90f, 90f, false,
                    topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round)
                )
            }
        }
        Box(
            Modifier
                .size(64.dp)
                .graphicsLayer {
                    scaleX = discScale
                    scaleY = discScale
                }
                .clip(CircleShape)
                .background(if (enabled || busy) Color.White else Color.Gray)
        )
    }
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

/**
 * Horizontal row of choices shown under the HUD (lenses, shutter speeds, ISO, f-stops).
 * Scrolls when it doesn't fit, starting near the selected entry.
 */
@Composable
fun PickerRow(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Stack the choices top to bottom (used beside the landscape side column). */
    vertical: Boolean = false,
) {
    val listState = remember(labels) {
        LazyListState(firstVisibleItemIndex = (selectedIndex - 2).coerceAtLeast(0))
    }
    val chip: @Composable (Int, String) -> Unit = { i, label ->
        val selected = i == selectedIndex
        Box(
            modifier = Modifier
                .height(36.dp)
                .widthIn(min = 56.dp)
                .clip(RoundedCornerShape(50))
                .background(if (selected) HudAmber.copy(alpha = 0.25f) else Color.Transparent)
                .clickable { onSelect(i) },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 10.dp),
                color = if (selected) HudAmber else Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                maxLines = 1
            )
        }
    }
    if (vertical) {
        LazyColumn(
            state = listState,
            modifier = modifier
                .clip(RoundedCornerShape(18.dp))
                .background(ControlBackground),
            contentPadding = PaddingValues(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            itemsIndexed(labels) { i, label -> chip(i, label) }
        }
    } else {
        LazyRow(
            state = listState,
            modifier = modifier
                .clip(RoundedCornerShape(50))
                .background(ControlBackground),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(labels) { i, label -> chip(i, label) }
        }
    }
}

/** Exposure compensation pill; amber when not at zero, highlighted while its picker is open. */
@Composable
fun EvButton(ev: Float, open: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val active = abs(ev) >= 0.05f
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (open) HudAmber.copy(alpha = 0.25f) else ControlBackground)
            .border(1.dp, ControlBorder.copy(alpha = 0.5f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .semantics { contentDescription = "Exposure compensation ${formatEv(ev)}" }
    ) {
        Text(
            text = "${formatEv(ev)} EV",
            color = if (active) HudAmber else Color.White,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
    }
}

/** Film recipe pill: "STD" when off, otherwise the recipe name in amber. */
@Composable
fun RecipeButton(recipe: FilmRecipe?, open: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = recipe?.label ?: "STD"
    // Narrow enough to sit beside / under the shutter: long names wrap onto a second line
    // (at the space where there is one), and the corners stay rounded either way.
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .widthIn(max = 100.dp)
            .clip(shape)
            .background(if (open) HudAmber.copy(alpha = 0.25f) else ControlBackground)
            .border(1.dp, ControlBorder.copy(alpha = 0.5f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .semantics { contentDescription = "Film recipe $label" },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (recipe != null) HudAmber else Color.White,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}
