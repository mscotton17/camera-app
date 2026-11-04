package com.example.cameraapp

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs

private val EditorBackground = Color.Black.copy(alpha = 0.88f)
private val LabelColor = Color.White.copy(alpha = 0.85f)
private val MutedColor = Color.White.copy(alpha = 0.5f)

/**
 * Slider panel for building a recipe. Every change goes straight to [onChange], so the live
 * preview behind the panel updates while dragging. Tap any value to reset that setting.
 */
@Composable
fun RecipeEditor(
    draft: FilmRecipe,
    isBuiltIn: Boolean,
    isSaved: Boolean,
    isFavorite: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    luts: List<String>,
    lensOptions: List<Int>,
    onChange: (FilmRecipe) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    onToggleFavorite: () -> Unit,
    onMove: (Int) -> Unit,
    onShare: () -> Unit,
    onImportLut: () -> Unit,
    onDeleteLut: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(setOf(RecipeSections.first().title)) }
    var band by rememberSaveable { mutableIntStateOf(0) }
    var curveChannel by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier = modifier.background(EditorBackground).imePadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = draft.label,
                onValueChange = { onChange(draft.copy(label = it.uppercase(Locale.US).take(16))) },
                singleLine = true,
                textStyle = TextStyle(color = HudAmber, fontFamily = FontFamily.Monospace, fontSize = 16.sp),
                cursorBrush = SolidColor(HudAmber),
                modifier = Modifier.weight(1f)
            )
            EditorButton(if (isBuiltIn) "SAVE COPY" else "SAVE", highlighted = true, onClick = onSave)
            EditorButton("✕", onClick = onClose)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EditorButton(if (isFavorite) "★ FAVORITE" else "☆ FAVORITE", highlighted = isFavorite, onClick = onToggleFavorite)
            if (isSaved && canMoveUp) EditorButton("◀ MOVE", onClick = { onMove(-1) })
            if (isSaved && canMoveDown) EditorButton("MOVE ▶", onClick = { onMove(1) })
            EditorButton("SHARE", onClick = onShare)
            if (isSaved) EditorButton("DELETE", onClick = onDelete)
        }
        if (isBuiltIn) Note("Built-in recipe: saving creates your own copy.")

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            RecipeSections.forEach { section ->
                item(key = section.title) {
                    val expanded = section.title in open
                    SectionHeader(section.title, expanded) {
                        open = if (expanded) open - section.title else open + section.title
                    }
                    if (expanded) {
                        section.note?.let { Note(it) }
                        section.params.forEach { ParamControl(it, draft, onChange) }
                        when (section.special) {
                            SpecialEditor.MONO -> MonoEditor(draft, onChange)
                            SpecialEditor.BANDS -> BandsEditor(draft, band, { band = it }, onChange)
                            SpecialEditor.CURVES -> CurvesEditor(draft, curveChannel, { curveChannel = it }, onChange)
                            SpecialEditor.LUT -> LutEditor(draft, luts, onChange, onImportLut, onDeleteLut)
                            SpecialEditor.DEFAULT_LENS -> {
                                Label("Lens when picked")
                                val options = listOf(0) + lensOptions
                                Chips(
                                    options = options.map { if (it == 0) "KEEP" else "$it mm" },
                                    selected = options.indexOf(draft.defaultLensMm).coerceAtLeast(0),
                                    onSelect = { onChange(draft.copy(defaultLensMm = options[it])) }
                                )
                            }
                            null -> Unit
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
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
private fun ParamControl(p: RecipeParam, draft: FilmRecipe, onChange: (FilmRecipe) -> Unit) {
    when (p) {
        is FloatParam -> ParamSlider(p.label, p.get(draft), p.min..p.max, p.default, p.decimals) { onChange(p.set(draft, it)) }
        is ChoiceParam -> {
            Label(p.label)
            Chips(
                options = p.options.map { it.label },
                selected = p.options.indexOf(p.get(draft)),
                onSelect = { onChange(p.set(draft, p.options[it])) }
            )
        }
        is ToggleParam -> {
            Label(p.label)
            Chips(listOf("OFF", "ON"), if (p.get(draft)) 1 else 0, onSelect = { onChange(p.set(draft, it == 1)) })
        }
    }
}

@Composable
private fun MonoEditor(draft: FilmRecipe, onChange: (FilmRecipe) -> Unit) {
    val mono = draft.mono
    Chips(
        options = listOf("COLOR", "MONO"),
        selected = if (mono == null) 0 else 1,
        onSelect = { onChange(draft.copy(mono = if (it == 1) Triple(0.30f, 0.60f, 0.10f) else null)) }
    )
    if (mono != null) {
        Note("Filter weights: red-heavy darkens skies, blue-heavy makes them moody.")
        ParamSlider("Red filter", mono.first, 0f..1f, 0.3f) { onChange(draft.copy(mono = mono.copy(first = it))) }
        ParamSlider("Green filter", mono.second, 0f..1f, 0.6f) { onChange(draft.copy(mono = mono.copy(second = it))) }
        ParamSlider("Blue filter", mono.third, 0f..1f, 0.1f) { onChange(draft.copy(mono = mono.copy(third = it))) }
    }
}

@Composable
private fun BandsEditor(draft: FilmRecipe, band: Int, onBand: (Int) -> Unit, onChange: (FilmRecipe) -> Unit) {
    val bands = ColorBand.entries
    Chips(
        options = bands.map { it.label },
        selected = band,
        onSelect = onBand,
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

@Composable
private fun CurvesEditor(draft: FilmRecipe, channel: Int, onChannel: (Int) -> Unit, onChange: (FilmRecipe) -> Unit) {
    Note("The graph shows the recipe's whole tone response. Each slider bends the curve at one input level.")
    ToneGraph(draft)
    val channels = CurveChannel.entries
    Chips(
        options = channels.map { it.label },
        selected = channel,
        onSelect = onChannel,
        marked = channels.map { ch -> draft.curve(ch).any { it != 0f } }
    )
    val ch = channels[channel]
    val pts = draft.curve(ch)
    pts.forEachIndexed { i, v ->
        val input = (i * 100f / (CURVE_POINTS - 1)).toInt()
        ParamSlider("Input $input%", v, -0.2f..0.2f, 0f, 3) { nv ->
            val updated = pts.toMutableList().also { it[i] = nv }
            onChange(draft.copy(curves = draft.curves + (ch to updated)))
        }
    }
}

/** The final per-channel tone tables (red, green, blue) drawn as curves. */
@Composable
private fun ToneGraph(draft: FilmRecipe) {
    val lut = draft.lut
    Canvas(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .height(120.dp)
            .border(1.dp, Color.White.copy(alpha = 0.2f))
    ) {
        val w = size.width
        val h = size.height
        for (i in 1..3) {
            drawLine(Color.White.copy(alpha = 0.1f), Offset(w * i / 4f, 0f), Offset(w * i / 4f, h))
            drawLine(Color.White.copy(alpha = 0.1f), Offset(0f, h * i / 4f), Offset(w, h * i / 4f))
        }
        drawLine(Color.White.copy(alpha = 0.25f), Offset(0f, h), Offset(w, 0f))
        val colors = listOf(Color(0xFFFF6B6B), Color(0xFF6BE38C), Color(0xFF6B9BFF))
        for (ch in 0 until 3) {
            val path = Path()
            for (i in 0 until 256) {
                val x = i / 255f * w
                val y = h - lut[ch * 256 + i] / 255f * h
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, colors[ch].copy(alpha = 0.85f), style = Stroke(width = 2f))
        }
    }
}

@Composable
private fun LutEditor(
    draft: FilmRecipe,
    luts: List<String>,
    onChange: (FilmRecipe) -> Unit,
    onImport: () -> Unit,
    onDelete: (String) -> Unit,
) {
    Note("Imported .cube LUTs run after the tone curve. Strength blends with the recipe underneath.")
    val options = listOf<String?>(null) + luts
    Chips(
        options = options.map { it ?: "NONE" },
        selected = options.indexOf(draft.lutName).coerceAtLeast(0),
        onSelect = { onChange(draft.copy(lutName = options[it])) }
    )
    if (draft.lutName != null && draft.lutName !in luts) Note("This recipe's LUT \"${draft.lutName}\" isn't on this phone; import it to use it.")
    Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
        EditorButton("IMPORT .CUBE", onClick = onImport)
        draft.lutName?.takeIf { it in luts }?.let { name -> EditorButton("DELETE LUT", onClick = { onDelete(name) }) }
    }
    ParamSlider("Strength", draft.lutStrength, 0f..1f, 1f) { onChange(draft.copy(lutStrength = it)) }
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

@Composable
private fun Note(text: String) {
    Text(
        text, color = MutedColor, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

@Composable
private fun Label(text: String) {
    Text(
        text, color = LabelColor, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
    )
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
            val signed = range.start < 0f
            Text(
                text = String.format(Locale.US, if (signed) "%+.${decimals}f" else "%.${decimals}f", value),
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
