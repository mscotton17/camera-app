package com.example.cameraapp

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "RecipeStore"

/** The user's own recipes (in their chosen order) and favourite recipe ids (built-in or own). */
data class RecipeLibrary(val recipes: List<FilmRecipe> = emptyList(), val favorites: List<String> = emptyList())

/** Saved in the app's private storage as JSON. */
class RecipeStore(context: Context) {
    private val file = File(context.filesDir, "recipes.json")

    fun load(): RecipeLibrary = try {
        if (!file.exists()) RecipeLibrary() else {
            val text = file.readText().trim()
            if (text.startsWith("[")) {
                // Version 1: a bare array of recipes.
                RecipeLibrary(recipesFromJsonArray(JSONArray(text)))
            } else {
                val o = JSONObject(text)
                val favs = o.optJSONArray("favorites")
                RecipeLibrary(
                    recipesFromJsonArray(o.optJSONArray("recipes") ?: JSONArray()),
                    (0 until (favs?.length() ?: 0)).mapNotNull { favs?.optString(it)?.takeIf { s -> s.isNotBlank() } }
                )
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Could not read recipes", e)
        RecipeLibrary()
    }

    fun save(library: RecipeLibrary) {
        try {
            val o = JSONObject()
            o.put("version", 2)
            o.put("recipes", JSONArray().apply { library.recipes.forEach { put(recipeToJson(it)) } })
            o.put("favorites", JSONArray().apply { library.favorites.forEach { put(it) } })
            // Write to a temp file and swap, so a crash mid-write can't lose every recipe.
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(o.toString(2))
            if (!tmp.renameTo(file)) {
                file.writeText(o.toString(2))
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not save recipes", e)
        }
    }
}

fun newRecipeId(): String = "custom_${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}"

private fun recipesFromJsonArray(array: JSONArray): List<FilmRecipe> =
    (0 until array.length()).mapNotNull { i ->
        try {
            recipeFromJson(array.getJSONObject(i))
        } catch (e: Exception) {
            Log.w(TAG, "Skipping unreadable recipe $i", e)
            null
        }
    }

/**
 * Reads a shared recipe file: one recipe, an array of recipes, or a saved library.
 * Every recipe gets a fresh id so imports never overwrite existing recipes.
 */
fun parseRecipeFile(text: String): List<FilmRecipe> {
    val trimmed = text.trim()
    val list = when {
        trimmed.startsWith("[") -> recipesFromJsonArray(JSONArray(trimmed))
        else -> {
            val o = JSONObject(trimmed)
            o.optJSONArray("recipes")?.let { recipesFromJsonArray(it) } ?: listOf(recipeFromJson(o))
        }
    }
    return list.map { it.copy(id = newRecipeId()) }
}

/** A shareable file for [recipes]. */
fun recipeFileText(recipes: List<FilmRecipe>): String =
    JSONObject().apply {
        put("app", "CameraApp")
        put("version", 2)
        put("recipes", JSONArray().apply { recipes.forEach { put(recipeToJson(it)) } })
    }.toString(2)

fun recipeToJson(r: FilmRecipe): JSONObject = JSONObject().apply {
    put("id", r.id)
    put("label", r.label)
    for (p in AllRecipeParams) when (p) {
        is FloatParam -> put(p.key, p.get(r).toDouble())
        is ChoiceParam -> put(p.key, p.get(r).key)
        is ToggleParam -> put(p.key, p.get(r))
    }
    r.mono?.let { put("mono", JSONArray().put(it.first.toDouble()).put(it.second.toDouble()).put(it.third.toDouble())) }
    put("hsl", JSONObject().apply {
        r.hsl.filterValues { !it.isNeutral }.forEach { (band, a) ->
            put(band.name, JSONArray().put(a.hue.toDouble()).put(a.sat.toDouble()).put(a.lum.toDouble()))
        }
    })
    put("curves", JSONObject().apply {
        r.curves.filterValues { pts -> pts.any { it != 0f } }.forEach { (ch, pts) ->
            put(ch.name, JSONArray().apply { pts.forEach { put(it.toDouble()) } })
        }
    })
    r.lutName?.let { put("lutName", it) }
    put("lutStrength", r.lutStrength.toDouble())
    put("defaultLensMm", r.defaultLensMm)
}

fun recipeFromJson(o: JSONObject): FilmRecipe {
    var r = FilmRecipe(
        id = o.optString("id").ifBlank { newRecipeId() },
        label = o.optString("label").ifBlank { "RECIPE" },
    )
    for (p in AllRecipeParams) when (p) {
        is FloatParam -> {
            val legacy = legacyTintComponent(o, p.key)
            when {
                o.has(p.key) -> r = p.set(r, o.optDouble(p.key, p.default.toDouble()).toFloat())
                legacy != null -> r = p.set(r, legacy)
            }
        }
        is ChoiceParam -> o.optString(p.key).takeIf { it.isNotEmpty() }?.let { key ->
            p.options.firstOrNull { it.key == key }?.let { r = p.set(r, it) }
        }
        is ToggleParam -> if (o.has(p.key)) r = p.set(r, o.optBoolean(p.key))
    }
    val mono = o.optJSONArray("mono")?.takeIf { it.length() >= 3 }?.let {
        Triple(it.optDouble(0).toFloat(), it.optDouble(1).toFloat(), it.optDouble(2).toFloat())
    }
    val hslJson = o.optJSONObject("hsl")
    val hsl = ColorBand.entries.mapNotNull { band ->
        hslJson?.optJSONArray(band.name)?.takeIf { it.length() >= 3 }?.let {
            band to HslAdjust(it.optDouble(0).toFloat(), it.optDouble(1).toFloat(), it.optDouble(2).toFloat())
        }
    }.toMap()
    val curvesJson = o.optJSONObject("curves")
    val curves = CurveChannel.entries.mapNotNull { ch ->
        curvesJson?.optJSONArray(ch.name)?.takeIf { it.length() == CURVE_POINTS }?.let { a ->
            ch to List(CURVE_POINTS) { a.optDouble(it).toFloat() }
        }
    }.toMap()
    return r.copy(
        mono = mono,
        hsl = hsl,
        curves = curves,
        lutName = o.optString("lutName").ifBlank { null },
        lutStrength = o.optDouble("lutStrength", 1.0).toFloat(),
        defaultLensMm = o.optInt("defaultLensMm", 0),
    )
}

/** Version 1 stored tints as "shadowTint": [r, g, b]. */
private fun legacyTintComponent(o: JSONObject, key: String): Float? {
    val dot = key.indexOf('.')
    if (dot < 0) return null
    val arr = o.optJSONArray(key.substring(0, dot)) ?: return null
    val idx = when (key.substring(dot + 1)) { "r" -> 0; "g" -> 1; "b" -> 2; else -> return null }
    return if (arr.length() > idx) arr.optDouble(idx).toFloat() else null
}
