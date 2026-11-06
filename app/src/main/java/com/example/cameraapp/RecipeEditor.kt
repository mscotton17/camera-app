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
import kotlin.math.roundToInt

private val EditorBackground = Color.Black.copy(alpha = 0.88f)
private val LabelColor = Color.White.copy(alpha = 0.85f)
private val MutedColor = Color.White.copy(alpha = 0.5f)

/**
 * Panel for building a recipe, in five tabs (COLOR, TONE, EFFECTS, FRAME, CAMERA). Each shows
 * the everyday controls; the rest of the settings sit under MORE. Every change goes straight
 * to [onChange], so the live preview behind the panel updates while dragging. Tap any value to
 * reset that setting.
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
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var moreOpen by rememberSaveable { mutableStateOf(setOf<Int>()) }
    var band by rememberSaveable { mutableIntStateOf(0) }
    var curveChannel by rememberSaveable { mutableIntStateOf(0) }
    var customColor by rememberSaveable { mutableStateOf<String?>(null) }
    val tabs = editorTabs(
        band = band, onBand = { band = it },
        curveChannel = curveChannel, onCurveChannel = { curveChannel = it },
        customColor = customColor, onCustomColor = { customColor = it },
        luts = luts, lensOptions = lensOptions, onImportLut = onImportLut, onDeleteLut = onDeleteLut,
    )
    val base = FilmRecipe(id = draft.id, label = draft.label)

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

        // Tabs: a dot marks any tab with settings changed from neutral.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            tabs.forEachIndexed { i, t ->
                val selected = i == tab
                val changed = (t.main + t.more).any { it.changed(draft, base) }
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) HudAmber.copy(alpha = 0.22f) else Color.Transparent)
                        .clickable { tab = i }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        t.title, color = if (selected) HudAmber else Color.White.copy(alpha = 0.7f),
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 1.sp
                    )
                    if (changed) {
                        Spacer(Modifier.width(4.dp))
                        Box(Modifier.size(5.dp).clip(CircleShape).background(HudAmber))
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.1f)))

        val current = tabs[tab.coerceIn(0, tabs.lastIndex)]
        val showMore = tab in moreOpen
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            item(key = "top") { Spacer(Modifier.height(6.dp)) }
            current.main.forEachIndexed { i, it -> item(key = "m$tab/$i") { it.content(draft, onChange) } }
            if (current.more.isNotEmpty()) item(key = "more$tab") {
                val moreChanged = current.more.any { it.changed(draft, base) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .clickable { moreOpen = if (showMore) moreOpen - tab else moreOpen + tab }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (showMore) "LESS" else "MORE", color = HudAmber, fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp, letterSpacing = 1.sp
                    )
                    if (moreChanged && !showMore) {
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.size(5.dp).clip(CircleShape).background(HudAmber))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(if (showMore) "▴" else "▾", color = HudAmber, fontSize = 12.sp)
                }
            }
            if (showMore) current.more.forEachIndexed { i, it -> item(key = "x$tab/$i") { it.content(draft, onChange) } }
            item(key = "reset") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    EditorButton("RESET ALL", onClick = { onChange(FilmRecipe(id = draft.id, label = draft.label)) })
                }
            }
        }
    }
}

/** One row of the editor, with how to tell it's been changed from a neutral recipe. */
private class EditorItem(
    val changed: (FilmRecipe, FilmRecipe) -> Boolean = { _, _ -> false },
    val content: @Composable (FilmRecipe, (FilmRecipe) -> Unit) -> Unit,
)

private class EditorTab(val title: String, val main: List<EditorItem>, val more: List<EditorItem>)

private val ParamsByKey: Map<String, RecipeParam> by lazy { AllRecipeParams.associateBy { it.key } }

private fun differs(p: RecipeParam, a: FilmRecipe, b: FilmRecipe): Boolean = when (p) {
    is FloatParam -> abs(p.get(a) - p.get(b)) > 0.0005f
    is ChoiceParam -> p.get(a) != p.get(b)
    is ToggleParam -> p.get(a) != p.get(b)
}

/** A plain slider / chips / toggle for the recipe setting [key]. */
private fun param(key: String): EditorItem {
    val p = ParamsByKey.getValue(key)
    return EditorItem({ a, b -> differs(p, a, b) }) { d, on -> ParamControl(p, d, on) }
}

private fun sub(title: String) = EditorItem { _, _ ->
    Text(
        title, color = HudAmber.copy(alpha = 0.8f), fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 2.dp)
    )
}

private fun note(text: String) = EditorItem { _, _ -> Note(text) }

private fun tintDiff(a: Tint, b: Tint) = abs(a.r - b.r) > 0.0005f || abs(a.g - b.g) > 0.0005f || abs(a.b - b.b) > 0.0005f

private fun swatch(
    id: String, label: String, presets: List<Pair<String, Tint>>,
    get: (FilmRecipe) -> Tint, set: (FilmRecipe, Tint) -> FilmRecipe,
    customColor: String?, onCustomColor: (String?) -> Unit,
) = EditorItem({ a, b -> tintDiff(get(a), get(b)) }) { d, on ->
    ColorSwatches(
        label, get(d), presets,
        customOpen = customColor == id,
        onCustomOpen = { onCustomColor(if (it) id else null) },
        onChange = { on(set(d, it)) }
    )
}

private val EvSteps = (-9..9).map { it / 3f }

private fun editorTabs(
    band: Int, onBand: (Int) -> Unit,
    curveChannel: Int, onCurveChannel: (Int) -> Unit,
    customColor: String?, onCustomColor: (String?) -> Unit,
    luts: List<String>, lensOptions: List<Int>,
    onImportLut: () -> Unit, onDeleteLut: (String) -> Unit,
): List<EditorTab> {
    val wb = EditorItem({ a, b -> a.temperature != b.temperature || abs(a.tint - b.tint) > 0.0005f }) { d, on ->
        WhiteBalancePad(d.temperature, d.tint) { t, tn -> on(d.copy(temperature = t, tint = tn)) }
    }
    // One saturation slider moving the red / green / blue saturations together.
    val saturation = EditorItem({ a, b -> a.satR != b.satR || a.satG != b.satG || a.satB != b.satB }) { d, on ->
        val avg = (d.satR + d.satG + d.satB) / 3f
        ParamSlider("Saturation", avg, 0f..1.6f, 1f) { v ->
            val dv = v - avg
            on(d.copy(
                satR = (d.satR + dv).coerceIn(0f, 1.6f),
                satG = (d.satG + dv).coerceIn(0f, 1.6f),
                satB = (d.satB + dv).coerceIn(0f, 1.6f),
            ))
        }
    }
    val split = EditorItem({ a, b -> tintDiff(a.shadowTint, b.shadowTint) || tintDiff(a.midTint, b.midTint) || tintDiff(a.highlightTint, b.highlightTint) }) { d, on ->
        ToneWheels(d.shadowTint, d.midTint, d.highlightTint, 0.05f) { s, m, h ->
            on(d.copy(shadowTint = s, midTint = m, highlightTint = h))
        }
    }
    val mono = EditorItem({ a, b -> a.mono != b.mono }) { d, on -> Label("Color / mono"); MonoEditor(d, on) }
    val bands = EditorItem({ a, _ -> a.hsl.values.any { !it.isNeutral } }) { d, on -> BandsEditor(d, band, onBand, on) }
    val mixer = EditorItem({ a, b -> a.mixer != b.mixer }) { d, on -> MixerGrid(d.mixer) { on(d.copy(mixer = it)) } }
    val lut = EditorItem({ a, b -> a.lutName != b.lutName || abs(a.lutStrength - b.lutStrength) > 0.0005f }) { d, on ->
        LutEditor(d, luts, on, onImportLut, onDeleteLut)
    }
    val curves = EditorItem({ a, b -> CurveChannel.entries.any { a.curve(it) != b.curve(it) } }) { d, on ->
        CurvesEditor(d, curveChannel, onCurveChannel, on)
    }
    val evWhenPicked = EditorItem({ a, b -> a.defaultEvOn != b.defaultEvOn || (a.defaultEvOn && abs(a.defaultEv - b.defaultEv) > 0.01f) }) { d, on ->
        val index = if (!d.defaultEvOn) 0 else 1 + EvSteps.indices.minByOrNull { abs(EvSteps[it] - d.defaultEv) }!!
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("EV when picked", color = LabelColor, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(
                    if (index == 0) "OFF" else formatEv(EvSteps[index - 1]),
                    color = if (index == 0) MutedColor else HudAmber, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { on(d.copy(defaultEvOn = false)) }.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
            Slider(
                value = index.toFloat(),
                onValueChange = { v ->
                    val i = v.roundToInt()
                    on(if (i == 0) d.copy(defaultEvOn = false) else d.copy(defaultEvOn = true, defaultEv = EvSteps[i - 1]))
                },
                valueRange = 0f..EvSteps.size.toFloat(),
                steps = EvSteps.size - 1,
                colors = SliderDefaults.colors(thumbColor = HudAmber, activeTrackColor = HudAmber, inactiveTrackColor = Color.White.copy(alpha = 0.2f)),
                modifier = Modifier.height(32.dp)
            )
            Text("Far left = leave EV as it is", color = MutedColor, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
    }
    val lens = EditorItem({ a, b -> a.defaultLensMm != b.defaultLensMm }) { d, on ->
        Label("Lens when picked")
        val options = listOf(0) + lensOptions
        Chips(
            options = options.map { if (it == 0) "KEEP" else "$it mm" },
            selected = options.indexOf(d.defaultLensMm).coerceAtLeast(0),
            onSelect = { on(d.copy(defaultLensMm = options[it])) }
        )
    }

    return listOf(
        EditorTab(
            "COLOR",
            main = listOf(wb, saturation, param("vibrance"), split, mono),
            more = listOf(
                sub("BALANCE"), param("redGain"), param("blueGain"), param("hueShift"),
                sub("SATURATION"), param("satR"), param("satG"), param("satB"),
                param("highlightSat"), param("shadowSat"),
                sub("FILM COLOR"), param("colorChrome"), param("colorChromeBlue"), param("bleachBypass"), param("skinProtect"),
                sub("COLOR BANDS (HSL)"), bands,
                sub("CHANNEL MIXER"), mixer,
                sub("3D LUT (.CUBE)"), lut,
            )
        ),
        EditorTab(
            "TONE",
            main = listOf(param("exposure"), param("contrast"), param("blackPoint"), param("highlights"), param("shadows")),
            more = listOf(
                param("mids"), param("whitePoint"),
                sub("ROLL-OFF"), param("toe"), param("shoulder"),
                sub("LOCAL"), param("highlightRecovery"), param("shadowRecovery"),
                sub("DYNAMIC RANGE"), param("dynamicRange"),
                note("DR200 / DR400 underexpose by 1 / 2 stops and lift the image back, protecting highlights."),
                sub("CURVES"), curves,
            )
        ),
        EditorTab(
            "EFFECTS",
            main = listOf(param("grain"), param("halation"), param("bloom"), param("vignette"), param("lightLeak"), param("dust")),
            more = listOf(
                sub("GRAIN"), param("grainSize"), param("grainRoughness"), param("grainColor"), param("grainColorBias"),
                param("grainShadows"), param("grainMids"), param("grainHighlights"),
                sub("HALATION"), param("halationThreshold"), param("halationRadius"),
                swatch("halation", "Halation color", HalationPresets, { it.halationColor }, { r, v -> r.copy(halationColor = v) }, customColor, onCustomColor),
                sub("BLOOM"), param("bloomThreshold"), param("bloomRadius"),
                sub("VIGNETTE"), param("vignetteFeather"), param("vignetteRoundness"),
                swatch("vignette", "Edge color", VignettePresets, { it.vignetteColor }, { r, v -> r.copy(vignetteColor = v) }, customColor, onCustomColor),
                sub("LIGHT LEAK"), param("lightLeakAngle"), param("lightLeakSize"),
                swatch("leak", "Leak color", LeakPresets, { it.lightLeakColor }, { r, v -> r.copy(lightLeakColor = v) }, customColor, onCustomColor),
                sub("SCRATCHES"), param("scratches"),
                sub("LENS"), param("sharpen"), param("clarity"), param("clarityRadius"), param("cornerSoftness"), param("chromaticAberration"),
            )
        ),
        EditorTab(
            "FRAME",
            main = listOf(param("aspect"), param("border"), param("dateStamp")),
            more = listOf(
                sub("BORDER"), param("borderColor"), param("borderWidth"), param("borderText"),
                note("The 35mm border is always black."),
                sub("DATE STAMP"), param("dateFormat"), param("dateCorner"), param("dateColor"), param("dateSize"),
            )
        ),
        EditorTab(
            "CAMERA",
            main = listOf(evWhenPicked, lens, param("whiteBalance")),
            more = listOf(
                note("Fixed white balance behaves like film balanced for that light: daylight film turns orange under bulbs."),
                sub("OUTPUT"), param("keepOriginal"), param("jpegQuality"),
            )
        ),
    )
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
internal fun EditorButton(label: String, highlighted: Boolean = false, onClick: () -> Unit) {
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
