package com.example.cameraapp

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
}

// ---------------------------------------------------------------------------------------------
// Top icon bar
// ---------------------------------------------------------------------------------------------

/**
 * Minimal icon bar: flash (tap to cycle), RAW badge (tap to cycle format), a timer indicator
 * when the self-timer is on, and the ☰ menu.
 */
@Composable
fun TopIconBar(
    flash: FlashSetting,
    flashAvailable: Boolean,
    onFlash: () -> Unit,
    format: CaptureFormat,
    formatAvailable: Boolean,
    onFormat: () -> Unit,
    timerSeconds: Int,
    onMenu: () -> Unit,
    menuOpen: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = if (compact) Arrangement.spacedBy(4.dp) else Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconSlot("Flash ${flash.label.lowercase()}", enabled = flashAvailable, onClick = onFlash) {
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
        if (formatAvailable) {
            IconSlot("Format ${format.label}", onClick = onFormat) {
                val raw = format != CaptureFormat.JPEG
                Box(
                    Modifier
                        .border(1.5.dp, if (raw) HudAmber else Dim, RoundedCornerShape(5.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        if (raw) "RAW" else "JPG",
                        color = if (raw) HudAmber else Dim,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
        if (timerSeconds > 0) {
            IconSlot("Self-timer ${timerSeconds} seconds", onClick = onMenu) {
                Text("⏲ ${timerSeconds}s", color = HudAmber, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
        }
        IconSlot("Menu", onClick = onMenu) {
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
}

@Composable
private fun IconSlot(description: String, enabled: Boolean = true, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) { content() }
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
fun ModeToggle(pro: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(false to "PHOTO", true to "PRO").forEach { (isPro, label) ->
            val selected = isPro == pro
            Text(
                label,
                color = if (selected) HudAmber else Color.White.copy(alpha = 0.8f),
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                letterSpacing = 0.5.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onChange(isPro) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
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
        Text(
            seconds.toString(),
            color = HudAmber,
            fontSize = 96.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Light
        )
    }
}
