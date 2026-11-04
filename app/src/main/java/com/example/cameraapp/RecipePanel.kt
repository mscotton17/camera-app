package com.example.cameraapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PanelBackground = Color.Black.copy(alpha = 0.88f)
private val Muted = Color.White.copy(alpha = 0.55f)

/** A few words describing a recipe's character, from its settings. */
fun recipeSummary(r: FilmRecipe): String {
    val parts = mutableListOf<String>()
    val mono = r.mono
    if (mono != null) {
        parts += "Black & white"
        when {
            mono.first >= 0.5f -> parts += "red filter"
            mono.third >= 0.4f -> parts += "blue filter"
            mono.second >= 0.5f -> parts += "green filter"
        }
    } else {
        parts += "Color"
        val warmth = (r.temperature - 6500f) / 1000f + (r.redGain - r.blueGain) * 5f
        if (warmth > 0.3f) parts += "warm" else if (warmth < -0.3f) parts += "cool"
        val sat = (r.satR + r.satG + r.satB) / 3f + r.vibrance * 0.3f
        if (sat > 1.12f) parts += "vivid" else if (sat < 0.85f) parts += "muted"
    }
    if (r.contrast > 0.08f || r.shadows < -0.04f) parts += "punchy" else if (r.contrast < -0.03f || r.blackPoint > 0.015f) parts += "soft"
    if (r.dynamicRange != DynamicRange.DR100) parts += r.dynamicRange.label
    if (r.halation > 0f) parts += "halation"
    if (r.bloom > 0f) parts += "bloom"
    if (r.lutName != null) parts += "LUT"
    if (r.grain > 0f) parts += if (r.grainColor > 0f) "color grain" else "grain"
    if (r.aspect != AspectRatio.NATIVE) parts += r.aspect.label
    if (r.border != BorderStyle.NONE) parts += "${r.border.label.lowercase()} border"
    if (r.dateStamp) parts += "date stamp"
    return parts.joinToString(" · ")
}

/**
 * Recipe chooser: a large, readable list in the same panel style as the editor. Tapping a
 * recipe applies it straight away (the panel stays open so looks can be compared live).
 */
@Composable
fun RecipePanel(
    recipes: List<FilmRecipe>,
    selectedId: String?,
    favorites: List<String>,
    customIds: Set<String>,
    onSelect: (String?) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onEdit: (FilmRecipe?) -> Unit,
    onNew: () -> Unit,
    onImport: () -> Unit,
    onDevelop: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.background(PanelBackground)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "FILM RECIPES", color = HudAmber, fontFamily = FontFamily.Monospace,
                fontSize = 14.sp, letterSpacing = 1.sp, modifier = Modifier.weight(1f)
            )
            EditorButton("✕", onClick = onClose)
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(recipes, key = { it.id }) { r ->
                RecipeRow(
                    name = r.label,
                    summary = recipeSummary(r) + if (r.id in customIds) " · yours" else "",
                    selected = r.id == selectedId,
                    favorite = r.id in favorites,
                    onClick = { onSelect(r.id) },
                    onFavorite = { onToggleFavorite(r.id) },
                    onEdit = { onEdit(r) }
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EditorButton("+ NEW", highlighted = true, onClick = onNew)
            EditorButton("⬇ IMPORT", onClick = onImport)
            EditorButton("⚙ DEVELOP PHOTO", onClick = onDevelop)
        }
    }
}

@Composable
private fun RecipeRow(
    name: String,
    summary: String,
    selected: Boolean,
    favorite: Boolean?,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    onEdit: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(if (selected) HudAmber.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
            .semantics { contentDescription = "$name. $summary${if (selected) ". Selected" else ""}" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Selection marker.
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (selected) HudAmber else Color.Transparent)
                .border(1.5.dp, if (selected) HudAmber else Color.White.copy(alpha = 0.4f), CircleShape)
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                color = if (selected) HudAmber else Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 18.sp,
                letterSpacing = 0.5.sp
            )
            Text(summary, color = Muted, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (favorite != null) {
            IconText(if (favorite) "★" else "☆", if (favorite) HudAmber else Muted, "Favorite", onFavorite)
        }
        if (onEdit != null) IconText("✎", Muted, "Edit $name", onEdit)
    }
}

@Composable
private fun IconText(symbol: String, color: Color, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, color = color, fontSize = 20.sp)
    }
}
