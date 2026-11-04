package com.example.cameraapp

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "RecipeStore"

/** Custom (user-made) recipes, saved as JSON in the app's private storage. */
class RecipeStore(context: Context) {
    private val file = File(context.filesDir, "recipes.json")

    fun load(): List<FilmRecipe> = try {
        if (!file.exists()) {
            emptyList()
        } else {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { i ->
                try {
                    recipeFromJson(array.getJSONObject(i))
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping unreadable recipe $i", e)
                    null
                }
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Could not read recipes", e)
        emptyList()
    }

    fun save(recipes: List<FilmRecipe>) {
        try {
            val array = JSONArray()
            recipes.forEach { array.put(recipeToJson(it)) }
            // Write to a temp file and swap, so a crash mid-write can't lose every recipe.
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(array.toString(2))
            if (!tmp.renameTo(file)) {
                file.writeText(array.toString(2))
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not save recipes", e)
        }
    }
}

fun newRecipeId(): String = "custom_${System.currentTimeMillis()}"

private fun JSONObject.f(name: String, default: Float): Float = optDouble(name, default.toDouble()).toFloat()

private fun tintToJson(t: Tint) = JSONArray().put(t.r.toDouble()).put(t.g.toDouble()).put(t.b.toDouble())

private fun tintFromJson(a: JSONArray?): Tint =
    if (a == null || a.length() < 3) Tint() else Tint(a.optDouble(0).toFloat(), a.optDouble(1).toFloat(), a.optDouble(2).toFloat())

fun recipeToJson(r: FilmRecipe): JSONObject = JSONObject().apply {
    put("id", r.id)
    put("label", r.label)
    put("redGain", r.redGain.toDouble())
    put("blueGain", r.blueGain.toDouble())
    put("tint", r.tint.toDouble())
    put("satR", r.satR.toDouble())
    put("satG", r.satG.toDouble())
    put("satB", r.satB.toDouble())
    r.mono?.let { put("mono", JSONArray().put(it.first.toDouble()).put(it.second.toDouble()).put(it.third.toDouble())) }
    put("blackPoint", r.blackPoint.toDouble())
    put("shadows", r.shadows.toDouble())
    put("mids", r.mids.toDouble())
    put("highlights", r.highlights.toDouble())
    put("whitePoint", r.whitePoint.toDouble())
    put("shadowTint", tintToJson(r.shadowTint))
    put("midTint", tintToJson(r.midTint))
    put("highlightTint", tintToJson(r.highlightTint))
    put("grain", r.grain.toDouble())
    put("grainSize", r.grainSize.toDouble())
    put("grainColor", r.grainColor.toDouble())
    put("vignette", r.vignette.toDouble())
    put("clarity", r.clarity.toDouble())
    put("colorChrome", r.colorChrome.toDouble())
    put("halation", r.halation.toDouble())
    val hsl = JSONObject()
    r.hsl.filterValues { !it.isNeutral }.forEach { (band, adj) ->
        hsl.put(band.name, JSONArray().put(adj.hue.toDouble()).put(adj.sat.toDouble()).put(adj.lum.toDouble()))
    }
    put("hsl", hsl)
}

fun recipeFromJson(o: JSONObject): FilmRecipe {
    val mono = o.optJSONArray("mono")?.takeIf { it.length() >= 3 }?.let {
        Triple(it.optDouble(0).toFloat(), it.optDouble(1).toFloat(), it.optDouble(2).toFloat())
    }
    val hslJson = o.optJSONObject("hsl")
    val hsl = ColorBand.entries.mapNotNull { band ->
        hslJson?.optJSONArray(band.name)?.takeIf { it.length() >= 3 }?.let {
            band to HslAdjust(it.optDouble(0).toFloat(), it.optDouble(1).toFloat(), it.optDouble(2).toFloat())
        }
    }.toMap()
    return FilmRecipe(
        id = o.optString("id").ifBlank { newRecipeId() },
        label = o.optString("label").ifBlank { "RECIPE" },
        redGain = o.f("redGain", 1f),
        blueGain = o.f("blueGain", 1f),
        tint = o.f("tint", 0f),
        satR = o.f("satR", 1f),
        satG = o.f("satG", 1f),
        satB = o.f("satB", 1f),
        mono = mono,
        blackPoint = o.f("blackPoint", 0f),
        shadows = o.f("shadows", 0f),
        mids = o.f("mids", 0f),
        highlights = o.f("highlights", 0f),
        whitePoint = o.f("whitePoint", 1f),
        shadowTint = tintFromJson(o.optJSONArray("shadowTint")),
        midTint = tintFromJson(o.optJSONArray("midTint")),
        highlightTint = tintFromJson(o.optJSONArray("highlightTint")),
        grain = o.f("grain", 0f),
        grainSize = o.f("grainSize", 1f),
        grainColor = o.f("grainColor", 0f),
        vignette = o.f("vignette", 0f),
        clarity = o.f("clarity", 0f),
        colorChrome = o.f("colorChrome", 0f),
        halation = o.f("halation", 0f),
        hsl = hsl,
    )
}
