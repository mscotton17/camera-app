package com.example.cameraapp

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "CubeLut"

/**
 * A 3D colour lookup table from a .cube file (Resolve / Lightroom / Premiere format).
 * [data] holds size³ RGB triples with red changing fastest, then green, then blue.
 */
class CubeLut(val name: String, val size: Int, private val data: FloatArray) {

    /** Trilinear lookup of ([r], [g], [b]) in 0..1; writes the result into [out]. */
    fun sample(r: Float, g: Float, b: Float, out: FloatArray) {
        val n1 = size - 1
        val fr = r.coerceIn(0f, 1f) * n1
        val fg = g.coerceIn(0f, 1f) * n1
        val fb = b.coerceIn(0f, 1f) * n1
        val r0 = fr.toInt().coerceAtMost(n1 - 1).coerceAtLeast(0)
        val g0 = fg.toInt().coerceAtMost(n1 - 1).coerceAtLeast(0)
        val b0 = fb.toInt().coerceAtMost(n1 - 1).coerceAtLeast(0)
        val tr = fr - r0
        val tg = fg - g0
        val tb = fb - b0
        for (c in 0 until 3) {
            fun v(ri: Int, gi: Int, bi: Int) = data[((bi * size + gi) * size + ri) * 3 + c]
            val c00 = v(r0, g0, b0) + (v(r0 + 1, g0, b0) - v(r0, g0, b0)) * tr
            val c10 = v(r0, g0 + 1, b0) + (v(r0 + 1, g0 + 1, b0) - v(r0, g0 + 1, b0)) * tr
            val c01 = v(r0, g0, b0 + 1) + (v(r0 + 1, g0, b0 + 1) - v(r0, g0, b0 + 1)) * tr
            val c11 = v(r0, g0 + 1, b0 + 1) + (v(r0 + 1, g0 + 1, b0 + 1) - v(r0, g0 + 1, b0 + 1)) * tr
            val c0 = c00 + (c10 - c00) * tg
            val c1 = c01 + (c11 - c01) * tg
            out[c] = c0 + (c1 - c0) * tb
        }
    }

    /**
     * The table as a 2D image for the preview shader: blue slices side by side
     * (x = red + blue * size, y = green).
     */
    fun toBitmap(): Bitmap {
        val w = size * size
        val pixels = IntArray(w * size)
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val i = ((b * size + g) * size + r) * 3
            fun c(v: Float) = (v.coerceIn(0f, 1f) * 255f).roundToInt()
            pixels[g * w + b * size + r] =
                (0xFF shl 24) or (c(data[i]) shl 16) or (c(data[i + 1]) shl 8) or c(data[i + 2])
        }
        return Bitmap.createBitmap(pixels, w, size, Bitmap.Config.ARGB_8888)
    }

    companion object {
        /** Parses .cube text; returns null if it isn't a valid 3D LUT. */
        fun parse(name: String, text: String): CubeLut? {
            var size = 0
            var minV = floatArrayOf(0f, 0f, 0f)
            var maxV = floatArrayOf(1f, 1f, 1f)
            val values = ArrayList<Float>()
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val parts = line.split(Regex("\\s+"))
                when (parts[0].uppercase()) {
                    "LUT_3D_SIZE" -> size = parts.getOrNull(1)?.toIntOrNull() ?: return null
                    "DOMAIN_MIN" -> minV = FloatArray(3) { parts.getOrNull(it + 1)?.toFloatOrNull() ?: 0f }
                    "DOMAIN_MAX" -> maxV = FloatArray(3) { parts.getOrNull(it + 1)?.toFloatOrNull() ?: 1f }
                    "TITLE", "LUT_1D_SIZE", "LUT_1D_INPUT_RANGE", "LUT_3D_INPUT_RANGE" -> {
                        if (parts[0].uppercase() == "LUT_1D_SIZE") return null // 1D LUTs aren't supported
                    }
                    else -> {
                        if (parts.size >= 3) {
                            val r = parts[0].toFloatOrNull()
                            val g = parts[1].toFloatOrNull()
                            val b = parts[2].toFloatOrNull()
                            if (r != null && g != null && b != null) {
                                values += r; values += g; values += b
                            }
                        }
                    }
                }
            }
            if (size < 2 || size > 65 || values.size != size * size * size * 3) return null
            // Normalise to 0..1 if the file declares another output domain.
            val data = FloatArray(values.size) { i ->
                val c = i % 3
                val range = (maxV[c] - minV[c]).takeIf { it != 0f } ?: 1f
                (values[i] - minV[c]) / range
            }
            return CubeLut(name, size, data)
        }
    }
}

/** Imported .cube files, kept in the app's private storage. */
object CubeLuts {
    private val cache = ConcurrentHashMap<String, CubeLut>()

    private fun dir(context: Context) = File(context.filesDir, "luts").apply { mkdirs() }

    fun list(context: Context): List<String> =
        dir(context).listFiles { f -> f.extension.equals("cube", true) }
            ?.map { it.nameWithoutExtension }?.sorted().orEmpty()

    fun load(context: Context, name: String?): CubeLut? {
        if (name.isNullOrBlank()) return null
        cache[name]?.let { return it }
        val file = File(dir(context), "$name.cube")
        if (!file.exists()) return null
        return try {
            CubeLut.parse(name, file.readText())?.also { cache[name] = it }
        } catch (e: Exception) {
            Log.e(TAG, "Could not read LUT $name", e)
            null
        }
    }

    /** Copies a .cube file into the app; returns its name, or null if it isn't a valid 3D LUT. */
    fun import(context: Context, uri: Uri): String? = try {
        val resolver = context.contentResolver
        val display = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "LUT"
        val base = display.substringBeforeLast('.').uppercase()
            .replace(Regex("[^A-Z0-9 _-]"), "").trim().take(20).ifEmpty { "LUT" }
        val text = resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
        val parsed = CubeLut.parse(base, text)
        if (parsed == null) {
            null
        } else {
            File(dir(context), "$base.cube").writeText(text)
            cache[base] = parsed
            base
        }
    } catch (e: Exception) {
        Log.e(TAG, "LUT import failed", e)
        null
    }

    fun delete(context: Context, name: String) {
        cache.remove(name)
        File(dir(context), "$name.cube").delete()
    }
