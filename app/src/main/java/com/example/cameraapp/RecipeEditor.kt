package com.example.cameraapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs

private val EditorBackground = Color.Black.copy(alpha = 0.88f)
private val LabelColor = Color.White.copy(alpha = 0.85f)
private val MutedColor = Color.White.copy(alpha = 0.5f)

private enum class EditorSection(val title: String) {
    COLOR("COLOR BALANCE"),
    SATURATION("SATURATION"),
    MONO("MONOCHROME"),
    TONE("TONE CURVE"),
    SPLIT("SPLIT TONING"),
    HSL("COLOR BANDS (HSL)"),
    TEXTURE("TEXTURE & LENS"),
}

/**
 * Slider panel for building a recipe. Every change goes straight to [onChange], so the live
 * preview behind the panel updates while dragging. Tap any value to reset that setting.
 */
@Composable
fun RecipeEditor(
    draft: FilmRecipe,
    isBuiltIn: Boolean,
    isSaved: Boolean,
    onChange: (FilmRecipe) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(setOf(EditorSection.COLOR.name)) }
    var tintTarget by rememberSaveable { mutableIntStateOf(0) }
    var band by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier = modifier.background(EditorBackground).imePadding()) {
        // Header: name and actions.
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = draft.label,
                onValueChange = { onChange(draft.copy(label = it.uppercase(Locale.US).take(14))) },
                singleLine = true,
                textStyle = TextStyle(color = HudAmber, fontFamily = FontFamily.Monospace, fontSize = 16.sp),
                cursorBrush = SolidColor(HudAmber),
                modifier = Modifier.weight(1f)
            )
            EditorButton(if (isBuiltIn) "SAVE COPY" else "SAVE", highlighted = true, onClick = onSave)
            if (isSaved) EditorButton("DELETE", onClick = onDelete)
            EditorButton("✕", onClick = onClose)
        }
        if (isBuiltIn) {
            Text(
                "Built-in recipe: changes are saved as a new recipe.",
                color = MutedColor, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            fun section(s: EditorSection, content: @Composable () -> Unit) {
                item(key = s.name) {
                    SectionHeader(s.title, expanded = s.name in open) {
                        open = if (s.name in open) open - s.name else open + s.name
                    }
                    if (s.name in open) content()
                }
            }

            section(EditorSection.COLOR) {
                ParamSlider("Warm (red gain)", draft.redGain, 0.85f..1.15f, 1f) { onChange(draft.copy(redGain = it)) }
                ParamSlider("Cool (blue gain)", draft.blueGain, 0.85f..1.15f, 1f) { onChange(draft.copy(blueGain = it)) }
                ParamSlider("Tint (green ↔ magenta)", draft.tint, -0.1f..0.1f, 0f, 3) { onChange(draft.copy(tint = it)) }
            }
            section(EditorSection.SATURATION) {
                ParamSlider("Red saturation", draft.satR, 0f..1.6f, 1f) { onChange(draft.copy(satR = it)) }
                ParamSlider("Green saturation", draft.satG, 0f..1.6f, 1f) { onChange(draft.copy(satG = it)) }
                ParamSlider("Blue saturation", draft.satB, 0f..1.6f, 1f) { onChange(draft.copy(satB = it)) }
                ParamSlider("Color chrome", draft.colorChrome, 0f..1f, 0f) { onChange(draft.copy(colorChrome = it)) }
            }
            section(EditorSection.MONO) {
                val mono = draft.mono
                Chips(
                    options = listOf("COLOR", "MONO"),
                    selected = if (mono == null) 0 else 1,
                    onSelect = { onChange(draft.copy(mono = if (it == 1) Triple(0.30f, 0.60f, 0.10f) else null)) }
                )
                if (mono != null) {
                    ParamSlider("Red filter", mono.first, 0f..1f, 0.3f) { onChange(draft.copy(mono = mono.copy(first = it))) }
                    ParamSlider("Green filter", mono.second, 0f..1f, 0.6f) { onChange(draft.copy(mono = mono.copy(second = it))) }
                    ParamSlider("Blue filter", mono.third, 0f..1f, 0.1f) { onChange(draft.copy(mono = mono.copy(third = it))) }
                }
            }
            section(EditorSection.TONE) {
                ParamSlider("Black point (fade)", draft.blackPoint, 0f..0.1f, 0f, 3) { onChange(draft.copy(blackPoint = it)) }
                ParamSlider("Shadows", draft.shadows, -0.1f..0.08f, 0f, 3) { onChange(draft.copy(shadows = it)) }
                ParamSlider("Midtones", draft.mids, -0.08f..0.08f, 0f, 3) { onChange(draft.copy(mids = it)) }
                ParamSlider("Highlights", draft.highlights, -0.08f..0.08f, 0f, 3) { onChange(draft.copy(highlights = it)) }
                ParamSlider("White point", draft.whitePoint, 0.88f..1f, 1f, 3) { onChange(draft.copy(whitePoint = it)) }
            }
            section(EditorSection.SPLIT) {
                Chips(listOf("SHADOWS", "MIDS", "HIGHLIGHTS"), tintTarget, onSelect = { tintTarget = it })
                val tint = when (tintTarget) { 0 -> draft.shadowTint; 1 -> draft.midTint; else -> draft.highlightTint }
                fun set(t: Tint) = onChange(
                    when (tintTarget) {
                        0 -> draft.copy(shadowTint = t)
                        1 -> draft.copy(midTint = t)
                        else -> draft.copy(highlightTint = t)
                    }
                )
                ParamSlider("Red", tint.r, -0.05f..0.05f, 0f, 3) { set(tint.copy(r = it)) }
                ParamSlider("Green", tint.g, -0.05f..0.05f, 0f, 3) { set(tint.copy(g = it)) }
                ParamSlider("Blue", tint.b, -0.05f..0.05f, 0f, 3) { set(tint.copy(b = it)) }
            }
            section(EditorSection.HSL) {
                val bands = ColorBand.entries
                Chips(
                    options = bands.map { it.label },
                    selected = band,
                    onSelect = { band = it },
                    swatches = bands.map { Color(it.swatch) },
                    marked = bands.map { draft.hsl[it]?.isNeutral == false }
                )
                val b = bands[band]
                val adj = draft.hsl[b] ?: HslAdjust()
                fun set(a: HslAdjust) = onChange(draft.copy(hsl = draft.hsl + (b to a)))
                ParamSlider("Hue shift (°)", adj.hue, -30f..30f, 0f, 0) { set(adj.copy(hue = it)) }
                ParamSlider("Saturation", adj.sat, -1f..1f, 0f) { set(adj.copy(sat = it)) }
                ParamSlider("Luminance", adj.lum, -0.5f..0.5f, 0f) { set(adj.copy(lum = it)) }
            }
            section(EditorSection.TEXTURE) {
                ParamSlider("Clarity", draft.clarity, -0.5f..0.5f, 0f) { onChange(draft.copy(clarity = it)) }
                ParamSlider("Halation", draft.halation, 0f..1f, 0f) { onChange(draft.copy(halation = it)) }
                ParamSlider("Vignette", draft.vignette, 0f..1f, 0f) { onChange(draft.copy(vignette = it)) }
                ParamSlider("Grain", draft.grain, 0f..0.1f, 0f, 3) { onChange(draft.copy(grain = it)) }
                ParamSlider("Grain size", draft.grainSize, 1f..3f, 1f) { onChange(draft.copy(grainSize = it)) }
                ParamSlider("Grain color", draft.grainColor, 0f..1f, 0f) { onChange(draft.copy(grainColor = it)) }
            }
            item(key = "reset") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    EditorButton("RESET ALL", onClick = { onChange(FilmRecipe(id = draft.id, label = draft.label)) })
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, expanded: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title, color = HudAmber, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            letterSpacing = 1.sp, modifier = Modifier.weight(1f)
        )
        Text(if (expanded) "▴" else "▾", color = HudAmber, fontSize = 12.sp)
    }
}

/** Labelled slider; the value turns amber when changed, and tapping it resets to [default]. */
@Composable
private fun ParamSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    default: Float,
    decimals: Int = 2,
    onChange: (Float) -> Unit,
) {
    val changed = abs(value - default) > 0.0005f
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = LabelColor, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(
                text = String.format(Locale.US, "%+.${decimals}f", value).let { if (default != 0f) it.removePrefix("+") else it },
                color = if (changed) HudAmber else MutedColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onChange(default) }
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = HudAmber,
                activeTrackColor = HudAmber,
                inactiveTrackColor = Color.White.copy(alpha = 0.2f)
            ),
            modifier = Modifier.height(32.dp)
        )
    }
}

/** Single-choice chip row, optionally with colour swatches and "edited" dots. */
@Composable
private fun Chips(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    swatches: List<Color>? = null,
    marked: List<Boolean>? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEachIndexed { i, label ->
            val isSelected = i == selected
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) HudAmber.copy(alpha = 0.25f) else Color.Transparent)
                    .border(1.dp, if (isSelected) HudAmber else Color.White.copy(alpha = 0.3f), RoundedCornerShape(50))
                    .clickable { onSelect(i) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                swatches?.getOrNull(i)?.let {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(it))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    label,
                    color = if (isSelected) HudAmber else Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
                if (marked?.getOrNull(i) == true) {
                    Spacer(Modifier.width(4.dp))
                    Text("•", color = HudAmber, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun EditorButton(label: String, highlighted: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(start = 6.dp)
            .clip(RoundedCornerShape(50))
            .background(if (highlighted) HudAmber.copy(alpha = 0.25f) else Color.Transparent)
            .border(1.dp, if (highlighted) HudAmber else Color.White.copy(alpha = 0.4f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(label, color = if (highlighted) HudAmber else Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}
