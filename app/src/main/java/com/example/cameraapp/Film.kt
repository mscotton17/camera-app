package com.example.cameraapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.net.Uri
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import java.util.concurrent.ForkJoinPool
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "Film"

/** Per-channel offsets (in 0..1 units) used for split toning. */
data class Tint(val r: Float = 0f, val g: Float = 0f, val b: Float = 0f)

/**
 * A film-style look built from the four pillars:
 *  1. Colour balance: [redGain]/[blueGain] tilt white balance.
 *  2. Tone curve: a smooth curve through black point, shadows, midtones, highlights and white
 *     point, with per-channel [shadowTint]/[midTint]/[highlightTint] for split toning.
 *  3. Saturation / hue: per-primary saturation ([satR]/[satG]/[satB]) mutes or boosts each
 *     primary; [mono] replaces colour with a weighted channel mix (like a B&W filter).
 *  4. Grain: procedural luminance noise, strongest in the midtones like real film.
 *
 * Pillars 1 and 3 are linear and become a [ColorMatrix]; pillar 2 is a lookup table; grain is
 * added last. The same matrix + table drive the live preview shader and the capture pass.
 */
data class FilmRecipe(
    val id: String,
    val label: String,
    val redGain: Float = 1f,
    val blueGain: Float = 1f,
    val satR: Float = 1f,
    val satG: Float = 1f,
    val satB: Float = 1f,
    /** Channel weights for monochrome, or null for colour. */
    val mono: Triple<Float, Float, Float>? = null,
    val blackPoint: Float = 0f,
    val shadows: Float = 0f,
    val mids: Float = 0f,
    val highlights: Float = 0f,
    val whitePoint: Float = 1f,
    val shadowTint: Tint = Tint(),
    val midTint: Tint = Tint(),
    val highlightTint: Tint = Tint(),
    /** Grain strength at midtones, as a fraction of full scale (0.04 ≈ ±10 levels). */
    val grain: Float = 0f,
) {
    /** Linear part (white balance, per-primary saturation, mono mix) as an Android ColorMatrix. */
    val colorMatrix: ColorMatrix by lazy { buildColorMatrix(this) }

    /** Tone curves as 256-entry tables: [0..255] red, [256..511] green, [512..767] blue. */
    val lut: IntArray by lazy { buildLut(this) }
}

val FilmRecipes: List<FilmRecipe> = listOf(
    // Muted, documentary colour: subdued primaries, deeper shadows with a teal cast,
    // gently held-back highlights.
    FilmRecipe(
        id = "chrome", label = "CHROME",
        redGain = 0.98f, satR = 0.68f, satG = 0.78f, satB = 0.72f,
        shadows = -0.045f, highlights = -0.02f, whitePoint = 0.97f,
        shadowTint = Tint(r = -0.018f, g = 0.004f, b = 0.016f),
        highlightTint = Tint(r = 0.01f, g = 0.004f, b = -0.01f),
        grain = 0.025f,
    ),
    // Warm portrait negative: golden midtones, lifted matte blacks, soft highlight roll-off,
    // restrained greens and blues.
    FilmRecipe(
        id = "portrait", label = "PORTRAIT",
        redGain = 1.05f, blueGain = 0.94f, satR = 0.95f, satG = 0.82f, satB = 0.8f,
        blackPoint = 0.035f, shadows = 0.012f, mids = 0.01f, highlights = -0.03f, whitePoint = 0.965f,
        shadowTint = Tint(r = -0.01f, b = 0.01f),
        midTint = Tint(r = 0.016f, g = 0.007f, b = -0.016f),
        grain = 0.03f,
    ),
    // High-contrast black and white: green-weighted mix, deep blacks, bright highlights,
    // pronounced grain.
    FilmRecipe(
        id = "mono_hc", label = "MONO HC",
        mono = Triple(0.30f, 0.60f, 0.10f),
        shadows = -0.065f, highlights = 0.045f,
        grain = 0.065f,
    ),
    // Saturated slide film: punchy colour and contrast, no grain.
    FilmRecipe(
        id = "vivid", label = "VIVID",
        satR = 1.35f, satG = 1.3f, satB = 1.3f,
        shadows = -0.03f, highlights = 0.02f,
    ),
)

// ---------------------------------------------------------------------------------------------
// Pillars 1 + 3: colour matrix
// ---------------------------------------------------------------------------------------------

private const val LR = 0.2126f
private const val LG = 0.7152f
private const val LB = 0.0722f

private fun buildColorMatrix(r: FilmRecipe): ColorMatrix {
    // Rows of a 3x3 matrix, applied to (R, G, B).
    val m: Array<FloatArray> = if (r.mono != null) {
        val (wr, wg, wb) = r.mono
        val sum = wr + wg + wb
        Array(3) { floatArrayOf(wr / sum, wg / sum, wb / sum) }
    } else {
        // Per-primary saturation: each output channel = luma + s * (channel - luma), which is
        // linear, so it fits in the matrix and can mute one primary while boosting another.
        val sats = floatArrayOf(r.satR, r.satG, r.satB)
        Array(3) { row ->
            val s = sats[row]
            floatArrayOf(
                (1 - s) * LR + if (row == 0) s else 0f,
                (1 - s) * LG + if (row == 1) s else 0f,
                (1 - s) * LB + if (row == 2) s else 0f,
            )
        }
    }
    // White-balance tilt is applied first (scales the input columns).
    val gains = floatArrayOf(r.redGain, 1f, r.blueGain)
    val values = FloatArray(20)
    for (row in 0 until 3) {
        for (col in 0 until 3) values[row * 5 + col] = m[row][col] * gains[col]
    }
    values[18] = 1f // alpha passes through
    return ColorMatrix(values)
}

// ---------------------------------------------------------------------------------------------
// Pillar 2: tone curves (monotone cubic through five control points, per channel)
// ---------------------------------------------------------------------------------------------

private fun buildLut(r: FilmRecipe): IntArray {
    val lut = IntArray(768)
    val xs = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
    for (ch in 0 until 3) {
        fun t(tint: Tint) = when (ch) { 0 -> tint.r; 1 -> tint.g; else -> tint.b }
        val ys = floatArrayOf(
            r.blackPoint,
            0.25f + r.shadows + t(r.shadowTint),
            0.5f + r.mids + t(r.midTint),
            0.75f + r.highlights + t(r.highlightTint),
            r.whitePoint,
        )
        // Keep the curve rising so tones never invert.
        for (i in 1 until ys.size) ys[i] = max(ys[i], ys[i - 1] + 0.001f)
        val curve = monotoneCubic(xs, ys)
        for (i in 0 until 256) {
            lut[ch * 256 + i] = (curve(i / 255f) * 255f).roundToInt().coerceIn(0, 255)
        }
    }
    return lut
}

/** Fritsch–Carlson monotone cubic interpolation: smooth, with no overshoot between points. */
private fun monotoneCubic(xs: FloatArray, ys: FloatArray): (Float) -> Float {
    val n = xs.size
    val d = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]) }
    val m = FloatArray(n)
    m[0] = d[0]
    m[n - 1] = d[n - 2]
    for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
    for (i in 0 until n - 1) {
        if (d[i] == 0f) {
            m[i] = 0f; m[i + 1] = 0f
        } else {
            val a = m[i] / d[i]
            val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9f) {
                val tau = 3f / kotlin.math.sqrt(s)
                m[i] = tau * a * d[i]
                m[i + 1] = tau * b * d[i]
            }
        }
    }
    return { x ->
        val i = xs.indexOfLast { it <= x }.coerceIn(0, n - 2)
        val h = xs[i + 1] - xs[i]
        val t = ((x - xs[i]) / h).coerceIn(0f, 1f)
        val t2 = t * t
        val t3 = t2 * t
        (2 * t3 - 3 * t2 + 1) * ys[i] + (t3 - 2 * t2 + t) * h * m[i] +
            (-2 * t3 + 3 * t2) * ys[i + 1] + (t3 - t2) * h * m[i + 1]
    }
}

// ---------------------------------------------------------------------------------------------
// Capture: one fused pass (matrix -> curve -> grain) over the decoded JPEG
// ---------------------------------------------------------------------------------------------

/** Integer hash -> [-1, 1]; deterministic per pixel cell so grain has no visible pattern. */
private fun grainNoise(x: Int, y: Int, seed: Int): Float {
    var h = x * 374761393 + y * 668265263 + seed * 1442695041
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0xFFFF) / 32767.5f - 1f
}

/** Applies [recipe] to a mutable ARGB_8888 [bitmap] in place, using all CPU cores. */
fun applyRecipe(bitmap: Bitmap, recipe: FilmRecipe) {
    val w = bitmap.width
    val h = bitmap.height
    val cm = recipe.colorMatrix.array
    val lut = recipe.lut
    val grainLevels = recipe.grain * 255f
    // Grain cells scale with the image so it looks the same at any resolution.
    val cell = max(1, (w / 2400f).roundToInt())
    val seed = (System.nanoTime() and 0x7FFFFFFF).toInt()

    val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
    val band = (h + threads - 1) / threads
    val pool = ForkJoinPool.commonPool()
    (0 until threads).map { t ->
        pool.submit(Runnable {
            val row = IntArray(w)
            val start = t * band
            val end = min(h, start + band)
            for (y in start until end) {
                bitmap.getPixels(row, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    val c = row[x]
                    val r = ((c shr 16) and 0xFF).toFloat()
                    val g = ((c shr 8) and 0xFF).toFloat()
                    val b = (c and 0xFF).toFloat()
                    // Pillars 1 + 3: colour matrix.
                    val mr = (cm[0] * r + cm[1] * g + cm[2] * b + cm[4]).roundToInt().coerceIn(0, 255)
                    val mg = (cm[5] * r + cm[6] * g + cm[7] * b + cm[9]).roundToInt().coerceIn(0, 255)
                    val mb = (cm[10] * r + cm[11] * g + cm[12] * b + cm[14]).roundToInt().coerceIn(0, 255)
                    // Pillar 2: tone curves.
                    var outR = lut[mr]
                    var outG = lut[256 + mg]
                    var outB = lut[512 + mb]
                    // Pillar 4: monochromatic grain, weighted toward the midtones.
                    if (grainLevels > 0f) {
                        val l = (LR * outR + LG * outG + LB * outB) / 255f
                        val d = (grainNoise(x / cell, y / cell, seed) * grainLevels * 4f * l * (1f - l)).roundToInt()
                        outR = (outR + d).coerceIn(0, 255)
                        outG = (outG + d).coerceIn(0, 255)
                        outB = (outB + d).coerceIn(0, 255)
                    }
                    row[x] = (c and 0xFF000000.toInt()) or (outR shl 16) or (outG shl 8) or outB
                }
                bitmap.setPixels(row, 0, w, 0, y, w, 1)
            }
        })
    }.forEach { it.get() }
}

/** All EXIF tag names ExifInterface knows, except embedded-thumbnail bookkeeping. */
private val ExifTags: List<String> by lazy {
    ExifInterface::class.java.fields
        .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.name.startsWith("TAG_") && it.type == String::class.java }
        .mapNotNull { it.get(null) as? String }
        .filterNot { it.startsWith("JPEGInterchangeFormat") || it.startsWith("Thumbnail") }
        .distinct()
}

/**
 * Re-renders the JPEG at [uri] with [recipe]: decode, process, re-encode at quality 95, then
 * restore the original EXIF (exposure, orientation, GPS, ...) and note the recipe in Software.
 */
fun applyRecipeToJpeg(context: Context, uri: Uri, recipe: FilmRecipe) {
    val resolver = context.contentResolver
    val savedExif: Map<String, String> = resolver.openInputStream(uri)?.use { input ->
        val exif = ExifInterface(input)
        ExifTags.mapNotNull { tag -> exif.getAttribute(tag)?.let { tag to it } }.toMap()
    }.orEmpty()

    val options = BitmapFactory.Options().apply {
        inMutable = true
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return
    try {
        applyRecipe(bitmap, recipe)
        resolver.openOutputStream(uri, "wt")?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    } finally {
        bitmap.recycle()
    }

    resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
        val exif = ExifInterface(pfd.fileDescriptor)
        for ((tag, value) in savedExif) {
            try {
                exif.setAttribute(tag, value)
            } catch (e: Exception) {
                Log.w(TAG, "Could not restore EXIF $tag", e)
            }
        }
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "CameraApp - ${recipe.label} recipe")
        exif.saveAttributes()
    }
}

// ---------------------------------------------------------------------------------------------
// Live preview: the same matrix + curves (+ grain) as a GPU shader
// ---------------------------------------------------------------------------------------------

private const val FILM_AGSL = """
uniform shader content;
uniform shader lut;
uniform float3x3 cm;
uniform float grainAmount;
uniform float seed;

float hash(float2 p) {
    return fract(sin(dot(p, float2(12.9898, 78.233)) + seed) * 43758.5453);
}

half4 main(float2 p) {
    half4 c = content.eval(p);
    float3 rgb = float3(c.rgb);
    // Leave the black letterbox bars alone.
    if (max(rgb.r, max(rgb.g, rgb.b)) < 0.004) return c;
    rgb = clamp(cm * rgb, 0.0, 1.0);
    rgb = float3(
        lut.eval(float2(rgb.r * 255.0 + 0.5, 0.5)).r,
        lut.eval(float2(rgb.g * 255.0 + 0.5, 0.5)).g,
        lut.eval(float2(rgb.b * 255.0 + 0.5, 0.5)).b);
    if (grainAmount > 0.0) {
        float l = dot(rgb, float3(0.2126, 0.7152, 0.0722));
        rgb += (hash(floor(p)) * 2.0 - 1.0) * grainAmount * 4.0 * l * (1.0 - l);
    }
    return half4(half3(clamp(rgb, 0.0, 1.0)), c.a);
}
"""

/** Builds the live-preview effect; call [update] with a new seed to animate the grain. */
@RequiresApi(33)
class FilmPreviewShader(recipe: FilmRecipe) {
    private val shader = RuntimeShader(FILM_AGSL)

    init {
        val a = recipe.colorMatrix.array
        // AGSL matrices are column-major.
        shader.setFloatUniform(
            "cm",
            floatArrayOf(a[0], a[5], a[10], a[1], a[6], a[11], a[2], a[7], a[12])
        )
        val lut = recipe.lut
        val pixels = IntArray(256) { i ->
            (0xFF shl 24) or (lut[i] shl 16) or (lut[256 + i] shl 8) or lut[512 + i]
        }
        val lutBitmap = Bitmap.createBitmap(pixels, 256, 1, Bitmap.Config.ARGB_8888)
        shader.setInputShader("lut", BitmapShader(lutBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        shader.setFloatUniform("grainAmount", recipe.grain)
        shader.setFloatUniform("seed", 0f)
    }

    fun effect(seed: Float): RenderEffect {
        shader.setFloatUniform("seed", seed)
        return RenderEffect.createRuntimeShaderEffect(shader, "content")
    }
}

/** Android 12 fallback for the preview: colour matrix only (no curves or grain). */
@RequiresApi(31)
fun filmColorFilterEffect(recipe: FilmRecipe): RenderEffect =
    RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(recipe.colorMatrix))
