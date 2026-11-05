package com.example.cameraapp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private val Muted = Color.White.copy(alpha = 0.5f)
private val Faint = Color.White.copy(alpha = 0.25f)

/** Hue sweep shared by the wheels: red at 3 o'clock, then yellow, green, cyan, blue, magenta clockwise. */
private val HueSweep = listOf(
    Color(0xFFFF4040), Color(0xFFFFE040), Color(0xFF40FF60),
    Color(0xFF40E0FF), Color(0xFF4060FF), Color(0xFFFF40E0), Color(0xFFFF4040)
)

// ---------------------------------------------------------------------------------------------
// White balance pad
// ---------------------------------------------------------------------------------------------

private const val TEMP_MIN = 2500f
private const val TEMP_MID = 6500f
private const val TEMP_MAX = 12000f
private const val TINT_RANGE = 0.1f

private fun tempToX(t: Float): Float =
    if (t <= TEMP_MID) 0.5f * (t - TEMP_MIN) / (TEMP_MID - TEMP_MIN)
    else 0.5f + 0.5f * (t - TEMP_MID) / (TEMP_MAX - TEMP_MID)

private fun xToTemp(x: Float): Float {
    if (abs(x - 0.5f) < 0.02f) return TEMP_MID
    val t = if (x <= 0.5f) TEMP_MIN + x / 0.5f * (TEMP_MID - TEMP_MIN)
    else TEMP_MID + (x - 0.5f) / 0.5f * (TEMP_MAX - TEMP_MID)
    return ((t / 50f).roundToInt() * 50f).coerceIn(TEMP_MIN, TEMP_MAX)
}

/**
 * Two-way white balance: drag the dot left (cool) / right (warm) for temperature and up
 * (magenta) / down (green) for tint. Double-tap, or tap the readout, to go back to neutral.
 */
@Composable
internal fun WhiteBalancePad(temperature: Float, tint: Float, onChange: (Float, Float) -> Unit) {
    val latest by rememberUpdatedState(onChange)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("White balance", color = Color.White.copy(alpha = 0.85f), fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f))
            val changed = temperature != TEMP_MID || abs(tint) > 0.0005f
            Text(
                String.format(Locale.US, "%.0fK  %+.3f", temperature, tint),
                color = if (changed) HudAmber else Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { latest(TEMP_MID, 0f) }.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .widthIn(max = 320.dp)
                .fillMaxWidth()
                .aspectRatio(1.5f)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, Faint, RoundedCornerShape(10.dp))
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        fun set(p: Offset) {
                            val x = (p.x / size.width).coerceIn(0f, 1f)
                            val y = (p.y / size.height).coerceIn(0f, 1f)
                            var t = TINT_RANGE - y * 2f * TINT_RANGE
                            if (abs(t) < 0.004f) t = 0f
                            latest(xToTemp(x), (t * 1000f).roundToInt() / 1000f)
                        }
                        detectTapGestures(onDoubleTap = { latest(TEMP_MID, 0f) }, onTap = { set(it) })
                    }
                    .pointerInput(Unit) {
                        fun set(p: Offset) {
                            val x = (p.x / size.width).coerceIn(0f, 1f)
                            val y = (p.y / size.height).coerceIn(0f, 1f)
                            var t = TINT_RANGE - y * 2f * TINT_RANGE
                            if (abs(t) < 0.004f) t = 0f
                            latest(xToTemp(x), (t * 1000f).roundToInt() / 1000f)
                        }
                        detectDragGestures(onDragStart = { set(it) }) { change, _ -> change.consume(); set(change.position) }
                    }
            ) {
                drawRect(Brush.horizontalGradient(listOf(Color(0xFF3F6FC8), Color(0xFF7A7A7A), Color(0xFFD99A3A))))
                drawRect(
                    Brush.verticalGradient(
                        0f to Color(0xFFD04FC0).copy(alpha = 0.6f),
                        0.5f to Color.Transparent,
                        1f to Color(0xFF4FC060).copy(alpha = 0.6f)
                    )
                )
                val w = size.width
                val h = size.height
                for (i in 1..3) {
                    drawLine(Color.White.copy(alpha = if (i == 2) 0.35f else 0.12f), Offset(w * i / 4f, 0f), Offset(w * i / 4f, h))
                    drawLine(Color.White.copy(alpha = if (i == 2) 0.35f else 0.12f), Offset(0f, h * i / 4f), Offset(w, h * i / 4f))
                }
                val dot = Offset(tempToX(temperature) * w, (TINT_RANGE - tint) / (2f * TINT_RANGE) * h)
                drawCircle(Color.Black.copy(alpha = 0.35f), 12.dp.toPx(), dot)
                drawCircle(Color.White, 10.dp.toPx(), dot, style = Stroke(2.5.dp.toPx()))
            }
            PadLabel("MAGENTA", Modifier.align(Alignment.TopCenter))
            PadLabel("GREEN", Modifier.align(Alignment.BottomCenter))
            PadLabel("COOL", Modifier.align(Alignment.CenterStart))
            PadLabel("WARM", Modifier.align(Alignment.CenterEnd))
        }
    }
}

@Composable
private fun PadLabel(text: String, modifier: Modifier) {
    Text(text, color = Color.White.copy(alpha = 0.7f), fontFamily = FontFamily.Monospace, fontSize = 9.sp, letterSpacing = 1.sp, modifier = modifier.padding(5.dp))
}

// ---------------------------------------------------------------------------------------------
// Split-toning wheels
// ---------------------------------------------------------------------------------------------

private const val TWO_THIRDS_PI = (2.0 * Math.PI / 3.0).toFloat()

/** A tint offset as hue (radians, red = 0, green = 2π/3) and strength, around its brightness. */
private data class Polar(val mean: Float, val amount: Float, val hue: Float)

private fun Tint.toPolar(): Polar {
    val mean = (r + g + b) / 3f
    val x = r - (g + b) / 2f
    val y = sqrt(3f) / 2f * (g - b)
    return Polar(mean, hypot(x, y) / 1.5f, atan2(y, x))
}

private fun fromPolar(p: Polar, range: Float): Tint = Tint(
    (p.mean + p.amount * cos(p.hue)).coerceIn(-range, range),
    (p.mean + p.amount * cos(p.hue - TWO_THIRDS_PI)).coerceIn(-range, range),
    (p.mean + p.amount * cos(p.hue + TWO_THIRDS_PI)).coerceIn(-range, range),
)

/**
 * Shadows / mids / highlights toning as three colour wheels: drag out from the centre for
 * strength, around for colour. Double-tap a wheel (or tap its value) to clear it.
 */
@Composable
internal fun ToneWheels(
    shadows: Tint, mids: Tint, highlights: Tint, range: Float,
    onChange: (shadows: Tint, mids: Tint, highlights: Tint) -> Unit,
) {
    val latest by rememberUpdatedState(onChange)
    val s by rememberUpdatedState(shadows)
    val m by rememberUpdatedState(mids)
    val h by rememberUpdatedState(highlights)
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text("Split toning", color = Color.White.copy(alpha = 0.85f), fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ToneWheel("SHADOWS", shadows, range, Modifier.weight(1f)) { latest(it, m, h) }
            ToneWheel("MIDS", mids, range, Modifier.weight(1f)) { latest(s, it, h) }
            ToneWheel("HIGHLIGHTS", highlights, range, Modifier.weight(1f)) { latest(s, m, it) }
        }
    }
}

@Composable
private fun ToneWheel(label: String, value: Tint, range: Float, modifier: Modifier, onChange: (Tint) -> Unit) {
    val latest by rememberUpdatedState(onChange)
    val current by rememberUpdatedState(value)
    val polar = value.toPolar()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .widthIn(max = 120.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .pointerInput(range) {
                    fun set(p: Offset) {
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val radius = size.width / 2f
                        val d = p - c
                        var frac = (hypot(d.x, d.y) / radius).coerceIn(0f, 1f)
                        if (frac < 0.06f) frac = 0f
                        val amount = frac * range
                        latest(fromPolar(Polar(current.toPolar().mean, amount, atan2(d.y, d.x)), range))
                    }
                    detectTapGestures(
                        onDoubleTap = { latest(Tint(current.toPolar().mean, current.toPolar().mean, current.toPolar().mean)) },
                        onTap = { set(it) }
                    )
                }
                .pointerInput(range) {
                    fun set(p: Offset) {
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val radius = size.width / 2f
                        val d = p - c
                        var frac = (hypot(d.x, d.y) / radius).coerceIn(0f, 1f)
                        if (frac < 0.06f) frac = 0f
                        latest(fromPolar(Polar(current.toPolar().mean, frac * range, atan2(d.y, d.x)), range))
                    }
                    detectDragGestures(onDragStart = { set(it) }) { change, _ -> change.consume(); set(change.position) }
                }
        ) {
            drawHueDisc(alpha = 0.75f)
            val radius = size.minDimension / 2f
            val frac = (polar.amount / range).coerceIn(0f, 1f)
            val dot = center + Offset(cos(polar.hue), sin(polar.hue)) * (frac * radius)
            drawCircle(Color.Black.copy(alpha = 0.35f), 8.dp.toPx(), dot)
            drawCircle(Color.White, 6.5.dp.toPx(), dot, style = Stroke(2.dp.toPx()))
        }
        val pct = (polar.amount / range * 100f).roundToInt()
        Text(label, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
        Text(
            if (pct == 0) "OFF" else "$pct%",
            color = if (pct == 0) Muted else HudAmber,
            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable {
                val mean = current.toPolar().mean
                latest(Tint(mean, mean, mean))
            }.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHueDisc(alpha: Float) {
    val radius = size.minDimension / 2f
    drawCircle(Brush.sweepGradient(HueSweep, center), radius, alpha = alpha)
    drawCircle(Brush.radialGradient(listOf(Color(0xFF6E6E6E), Color(0x006E6E6E)), center, radius), radius)
    drawCircle(Faint, radius, style = Stroke(1.dp.toPx()))
    drawLine(Color.White.copy(alpha = 0.15f), Offset(center.x - 6.dp.toPx(), center.y), Offset(center.x + 6.dp.toPx(), center.y))
    drawLine(Color.White.copy(alpha = 0.15f), Offset(center.x, center.y - 6.dp.toPx()), Offset(center.x, center.y + 6.dp.toPx()))
}

// ---------------------------------------------------------------------------------------------
// Colour swatches (halation, vignette, light leak)
// ---------------------------------------------------------------------------------------------

private fun Tint.toColor(): Color = Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))

private fun Tint.toHsv(): FloatArray {
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        (r.coerceIn(0f, 1f) * 255).roundToInt(), (g.coerceIn(0f, 1f) * 255).roundToInt(), (b.coerceIn(0f, 1f) * 255).roundToInt(), hsv
    )
    return hsv
}

private fun hsvToTint(h: Float, s: Float, v: Float): Tint {
    val c = android.graphics.Color.HSVToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)))
    fun ch(x: Int) = ((x / 255f) * 100f).roundToInt() / 100f
    return Tint(ch(android.graphics.Color.red(c)), ch(android.graphics.Color.green(c)), ch(android.graphics.Color.blue(c)))
}

private fun close(a: Tint, b: Tint) = abs(a.r - b.r) < 0.02f && abs(a.g - b.g) < 0.02f && abs(a.b - b.b) < 0.02f

/**
 * Pick a colour from film-like presets, or CUSTOM for a hue / saturation wheel plus
 * brightness. Values are plain 0..1 RGB, as the recipe stores them.
 */
@Composable
internal fun ColorSwatches(
    label: String,
    value: Tint,
    presets: List<Pair<String, Tint>>,
    customOpen: Boolean,
    onCustomOpen: (Boolean) -> Unit,
    onChange: (Tint) -> Unit,
) {
    val latest by rememberUpdatedState(onChange)
    val current by rememberUpdatedState(value)
    val matched = presets.indexOfFirst { close(it.second, value) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White.copy(alpha = 0.85f), fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(
                if (matched >= 0) presets[matched].first else "CUSTOM",
                color = if (matched == 0) Muted else HudAmber, fontFamily = FontFamily.Monospace, fontSize = 11.sp
            )
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            presets.forEachIndexed { i, (_, tint) ->
                val selected = i == matched
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(tint.toColor())
                        .border(if (selected) 2.5.dp else 1.dp, if (selected) HudAmber else Faint, CircleShape)
                        .clickable { latest(tint) }
                )
            }
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Brush.sweepGradient(HueSweep))
                    .border(if (customOpen || matched < 0) 2.5.dp else 1.dp, if (customOpen || matched < 0) HudAmber else Faint, CircleShape)
                    .clickable { onCustomOpen(!customOpen) },
                contentAlignment = Alignment.Center
            ) { Text("+", color = Color.White, fontSize = 14.sp) }
        }
        if (customOpen) {
            val hsv = value.toHsv()
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Canvas(
                    Modifier
                        .size(110.dp)
                        .pointerInput(Unit) {
                            fun set(p: Offset) {
                                val c = Offset(size.width / 2f, size.height / 2f)
                                val d = p - c
                                val s = (hypot(d.x, d.y) / (size.width / 2f)).coerceIn(0f, 1f)
                                val hue = Math.toDegrees(atan2(d.y, d.x).toDouble()).toFloat()
                                val v = current.toHsv()[2].takeIf { it > 0.05f } ?: 1f
                                latest(hsvToTint(hue, s, v))
                            }
                            detectTapGestures(onTap = { set(it) })
                        }
                        .pointerInput(Unit) {
                            fun set(p: Offset) {
                                val c = Offset(size.width / 2f, size.height / 2f)
                                val d = p - c
                                val s = (hypot(d.x, d.y) / (size.width / 2f)).coerceIn(0f, 1f)
                                val hue = Math.toDegrees(atan2(d.y, d.x).toDouble()).toFloat()
                                val v = current.toHsv()[2].takeIf { it > 0.05f } ?: 1f
                                latest(hsvToTint(hue, s, v))
                            }
                            detectDragGestures(onDragStart = { set(it) }) { change, _ -> change.consume(); set(change.position) }
                        }
                ) {
                    val radius = size.minDimension / 2f
                    drawCircle(Brush.sweepGradient(HueSweep, center), radius)
                    drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent), center, radius), radius)
                    val a = Math.toRadians(hsv[0].toDouble()).toFloat()
                    val dot = center + Offset(cos(a), sin(a)) * (hsv[1] * radius)
                    drawCircle(Color.Black.copy(alpha = 0.35f), 8.dp.toPx(), dot)
                    drawCircle(Color.White, 6.5.dp.toPx(), dot, style = Stroke(2.dp.toPx()))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(8.dp)).background(value.toColor()).border(1.dp, Faint, RoundedCornerShape(8.dp)))
                    Text("Brightness", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                    androidx.compose.material3.Slider(
                        value = hsv[2],
                        onValueChange = { v -> val c = current.toHsv(); latest(hsvToTint(c[0], c[1], v)) },
                        colors = androidx.compose.material3.SliderDefaults.colors(
                            thumbColor = HudAmber, activeTrackColor = HudAmber, inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier.height(32.dp)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Channel mixer grid
// ---------------------------------------------------------------------------------------------

private class MixerCell(val get: (Mixer) -> Float, val set: (Mixer, Float) -> Mixer, val default: Float)

private val MixerCells = listOf(
    MixerCell({ it.rr }, { m, v -> m.copy(rr = v) }, 1f), MixerCell({ it.rg }, { m, v -> m.copy(rg = v) }, 0f), MixerCell({ it.rb }, { m, v -> m.copy(rb = v) }, 0f),
    MixerCell({ it.gr }, { m, v -> m.copy(gr = v) }, 0f), MixerCell({ it.gg }, { m, v -> m.copy(gg = v) }, 1f), MixerCell({ it.gb }, { m, v -> m.copy(gb = v) }, 0f),
    MixerCell({ it.br }, { m, v -> m.copy(br = v) }, 0f), MixerCell({ it.bg }, { m, v -> m.copy(bg = v) }, 0f), MixerCell({ it.bb }, { m, v -> m.copy(bb = v) }, 1f),
)

private val ChannelColors = listOf(Color(0xFFFF6B6B), Color(0xFF6BE38C), Color(0xFF6B9BFF))

/**
 * The 3×3 channel mixer as a grid: rows are the output channel, columns the input it takes
 * from. Drag a cell sideways to change it; double-tap to reset it.
 */
@Composable
internal fun MixerGrid(mixer: Mixer, onChange: (Mixer) -> Unit) {
    val latest by rememberUpdatedState(onChange)
    val current by rememberUpdatedState(mixer)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Channel mixer", color = Color.White.copy(alpha = 0.85f), fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f))
            if (mixer != Mixer()) Text(
                "RESET", color = HudAmber, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { latest(Mixer()) }.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        Text("Drag a cell sideways · double-tap to reset", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(44.dp))
            listOf("FROM R", "FROM G", "FROM B").forEachIndexed { i, t ->
                Text(t, color = ChannelColors[i], fontFamily = FontFamily.Monospace, fontSize = 9.sp, modifier = Modifier.weight(1f).padding(bottom = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        for (row in 0 until 3) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(listOf("R OUT", "G OUT", "B OUT")[row], color = ChannelColors[row], fontFamily = FontFamily.Monospace, fontSize = 9.sp, modifier = Modifier.width(44.dp))
                for (col in 0 until 3) {
                    val cell = MixerCells[row * 3 + col]
                    val v = cell.get(mixer)
                    val changed = abs(v - cell.default) > 0.005f
                    Box(
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 3.dp)
                            .height(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(ChannelColors[row].copy(alpha = (0.08f + 0.25f * abs(v).coerceAtMost(1f))))
                            .border(1.dp, if (changed) HudAmber else Faint, RoundedCornerShape(8.dp))
                            .pointerInput(cell) {
                                detectTapGestures(onDoubleTap = { latest(cell.set(current, cell.default)) })
                            }
                            .pointerInput(cell) {
                                val perUnit = 160.dp.toPx()
                                var start = 0f
                                var moved = 0f
                                detectHorizontalDragGestures(
                                    onDragStart = { start = cell.get(current); moved = 0f }
                                ) { change, dx ->
                                    change.consume()
                                    moved += dx
                                    val nv = ((start + moved / perUnit).coerceIn(-1f, 2f) * 100f).roundToInt() / 100f
                                    latest(cell.set(current, nv))
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            String.format(Locale.US, "%.2f", v),
                            color = if (changed) HudAmber else Color.White,
                            fontFamily = FontFamily.Monospace, fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

/** Colour presets for the swatch pickers. The first is each setting's default. */
internal val HalationPresets = listOf(
    "CLASSIC" to Tint(1f, 0.32f, 0.08f),
    "RED" to Tint(1f, 0.15f, 0.05f),
    "AMBER" to Tint(1f, 0.55f, 0.15f),
    "PINK" to Tint(1f, 0.35f, 0.45f),
    "WARM WHITE" to Tint(1f, 0.8f, 0.6f),
)
internal val VignettePresets = listOf(
    "BLACK" to Tint(0f, 0f, 0f),
    "BROWN" to Tint(0.35f, 0.2f, 0.1f),
    "SEPIA" to Tint(0.45f, 0.3f, 0.15f),
    "NAVY" to Tint(0.1f, 0.15f, 0.3f),
    "WHITE" to Tint(1f, 1f, 1f),
)
internal val LeakPresets = listOf(
    "ORANGE" to Tint(1f, 0.45f, 0.15f),
    "RED" to Tint(1f, 0.2f, 0.1f),
    "GOLD" to Tint(1f, 0.8f, 0.3f),
    "MAGENTA" to Tint(1f, 0.3f, 0.6f),
    "BLUE" to Tint(0.4f, 0.5f, 1f),
)
