package com.example.cameraapp

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.min

/**
 * Where the 4:3 camera image sits on a screen of [size]. With a recipe [aspect] crop, the image
 * is enlarged until the cropped frame fills as much of the screen as it can (the parts that
 * get cropped away may run off-screen), so a crop never makes the picture smaller.
 */
fun previewImageRect(size: Size, aspect: AspectRatio = AspectRatio.NATIVE): Rect {
    val ratio = if (size.height > size.width) 3f / 4f else 4f / 3f
    var w = min(size.width, size.height * ratio)
    var h = w / ratio
    if (aspect != AspectRatio.NATIVE) {
        val crop = previewCropRect(Rect(0f, 0f, w, h), aspect)
        val scale = min(size.width / crop.width, size.height / crop.height)
        if (scale > 1f) {
            w *= scale
            h *= scale
        }
    }
    val left = (size.width - w) / 2f
    val top = (size.height - h) / 2f
    return Rect(left, top, left + w, top + h)
}

/** The recipe's aspect crop inside [image] (same rule as the saved photo). */
fun previewCropRect(image: Rect, aspect: AspectRatio): Rect {
    val c = cropRect(image.width.toInt().coerceAtLeast(1), image.height.toInt().coerceAtLeast(1), aspect)
    return Rect(image.left + c.left, image.top + c.top, image.left + c.right, image.top + c.bottom)
}

/**
 * Shows the recipe's frame on the viewfinder: masks outside the aspect crop, draws the border
 * over the image edges (scaled to fit), and the date stamp where it will be printed.
 */
@Composable
fun FilmFrameOverlay(recipe: FilmRecipe, modifier: Modifier = Modifier) {
    if (recipe.aspect == AspectRatio.NATIVE && recipe.border == BorderStyle.NONE && !recipe.dateStamp) return
    Canvas(modifier) {
        val image = previewImageRect(size, recipe.aspect)
        val crop = previewCropRect(image, recipe.aspect)
        val mask = Color.Black.copy(alpha = 0.9f)
        // Aspect crop: darken the parts of the frame that won't be saved.
        if (crop != image) {
            drawRect(mask, Offset(image.left, image.top), Size(image.width, crop.top - image.top))
            drawRect(mask, Offset(image.left, crop.bottom), Size(image.width, image.bottom - crop.bottom))
            drawRect(mask, Offset(image.left, crop.top), Size(crop.left - image.left, crop.height))
            drawRect(mask, Offset(crop.right, crop.top), Size(image.right - crop.right, crop.height))
            // Hairline at the crop edge, for precise framing.
            drawRect(
                Color.White.copy(alpha = 0.55f), Offset(crop.left, crop.top), Size(crop.width, crop.height),
                style = Stroke(width = 1.dp.toPx())
            )
        }
        if (recipe.border == BorderStyle.NONE) {
            if (recipe.dateStamp) drawStamp(recipe, crop.left, crop.top, crop.width, crop.height)
            return@Canvas
        }
        // Lay the finished print (image + border) over the crop, scaled to fit inside it.
        val ins = borderInsets(recipe, crop.width.toInt().coerceAtLeast(1), crop.height.toInt().coerceAtLeast(1))
        val outW = crop.width + ins.left + ins.right
        val outH = crop.height + ins.top + ins.bottom
        val s = min(crop.width / outW, crop.height / outH)
        val ox = crop.left + (crop.width - outW * s) / 2f
        val oy = crop.top + (crop.height - outH * s) / 2f
        val borderArgb = if (recipe.border == BorderStyle.REBATE) 0xFF0C0C0C.toInt() else recipe.borderColor.argb
        val borderColor = Color(borderArgb)
        // Fill the strip between the crop and the scaled print first, then the border bands.
        drawRect(mask, Offset(crop.left, crop.top), Size(crop.width, oy - crop.top))
        drawRect(mask, Offset(crop.left, oy + outH * s), Size(crop.width, crop.bottom - oy - outH * s))
        drawRect(mask, Offset(crop.left, crop.top), Size(ox - crop.left, crop.height))
        drawRect(mask, Offset(ox + outW * s, crop.top), Size(crop.right - ox - outW * s, crop.height))
        drawRect(borderColor, Offset(ox, oy), Size(outW * s, ins.top * s))
        drawRect(borderColor, Offset(ox, oy + (ins.top + crop.height) * s), Size(outW * s, ins.bottom * s))
        drawRect(borderColor, Offset(ox, oy), Size(ins.left * s, outH * s))
        drawRect(borderColor, Offset(ox + (ins.left + crop.width) * s, oy), Size(ins.right * s, outH * s))
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            nc.save()
            nc.translate(ox, oy)
            nc.scale(s, s)
            val imageRect = RectF(ins.left, ins.top, ins.left + crop.width, ins.top + crop.height)
            drawBorderDetails(nc, recipe, imageRect, outW.toInt(), outH.toInt(), borderArgb)
            if (recipe.dateStamp) drawDateStamp(nc, recipe, imageRect, System.currentTimeMillis())
            nc.restore()
        }
    }
}

private fun DrawScope.drawStamp(recipe: FilmRecipe, left: Float, top: Float, width: Float, height: Float) {
    drawIntoCanvas { canvas ->
        drawDateStamp(canvas.nativeCanvas, recipe, RectF(left, top, left + width, top + height), System.currentTimeMillis())
    }
}
