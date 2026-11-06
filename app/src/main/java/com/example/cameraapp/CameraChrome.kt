package com.example.cameraapp

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.wrapContentWidth
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SheetBackground = Color(0xF20E0E0E)
private val SegmentBackground = Color(0xFF1E1E1E)
private val Dim = Color.White.copy(alpha = 0.45f)

// ---------------------------------------------------------------------------------------------
// Settings (kept between launches)
// ---------------------------------------------------------------------------------------------

/** App preferences shown on the Settings page; saved in SharedPreferences. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("camera_settings", Context.MODE_PRIVATE)

    var hapticsOn by mutableStateOf(prefs.getBoolean("haptics", true))
        private set
    var gridOn by mutableStateOf(prefs.getBoolean("grid", false))
        private set

    fun updateHaptics(on: Boolean) {
        hapticsOn = on
        prefs.edit().putBoolean("haptics", on).apply()
    }

    fun updateGrid(on: Boolean) {
        gridOn = on
        prefs.edit().putBoolean("grid", on).apply()
    }

    /** Fixed (camera-like) tone curve instead of the phone's scene-adaptive one. On by default. */
    var naturalTone by mutableStateOf(prefs.getBoolean("naturalTone", true))
        private set
    var metering by mutableStateOf(
        MeteringMode.entries.firstOrNull { it.name == prefs.getString("metering", null) } ?: MeteringMode.CENTER
    )
        private set

    fun updateNaturalTone(on: Boolean) {
        naturalTone = on
        prefs.edit().putBoolean("naturalTone", on).apply()
    }

    fun updateMetering(mode: MeteringMode) {
        metering = mode
        prefs.edit().putString("metering", mode.name).apply()
    }
}

// ---------------------------------------------------------------------------------------------
// Top icon bar
// ---------------------------------------------------------------------------------------------

/**
 * Minimal icon bar: flash (tap to cycle), RAW badge (tap to cycle format), a timer indicator
 * when the self-timer is on, and the ☰ menu. [vertical] lays them down an edge (landscape),
 * with [reversed] flipping the order so they stay where they physically are in portrait.
 */
@Composable
fun TopIconBar(
    flash: FlashSetting,
    flashAvailable: Boolean,
    onFlash: () -> Unit,
    ev: Float,
    evAvailable: Boolean,
    onEv: (Float) -> Unit,
    /** Called on every 1/3-stop click while dragging (for a haptic tick). */
    onEvStep: () -> Unit,
    timerSeconds: Int,
    onMenu: () -> Unit,
    menuOpen: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    vertical: Boolean = false,
    reversed: Boolean = false,
) {
    val slot = if (compact) 40.dp else 48.dp
    val items = mutableListOf<@Composable () -> Unit>()
    items += {
        IconSlot("Flash ${flash.label.lowercase()}", slot, enabled = flashAvailable, onClick = onFlash) {
            val tint = when {
                !flashAvailable || flash == FlashSetting.OFF -> Dim
                else -> HudAmber
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(FlashBolt, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                if (flashAvailable && flash == FlashSetting.AUTO) {
                    Text("A", color = tint, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
    items += { EvDial(ev, evAvailable, onEv, onEvStep) }
    if (timerSeconds > 0) items += {
        IconSlot("Self-timer ${timerSeconds} seconds", slot, onClick = onMenu) {
            Text("\u23F2 ${timerSeconds}s", color = HudAmber, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }
    }
    items += {
        IconSlot("Menu", slot, onClick = onMenu) {
            Canvas(Modifier.size(22.dp)) {
                val c = if (menuOpen) HudAmber else Color.White
                val w = 2.dp.toPx()
                for (i in 0..2) {
                    val y = size.height * (0.25f + i * 0.25f)
                    drawLine(c, Offset(size.width * 0.12f, y), Offset(size.width * 0.88f, y), w, StrokeCap.Round)
                }
            }
        }
    }
    val ordered = if (reversed) items.reversed() else items
    if (vertical) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) { ordered.forEach { it() } }
    } else {
        Row(
            modifier = modifier,
            horizontalArrangement = if (compact) Arrangement.SpaceEvenly else Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) { ordered.forEach { it() } }
    }
}

/**
 * Exposure compensation as a small ruler: drag along it to scroll in 1/3-stop clicks, double-tap
 * to go back to ±0. Upright it reads left to right (drag left for brighter). Held sideways the
 * ruler stays put, so it runs up and down as you hold the phone (drag down for brighter), and
 * only its number turns to read upright. Greyed out in full manual, where EV isn't used.
 */
@Composable
private fun EvDial(ev: Float, enabled: Boolean, onEv: (Float) -> Unit, onStep: () -> Unit) {
    val latestEv by androidx.compose.runtime.rememberUpdatedState(ev)
    val latestOnEv by androidx.compose.runtime.rememberUpdatedState(onEv)
    val latestOnStep by androidx.compose.runtime.rememberUpdatedState(onStep)
    fun step(by: Int) {
        val i = EvOptions.indices.minByOrNull { kotlin.math.abs(EvOptions[it] - latestEv) } ?: return
        val next = (i + by).coerceIn(0, EvOptions.lastIndex)
        if (next != i) {
            latestOnEv(EvOptions[next])
            latestOnStep()
        }
    }
    val orientation = LocalDeviceOrientation.current
    val sideways = orientation == 90 || orientation == 270
    // Sideways, flip the ruler for the phone turned one way (90) so plus is always the same
    // direction for the person holding it, whichever way it's turned.
    val dir = if (orientation == 90) -1 else 1
    val dial: @Composable (@Composable () -> Unit) -> Unit = { c -> if (sideways) c() else RotateWithDevice { c() } }
    dial {
        Column(
            Modifier
                .width(124.dp)
                .height(46.dp)
                .clip(RoundedCornerShape(23.dp))
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(onDoubleTap = { if (latestEv != 0f) { latestOnEv(0f); latestOnStep() } })
                }
                .pointerInput(enabled, dir) {
                    if (!enabled) return@pointerInput
                    val stepPx = 14.dp.toPx()
                    var acc = 0f
                    detectHorizontalDragGestures(onDragStart = { acc = 0f }) { change, dx ->
                        change.consume()
                        acc += dx
                        while (acc <= -stepPx) { acc += stepPx; step(dir) }
                        while (acc >= stepPx) { acc -= stepPx; step(-dir) }
                    }
                }
                .semantics { contentDescription = "Exposure compensation ${formatEv(ev)}. Drag sideways to change." }
                .graphicsLayer { alpha = if (enabled) 1f else 0.35f },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val label: @Composable () -> Unit = {
                Text(
                    if (sideways) formatEv(ev) else formatEv(ev) + " EV",
                    color = if (enabled && kotlin.math.abs(ev) > 0.05f) HudAmber else Color.White,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (sideways) RotateWithDevice { label() } else label()
            Canvas(Modifier.padding(top = 3.dp).fillMaxWidth().height(12.dp)) {
                val spacing = 14.dp.toPx() / 1.6f
                val cx = size.width / 2f
                val current = EvOptions.indices.minByOrNull { kotlin.math.abs(EvOptions[it] - ev) } ?: 9
                for (i in EvOptions.indices) {
                    val x = cx + (i - current) * spacing * dir
                    if (x < 0f || x > size.width) continue
                    val whole = (i - 9) % 3 == 0
                    val fade = 1f - kotlin.math.abs(x - cx) / (size.width / 2f)
                    val h = if (whole) size.height else size.height * 0.55f
                    drawLine(
                        Color.White.copy(alpha = 0.25f + 0.5f * fade), Offset(x, size.height - h), Offset(x, size.height),
                        strokeWidth = (if (whole) 1.5f else 1f).dp.toPx()
                    )
                }
                drawLine(HudAmber, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun IconSlot(
    description: String,
    slot: androidx.compose.ui.unit.Dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(slot)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) { RotateWithDevice { content() } }
}

/**
 * How far (degrees) small controls should turn so they read upright in the hand. The screen is
 * locked to portrait; this follows how the phone is actually held.
 */
val LocalIconRotation = compositionLocalOf { 0f }

/** How the phone is held, snapped to 0 / 90 / 180 / 270 degrees clockwise. */
val LocalDeviceOrientation = compositionLocalOf { 0 }

/** Turns [content] in place to match how the phone is held (see [LocalIconRotation]). */
@Composable
fun RotateWithDevice(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val r = LocalIconRotation.current
    Box(modifier.graphicsLayer { rotationZ = r }, contentAlignment = Alignment.Center) { content() }
}

// ---------------------------------------------------------------------------------------------
// ☰ sheet
// ---------------------------------------------------------------------------------------------

class MenuTile(val symbol: String, val label: String, val onClick: () -> Unit)

/**
 * Drop-down menu: segmented rows (format, flash, timer, exposure) and action tiles. The
 * Settings tile flips the sheet to the settings page.
 */
@Composable
fun MenuSheet(
    formats: List<CaptureFormat>,
    format: CaptureFormat,
    disabledFormats: Set<CaptureFormat>,
    onFormat: (CaptureFormat) -> Unit,
    flash: FlashSetting,
    flashNote: String?,
    onFlash: (FlashSetting) -> Unit,
    timerSeconds: Int,
    onTimer: (Int) -> Unit,
    ev: Float,
    evAvailable: Boolean,
    onEv: (Float) -> Unit,
    tiles: List<MenuTile>,
    settings: AppSettings,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by remember { mutableStateOf(0) } // 0 = main, 1 = settings
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
            .background(SheetBackground)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (page == 1) {
                Text(
                    "‹ BACK", color = HudAmber, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { page = 0 }.padding(8.dp)
                )
                Text("SETTINGS", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(
                "✕", color = Color.White, fontSize = 18.sp,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onClose).padding(10.dp)
                    .semantics { contentDescription = "Close menu" }
            )
        }
        if (page == 0) {
            if (formats.size > 1) {
                Segmented("FORMAT", formats.map { it.label }, formats.indexOf(format),
                    disabled = formats.mapIndexedNotNull { i, f -> if (f in disabledFormats) i else null }.toSet()
                ) { onFormat(formats[it]) }
            }
            Segmented(
                "FLASH", FlashSetting.entries.map { it.label }, FlashSetting.entries.indexOf(flash),
                disabled = if (flashNote != null) FlashSetting.entries.indices.toSet() else emptySet(), note = flashNote
            ) { onFlash(FlashSetting.entries[it]) }
            val timers = listOf(0, 3, 10)
            Segmented("TIMER", listOf("OFF", "3 s", "10 s"), timers.indexOf(timerSeconds)) { onTimer(timers[it]) }
            if (evAvailable) {
                SectionLabel("EXPOSURE")
                PickerRow(
                    labels = EvOptions.map { formatEv(it) },
                    selectedIndex = EvOptions.indexOfFirst { kotlin.math.abs(it - ev) < 0.05f },
                    onSelect = { onEv(EvOptions[it]) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                tiles.forEach { Tile(it) }
                Tile(MenuTile("⚙", "Settings") { page = 1 })
            }
        } else {
            Segmented("SHUTTER HAPTICS", listOf("OFF", "ON"), if (settings.hapticsOn) 1 else 0) { settings.updateHaptics(it == 1) }
            Segmented("GRID LINES", listOf("OFF", "THIRDS"), if (settings.gridOn) 1 else 0) { settings.updateGrid(it == 1) }
            Segmented(
                "METERING", MeteringMode.entries.map { it.label }, MeteringMode.entries.indexOf(settings.metering),
                note = settings.metering.description
            ) { settings.updateMetering(MeteringMode.entries[it]) }
            Segmented(
                "TONE", listOf("PHONE", "NATURAL"), if (settings.naturalTone) 1 else 0,
                note = if (settings.naturalTone) "Fixed tone curve like a dedicated camera: shadows and highlights stay where the light put them"
                else "The phone's adaptive curve, which evens out every scene"
            ) { settings.updateNaturalTone(it == 1) }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Dim, fontSize = 11.sp, letterSpacing = 1.sp, fontFamily = FontFamily.Monospace)
}

@Composable
private fun Segmented(
    label: String,
    options: List<String>,
    selected: Int,
    disabled: Set<Int> = emptySet(),
    note: String? = null,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(label)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SegmentBackground)
                .padding(4.dp)
        ) {
            options.forEachIndexed { i, opt ->
                val isDisabled = i in disabled
                val isSelected = i == selected
                Box(
                    Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (isSelected && !isDisabled) Color.White.copy(alpha = 0.08f) else Color.Transparent)
                        .clickable(enabled = !isDisabled) { onSelect(i) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        opt,
                        color = when {
                            isDisabled -> Color.White.copy(alpha = 0.25f)
                            isSelected -> HudAmber
                            else -> Color.White
                        },
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
        note?.let { Text(it, color = Dim, fontSize = 11.sp) }
    }
}

@Composable
private fun Tile(tile: MenuTile) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(SegmentBackground)
                .clickable(onClick = tile.onClick)
                .semantics { contentDescription = tile.label },
            contentAlignment = Alignment.Center
        ) {
            Text(tile.symbol, color = Color.White, fontSize = 22.sp)
        }
        Text(
            tile.label, color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

/** Full-screen tap catcher behind the sheet; tapping outside closes it. */
@Composable
fun Scrim(onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    )
}

// ---------------------------------------------------------------------------------------------
// On-viewfinder controls
// ---------------------------------------------------------------------------------------------

/** One-tap lens buttons, shown on the image. */
@Composable
fun LensPills(lenses: List<Lens>, selectedKey: String?, onSelect: (Lens) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        lenses.forEach { lens ->
            val selected = lens.key == selectedKey
            val mm = lens.equivFocalMm?.toString() ?: "?"
            Box(
                Modifier
                    .size(if (selected) 44.dp else 38.dp)
                    .clip(CircleShape)
                    .background(if (selected) Color.Black.copy(alpha = 0.6f) else Color.Transparent)
                    .clickable { onSelect(lens) }
                    .semantics { contentDescription = "$mm millimetre lens" },
                contentAlignment = Alignment.Center
            ) {
                RotateWithDevice {
                    Text(
                        if (selected) "${mm}mm" else mm,
                        color = if (selected) HudAmber else Color.White,
                        fontSize = if (selected) 11.sp else 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

/** One PRO-mode exposure value. */
class ProCell(val item: HudItem, val value: String, val label: String, val auto: Boolean, val pickable: Boolean)

/**
 * PRO-mode exposure strip: value over label, with an "A" badge while automatic. Horizontal
 * along the bottom of the image in portrait, a column in the side bar in landscape.
 */
@Composable
fun ProStrip(cells: List<ProCell>, openItem: HudItem?, onClick: (HudItem) -> Unit, vertical: Boolean, modifier: Modifier = Modifier) {
    val cell: @Composable (ProCell) -> Unit = { c ->
        val open = c.item == openItem
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(min = 64.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (open) HudAmber.copy(alpha = 0.22f) else Color.Transparent)
                .clickable(enabled = c.pickable) { onClick(c.item) }
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .semantics { contentDescription = "${c.label} ${c.value}${if (c.auto) ", automatic" else ""}" }
        ) {
            Text(
                c.value,
                color = if (c.auto) Color.White else HudAmber,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                maxLines = 1
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                if (c.auto) {
                    Box(
                        Modifier.size(12.dp).clip(CircleShape).background(Color.White),
                        contentAlignment = Alignment.Center
                    ) { Text("A", color = Color.Black, fontSize = 8.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(3.dp))
                }
                Text(c.label, color = Dim, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
    if (vertical) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) { cells.forEach { cell(it) } }
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) { cells.forEach { cell(it) } }
    }
}

/** PHOTO · PRO switch. */
@Composable
fun ModeToggle(pro: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 20.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(false to "PHOTO", true to "PRO").forEach { (isPro, label) ->
            val selected = isPro == pro
            Text(
                label,
                color = if (selected) HudAmber else Color.White.copy(alpha = 0.8f),
                fontSize = if (compact) 14.sp else 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                letterSpacing = 0.5.sp,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onChange(isPro) }
                    .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = 6.dp)
            )
        }
    }
}

/** Rule-of-thirds guides over the image. */
@Composable
fun GridOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val c = Color.White.copy(alpha = 0.35f)
        val w = 1.dp.toPx()
        for (i in 1..2) {
            val x = size.width * i / 3f
            val y = size.height * i / 3f
            drawLine(c, Offset(x, 0f), Offset(x, size.height), w)
            drawLine(c, Offset(0f, y), Offset(size.width, y), w)
        }
    }
}

/** Big self-timer countdown in the middle of the image. */
@Composable
fun CountdownOverlay(seconds: Int, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        RotateWithDevice {
            Text(
                seconds.toString(),
                color = HudAmber,
                fontSize = 96.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Light
            )
        }
    }
}

/**
 * Swipeable recipe carousel (where camera apps show their modes): the current recipe in the
 * middle with its neighbours dimmed either side. Swipe, or tap a neighbour, to change recipe.
 * [vertical] stacks it top to bottom (landscape side bar) and swipes up / down.
 */
@Composable
fun RecipeCarousel(
    recipes: List<FilmRecipe>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
    /** Narrow version (landscape, beside the shutter): current name with ‹ › arrows. */
    compact: Boolean = false,
) {
    if (recipes.isEmpty()) return
    val index = recipes.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val prev = recipes.getOrNull(index - 1)
    val next = recipes.getOrNull(index + 1)
    val current = recipes[index]
    // Read inside the gesture so it always sees the latest neighbours.
    val latestPrev = androidx.compose.runtime.rememberUpdatedState(prev)
    val latestNext = androidx.compose.runtime.rememberUpdatedState(next)
    val swipe = Modifier.pointerInput(vertical) {
        var total = 0f
        val threshold = 40.dp.toPx()
        val onEnd = {
            when {
                total < -threshold -> latestNext.value?.let { onSelect(it.id) }
                total > threshold -> latestPrev.value?.let { onSelect(it.id) }
            }
            total = 0f
        }
        if (vertical) {
            detectVerticalDragGestures(onDragEnd = onEnd, onDragCancel = { total = 0f }) { _, d -> total += d }
        } else {
            detectHorizontalDragGestures(onDragEnd = onEnd, onDragCancel = { total = 0f }) { _, d -> total += d }
        }
    }

    @Composable
    fun Name(r: FilmRecipe?, selected: Boolean, m: Modifier) {
        Text(
            text = r?.label ?: "",
            color = if (selected) HudAmber else Color.White.copy(alpha = 0.55f),
            fontSize = if (selected) 15.sp else 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            letterSpacing = 0.5.sp,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = m
                .clip(RoundedCornerShape(50))
                .then(if (r != null && !selected) Modifier.clickable { onSelect(r.id) } else Modifier)
                .padding(horizontal = 6.dp, vertical = 6.dp)
                .semantics { if (r != null) contentDescription = if (selected) "Recipe ${r.label}, selected" else "Switch to ${r.label}" }
        )
    }

    if (compact) {
        Row(modifier.then(swipe), verticalAlignment = Alignment.CenterVertically) {
            Arrow("\u2039", prev, onSelect)
            Text(
                text = current.label,
                color = HudAmber,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Recipe ${current.label}, selected. Swipe to change." }
            )
            Arrow("\u203A", next, onSelect)
        }
        return
    }
    if (vertical) {
        Column(modifier.then(swipe), horizontalAlignment = Alignment.CenterHorizontally) {
            Name(prev, false, Modifier.fillMaxWidth())
            Name(current, true, Modifier.fillMaxWidth())
            Name(next, false, Modifier.fillMaxWidth())
        }
    } else {
        SlidingRecipeStrip(recipes, index, onSelect, modifier)
    }
}

/**
 * The portrait recipe strip, like a camera app's mode switcher: the names slide with your
 * finger and settle on the nearest one (a quick flick carries on to the next), and the one in
 * the middle is selected. Tapping a side name slides it to the middle.
 */
@Composable
private fun SlidingRecipeStrip(
    recipes: List<FilmRecipe>,
    index: Int,
    onSelect: (String) -> Unit,
    modifier: Modifier,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val latestRecipes by androidx.compose.runtime.rememberUpdatedState(recipes)
    val latestIndex by androidx.compose.runtime.rememberUpdatedState(index)
    val latestOnSelect by androidx.compose.runtime.rememberUpdatedState(onSelect)
    // Strip position in items: 1.0 means recipe 1 is in the middle.
    var pos by remember { androidx.compose.runtime.mutableFloatStateOf(index.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    var settleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun settleTo(target: Int) {
        settleJob?.cancel()
        settleJob = scope.launch {
            androidx.compose.animation.core.animate(
                pos, target.toFloat(),
                animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 380f)
            ) { v, _ -> pos = v }
        }
    }
    // Selection changed elsewhere (a tap, or a swipe on the viewfinder): slide to it.
    androidx.compose.runtime.LaunchedEffect(index) { if (!dragging) settleTo(index) }

    androidx.compose.foundation.layout.BoxWithConstraints(modifier.clipToBounds()) {
        val itemW = maxWidth / 2.4f
        val itemPx = with(androidx.compose.ui.platform.LocalDensity.current) { itemW.toPx() }
        val drag = Modifier.pointerInput(itemPx) {
            val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
            detectHorizontalDragGestures(
                onDragStart = {
                    dragging = true
                    settleJob?.cancel()
                    tracker.resetTracking()
                },
                onDragEnd = {
                    dragging = false
                    val v = tracker.calculateVelocity().x
                    // Where a flick would carry it, at most one recipe past where it was let go.
                    val projected = (pos - v / itemPx * 0.15f).coerceIn(pos - 1f, pos + 1f)
                    val target = projected.roundToInt().coerceIn(0, latestRecipes.lastIndex)
                    settleTo(target)
                    if (target != latestIndex) latestOnSelect(latestRecipes[target].id)
                },
                onDragCancel = {
                    dragging = false
                    settleTo(latestIndex)
                }
            ) { change, dx ->
                tracker.addPosition(change.uptimeMillis, change.position)
                pos = (pos - dx / itemPx).coerceIn(-0.35f, latestRecipes.lastIndex + 0.35f)
            }
        }
        Box(Modifier.fillMaxSize().then(drag)) {
            recipes.forEachIndexed { i, r ->
                val d = i - pos
                val ad = abs(d)
                if (ad > 2.5f) return@forEachIndexed
                val sel = (1f - ad).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .width(itemW)
                        .graphicsLayer {
                            translationX = d * itemPx
                            val sc = 0.87f + 0.13f * sel
                            scaleX = sc
                            scaleY = sc
                            alpha = (1f - (ad - 1f).coerceAtLeast(0f) * 0.8f).coerceIn(0f, 1f)
                        }
                        .clip(RoundedCornerShape(50))
                        .clickable { if (i != latestIndex) latestOnSelect(r.id) }
                        .semantics {
                            contentDescription = if (i == index) "Recipe ${r.label}, selected. Swipe to change." else "Switch to ${r.label}"
                        }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = r.label,
                        color = androidx.compose.ui.graphics.lerp(Color.White.copy(alpha = 0.55f), HudAmber, sel),
                        fontSize = 15.sp,
                        fontWeight = if (sel > 0.5f) FontWeight.SemiBold else FontWeight.Normal,
                        letterSpacing = 0.5.sp,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.wrapContentWidth(unbounded = true)
                    )
                }
            }
        }
    }
}

/** Tappable ‹ / › arrow for the compact recipe carousel; blank (but space-holding) at the ends. */
@Composable
private fun Arrow(symbol: String, target: FilmRecipe?, onSelect: (String) -> Unit) {
    Box(
        Modifier
            .size(width = 22.dp, height = 40.dp)
            .clip(RoundedCornerShape(50))
            .then(if (target != null) Modifier.clickable { onSelect(target.id) } else Modifier)
            .semantics { if (target != null) contentDescription = "Switch to ${target.label}" },
        contentAlignment = Alignment.Center
    ) {
        if (target != null) Text(symbol, color = Color.White.copy(alpha = 0.7f), fontSize = 22.sp)
    }
}

/**
 * Hosts a panel the way the phone is held. Upright (or upside down) it fills the screen as usual.
 * Held sideways, the screen stays portrait, so [content] gets a landscape-shaped box turned to
 * read upright, padded clear of the status and navigation bars (which are now at its sides).
 */
@Composable
fun SidewaysHost(deviceOrientation: Int, content: @Composable androidx.compose.foundation.layout.BoxScope.(sideways: Boolean) -> Unit) {
    val sideways = deviceOrientation == 90 || deviceOrientation == 270
    if (!sideways) {
        Box(Modifier.fillMaxSize()) { content(false) }
        return
    }
    val d = androidx.compose.ui.platform.LocalDensity.current
    val top = with(d) { androidx.compose.foundation.layout.WindowInsets.statusBars.getTop(d).toDp() }
    val bottom = with(d) { androidx.compose.foundation.layout.WindowInsets.navigationBars.getBottom(d).toDp() }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        // Turned a quarter anticlockwise (phone turned clockwise), the box's right edge is the
        // phone's top; turned clockwise, its right edge is the phone's bottom.
        val (startPad, endPad) = if (deviceOrientation == 90) bottom to top else top to bottom
        Box(
            Modifier
                .align(Alignment.Center)
                .requiredSize(maxHeight, maxWidth)
                .graphicsLayer { rotationZ = -deviceOrientation.toFloat() }
                .padding(start = startPad, end = endPad)
        ) { content(true) }
    }
}
