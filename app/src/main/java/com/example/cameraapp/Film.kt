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
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val TAG = "Film"

/** Per-channel offsets (in 0..1 units) used for split toning. */
data class Tint(val r: Float = 0f, val g: Float = 0f, val b: Float = 0f)

/** The eight hue bands of the per-colour HSL controls, with their centre hue in degrees. */
enum class ColorBand(val label: String, val hueDeg: Float, val swatch: Long) {
    RED("RED", 0f, 0xFFE5484D),
    ORANGE("ORANGE", 30f, 0xFFF76B15),
    YELLOW("YELLOW", 60f, 0xFFFFC53D),
    GREEN("GREEN", 120f, 0xFF46A758),
    AQUA("AQUA", 180f, 0xFF12A594),
    BLUE("BLUE", 240f, 0xFF3E63DD),
    PURPLE("PURPLE", 270f, 0xFF8E4EC6),
    MAGENTA("MAGENTA", 300f, 0xFFD6409F),
}

/** Adjustment for one hue band: hue shift in degrees (±30), saturation (±1), luminance (±0.5). */
data class HslAdjust(val hue: Float = 0f, val sat: Float = 0f, val lum: Float = 0f) {
    val isNeutral: Boolean get() = hue == 0f && sat == 0f && lum == 0f
}

/**
 * A film-style look. Processing order (identical in the preview shader and the capture pass):
 *
 *  1. Colour matrix: white balance ([redGain], [blueGain], [tint]), per-primary saturation
 *     ([satR]/[satG]/[satB]) or a monochrome channel mix ([mono]).
 *  2. [colorChrome]: compresses and deepens highly saturated colours before they clip.
 *  3. Per-colour HSL ([hsl]): hue / saturation / luminance for eight hue bands.
 *  4. [halation]: red-orange glow bleeding out of bright highlights.
 *  5. Tone curve: black point, shadows, mids, highlights, white point, with per-channel
 *     [shadowTint]/[midTint]/[highlightTint] split toning.
 *  6. [clarity]: midtone micro-contrast (negative = soft glow, positive = punch).
 *  7. [vignette]: soft radial edge darkening.
 *  8. Grain: [grain] strength, [grainSize] coarseness, [grainColor] chroma.
 */
data class FilmRecipe(
    val id: String,
    val label: String,
    val redGain: Float = 1f,
    val blueGain: Float = 1f,
    /** Green–magenta white balance: positive = magenta, negative = green (±0.1). */
    val tint: Float = 0f,
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
    /** Grain coarseness: 1 = fine 35mm, 3 = coarse pushed film. */
    val grainSize: Float = 1f,
    /** 0 = monochrome grain, 1 = fully independent per-channel (colour negative) grain. */
    val grainColor: Float = 0f,
    /** Radial edge darkening, 0..1 (1 = corners at ~25% brightness), softly feathered. */
    val vignette: Float = 0f,
    /** Midtone micro-contrast, -0.5..+0.5: negative softens/glows, positive adds punch. */
    val clarity: Float = 0f,
    /** 0..1: compresses and deepens highly saturated colours so they don't clip. */
    val colorChrome: Float = 0f,
    /** 0..1: red-orange glow around bright highlights, like cinema film. */
    val halation: Float = 0f,
    /** Per-colour hue / saturation / luminance. Missing bands are neutral. */
    val hsl: Map<ColorBand, HslAdjust> = emptyMap(),
) {
    /** Linear part (white balance, per-primary saturation, mono mix) as an Android ColorMatrix. */
    val colorMatrix: ColorMatrix by lazy { buildColorMatrix(this) }

    /** Tone curves as 256-entry tables: [0..255] red, [256..511] green, [512..767] blue. */
    val lut: IntArray by lazy { buildLut(this) }

    /** HSL per band in band order: hue shift (turns), saturation, luminance. */
    internal val hslHue: FloatArray by lazy { FloatArray(8) { (hsl[ColorBand.entries[it]]?.hue ?: 0f) / 360f } }
    internal val hslSat: FloatArray by lazy { FloatArray(8) { hsl[ColorBand.entries[it]]?.sat ?: 0f } }
    internal val hslLum: FloatArray by lazy { FloatArray(8) { hsl[ColorBand.entries[it]]?.lum ?: 0f } }
    internal val hasHsl: Boolean get() = hsl.values.any { !it.isNeutral }
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
    // Gritty street look: punchy micro-contrast, dense colour that holds saturated signs and
    // clothing without clipping, cool shadows, a strong vignette.
    FilmRecipe(
        id = "street", label = "STREET",
        satR = 1.08f, satG = 0.88f, satB = 1.02f,
        shadows = -0.045f, highlights = 0.015f,
        shadowTint = Tint(r = -0.01f, b = 0.01f),
        grain = 0.035f, vignette = 0.35f, clarity = 0.35f, colorChrome = 0.7f,
    ),
    // Tungsten cinema stock: cool cast, glowing red halation around lights, olive greens,
    // deep teal-blue skies, soft clarity and coarse colour grain.
    FilmRecipe(
        id = "cine", label = "CINE",
        redGain = 0.97f, blueGain = 1.04f, tint = 0.015f,
        satR = 1.0f, satG = 0.85f, satB = 0.95f,
        blackPoint = 0.02f, shadows = -0.02f, highlights = -0.03f, whitePoint = 0.97f,
        shadowTint = Tint(r = -0.012f, g = 0.004f, b = 0.012f),
        highlightTint = Tint(r = 0.012f, g = 0.004f, b = -0.008f),
        grain = 0.04f, grainSize = 1.8f, grainColor = 0.35f,
        clarity = -0.1f, halation = 0.55f,
        hsl = mapOf(
            ColorBand.GREEN to HslAdjust(hue = -12f, sat = -0.25f),
            ColorBand.BLUE to HslAdjust(hue = -8f, sat = 0.1f, lum = -0.1f),
            ColorBand.ORANGE to HslAdjust(sat = 0.05f, lum = 0.05f),
        ),
    ),
    // Saturated slide film: punchy colour and contrast, no grain.
    FilmRecipe(
        id = "vivid", label = "VIVID",
        satR = 1.35f, satG = 1.3f, satB = 1.3f,
        shadows = -0.03f, highlights = 0.02f,
    ),
)

// ---------------------------------------------------------------------------------------------
// Shared constants (the CPU pass and the preview shader must agree)
// ---------------------------------------------------------------------------------------------

private const val LR = 0.2126f
private const val LG = 0.7152f
private const val LB = 0.0722f

/** Clarity blur radius as a fraction of image width. */
private const val CLARITY_RADIUS = 0.01f
/** Detail gain at clarity = 1 (so ±0.5 doubles or halves local midtone contrast). */
private const val CLARITY_GAIN = 2f
/** Vignette starts this far out (fraction of the half-diagonal) and is darkest at the corners. */
private const val VIGNETTE_START = 0.3f
private const val VIGNETTE_DEPTH = 0.75f
/** Colour chrome: chroma range over which it fades in, and how much it compresses/deepens. */
private const val CHROME_LOW = 0.35f
private const val CHROME_HIGH = 0.9f
private const val CHROME_DESAT = 0.25f
private const val CHROME_DEEPEN = 0.18f
/** Halation: glow radius (fraction of width), luma where highlights start glowing, colour, gain. */
private const val HALATION_RADIUS = 0.02f
private const val HALATION_THRESHOLD = 0.7f
private const val HALATION_R = 1.0f
private const val HALATION_G = 0.32f
private const val HALATION_B = 0.08f
private const val HALATION_GAIN = 0.6f
/** Grain cell size at grainSize 1, as a fraction of image width. */
private const val GRAIN_CELL = 1f / 2400f
/** Value noise averages neighbouring cells; this restores its contrast to match per-pixel grain. */
private const val GRAIN_SMOOTH_BOOST = 1.4f
/** HSL changes fade in over this much saturation, so greys are never shifted. */
private const val HSL_SAT_FADE = 0.25f

/** Band centres (turns) and the distance to the next/previous band, for tent weighting. */
private val BandCenters = FloatArray(8) { ColorBand.entries[it].hueDeg / 360f }
private val BandRight = FloatArray(8) { i -> (BandCenters[(i + 1) % 8] + if (i == 7) 1f else 0f) - BandCenters[i] }
private val BandLeft = FloatArray(8) { i -> BandCenters[i] - (BandCenters[(i + 7) % 8] - if (i == 0) 1f else 0f) }

private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun fract(x: Float): Float = x - floor(x)

// ---------------------------------------------------------------------------------------------
// Colour matrix (white balance + saturation / mono)
// ---------------------------------------------------------------------------------------------

private fun buildColorMatrix(r: FilmRecipe): ColorMatrix {
    // Rows of a 3x3 matrix, applied to (R, G, B).
    val m: Array<FloatArray> = if (r.mono != null) {
        val (wr, wg, wb) = r.mono
        val sum = (wr + wg + wb).takeIf { it > 0f } ?: 1f
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
    // White balance is applied first (scales the input columns); tint moves green vs magenta.
    val gains = floatArrayOf(r.redGain, 1f - r.tint, r.blueGain)
    val values = FloatArray(20)
    for (row in 0 until 3) {
        for (col in 0 until 3) values[row * 5 + col] = m[row][col] * gains[col]
    }
    values[18] = 1f // alpha passes through
    return ColorMatrix(values)
}

// ---------------------------------------------------------------------------------------------
// Tone curves (monotone cubic through five control points, per channel)
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
                val tau = 3f / sqrt(s)
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
// Low-resolution helpers for clarity and halation
// ---------------------------------------------------------------------------------------------

/** The original image's luminance (0..1) at ~400px wide, shared by the blur maps. */
private class LowResLuma(source: Bitmap) {
    val w: Int = min(source.width, 400)
    val h: Int = max(1, (source.height * w / source.width.toFloat()).roundToInt())
    val luma = FloatArray(w * h)
    val sx = (w - 1).toFloat() / max(1, source.width - 1)
    val sy = (h - 1).toFloat() / max(1, source.height - 1)

    init {
        val small = Bitmap.createScaledBitmap(source, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== source) small.recycle()
        for (i in px.indices) {
            val c = px[i]
            luma[i] = (LR * ((c shr 16) and 0xFF) + LG * ((c shr 8) and 0xFF) + LB * (c and 0xFF)) / 255f
        }
    }
}

/** A blurred map of [transform] applied to the original luminance, sampled at full resolution. */
private class BlurMap(private val base: LowResLuma, radiusFraction: Float, transform: (Float) -> Float) {
    private val w = base.w
    private val h = base.h
    private val data = FloatArray(base.luma.size) { transform(base.luma[it]) }

    init {
        val radius = max(1, (w * radiusFraction).roundToInt())
        // Two box passes in each direction approximate a Gaussian.
        repeat(2) {
            boxBlur(radius, horizontal = true)
            boxBlur(radius, horizontal = false)
        }
    }

    /** Bilinear sample at full-resolution pixel (x, y). */
    fun sample(x: Int, y: Int): Float {
        val fx = x * base.sx
        val fy = y * base.sy
        val x0 = fx.toInt().coerceIn(0, w - 1)
        val y0 = fy.toInt().coerceIn(0, h - 1)
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val tx = fx - x0
        val ty = fy - y0
        val top = data[y0 * w + x0] * (1 - tx) + data[y0 * w + x1] * tx
        val bottom = data[y1 * w + x0] * (1 - tx) + data[y1 * w + x1] * tx
        return top * (1 - ty) + bottom * ty
    }

    private fun boxBlur(r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val line = FloatArray(len)
        for (l in 0 until lines) {
            fun idx(i: Int) = if (horizontal) l * w + i else i * w + l
            for (i in 0 until len) line[i] = data[idx(i)]
            var sum = 0f
            for (i in -r..r) sum += line[i.coerceIn(0, len - 1)]
            for (i in 0 until len) {
                data[idx(i)] = sum / (2 * r + 1)
                sum += line[(i + r + 1).coerceAtMost(len - 1)] - line[(i - r).coerceAtLeast(0)]
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Grain noise
// ---------------------------------------------------------------------------------------------

/** Integer hash -> [-1, 1]. */
private fun grainNoise(x: Int, y: Int, seed: Int): Float {
    var h = x * 374761393 + y * 668265263 + seed * 1442695041
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0xFFFF) / 32767.5f - 1f
}

/** Smooth value noise in [-1, 1] for coarse grain ([x], [y] in cell units). */
private fun valueNoise(x: Float, y: Float, seed: Int): Float {
    val x0 = floor(x).toInt()
    val y0 = floor(y).toInt()
    var tx = x - x0
    var ty = y - y0
    tx = tx * tx * (3f - 2f * tx)
    ty = ty * ty * (3f - 2f * ty)
    val a = grainNoise(x0, y0, seed)
    val b = grainNoise(x0 + 1, y0, seed)
    val c = grainNoise(x0, y0 + 1, seed)
    val d = grainNoise(x0 + 1, y0 + 1, seed)
    val v = (a + (b - a) * tx) * (1 - ty) + (c + (d - c) * tx) * ty
    return (v * GRAIN_SMOOTH_BOOST).coerceIn(-1f, 1f)
}

// ---------------------------------------------------------------------------------------------
// Per-colour HSL (same formulas as the shader)
// ---------------------------------------------------------------------------------------------

/** Applies [recipe]'s HSL bands to [rgb] (0..1, in place). */
private fun applyHsl(rgb: FloatArray, recipe: FilmRecipe) {
    val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    val d = mx - mn
    if (mx <= 0f) return
    var s = d / mx
    val v = mx
    var h = when {
        d == 0f -> 0f
        mx == r -> fract(((g - b) / d) / 6f)
        mx == g -> ((b - r) / d + 2f) / 6f
        else -> ((r - g) / d + 4f) / 6f
    }
    var hueShift = 0f
    var satAdj = 0f
    var lumAdj = 0f
    for (i in 0 until 8) {
        val delta = fract(h - BandCenters[i] + 0.5f) - 0.5f
        val wgt = max(0f, 1f - max(delta / BandRight[i], -delta / BandLeft[i]))
        if (wgt > 0f) {
            hueShift += wgt * recipe.hslHue[i]
            satAdj += wgt * recipe.hslSat[i]
            lumAdj += wgt * recipe.hslLum[i]
        }
    }
    val fade = smoothstep(0f, HSL_SAT_FADE, s)
    h = fract(h + hueShift * fade)
    s = (s * (1f + satAdj)).coerceIn(0f, 1f)
    val nv = v * (1f + lumAdj * fade)
    // HSV -> RGB
    fun channel(k: Float): Float {
        val p = abs(fract(h + k) * 6f - 3f)
        return nv * (1f + s * ((p - 1f).coerceIn(0f, 1f) - 1f))
    }
    rgb[0] = channel(1f)
    rgb[1] = channel(2f / 3f)
    rgb[2] = channel(1f / 3f)
}

// ---------------------------------------------------------------------------------------------
// Capture: one fused pass over the decoded JPEG
// ---------------------------------------------------------------------------------------------

/** Applies [recipe] to a mutable ARGB_8888 [bitmap] in place, using all CPU cores. */
fun applyRecipe(bitmap: Bitmap, recipe: FilmRecipe) {
    val w = bitmap.width
    val h = bitmap.height
    val cm = recipe.colorMatrix.array
    val lut = recipe.lut
    val seed = (System.nanoTime() and 0x7FFFFFFF).toInt()

    val needsBase = recipe.clarity != 0f || recipe.halation > 0f
    val base = if (needsBase) LowResLuma(bitmap) else null
    // Clarity compares each pixel with its surroundings in the original image.
    val clarityMap = if (base != null && recipe.clarity != 0f) BlurMap(base, CLARITY_RADIUS) { it } else null
    // Halation spreads a glow from the original image's highlights.
    val haloMap = if (base != null && recipe.halation > 0f) {
        BlurMap(base, HALATION_RADIUS) { smoothstep(HALATION_THRESHOLD, 1f, it) }
    } else {
        null
    }
    val clarity = recipe.clarity * CLARITY_GAIN
    val halation = recipe.halation * HALATION_GAIN * 255f
    val chrome = recipe.colorChrome
    val hasHsl = recipe.hasHsl
    val vignette = recipe.vignette * VIGNETTE_DEPTH
    val cx = (w - 1) / 2f
    val cy = (h - 1) / 2f
    val halfDiagonal = sqrt(cx * cx + cy * cy).coerceAtLeast(1f)
    val grainLevels = recipe.grain * 255f
    // Grain cells scale with the image so it looks the same at any resolution.
    val grainCell = max(1f, w * GRAIN_CELL * recipe.grainSize)
    val grainColor = recipe.grainColor

    fun grainAt(x: Int, y: Int, s: Int): Float =
        if (grainCell <= 1.01f) grainNoise(x, y, s) else valueNoise(x / grainCell, y / grainCell, s)

    val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
    val band = (h + threads - 1) / threads
    val pool = ForkJoinPool.commonPool()
    (0 until threads).map { t ->
        pool.submit(Runnable {
            val row = IntArray(w)
            val px = FloatArray(3)
            val start = t * band
            val end = min(h, start + band)
            for (y in start until end) {
                bitmap.getPixels(row, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    val c = row[x]
                    val r = ((c shr 16) and 0xFF).toFloat()
                    val g = ((c shr 8) and 0xFF).toFloat()
                    val b = (c and 0xFF).toFloat()
                    // 1. Colour matrix (kept unclamped so colour chrome sees the overshoot).
                    var fr = cm[0] * r + cm[1] * g + cm[2] * b + cm[4]
                    var fg = cm[5] * r + cm[6] * g + cm[7] * b + cm[9]
                    var fb = cm[10] * r + cm[11] * g + cm[12] * b + cm[14]
                    // 2. Colour chrome: pull in and deepen highly saturated colours before they clip.
                    if (chrome > 0f) {
                        val chroma = (max(fr, max(fg, fb)) - min(fr, min(fg, fb))) / 255f
                        val wgt = smoothstep(CHROME_LOW, CHROME_HIGH, chroma) * chrome
                        if (wgt > 0f) {
                            val l = LR * fr + LG * fg + LB * fb
                            val keep = 1f - CHROME_DESAT * wgt
                            val deepen = 1f - CHROME_DEEPEN * wgt
                            fr = (l + (fr - l) * keep) * deepen
                            fg = (l + (fg - l) * keep) * deepen
                            fb = (l + (fb - l) * keep) * deepen
                        }
                    }
                    // 3. Per-colour HSL.
                    if (hasHsl) {
                        px[0] = (fr / 255f).coerceIn(0f, 1f)
                        px[1] = (fg / 255f).coerceIn(0f, 1f)
                        px[2] = (fb / 255f).coerceIn(0f, 1f)
                        applyHsl(px, recipe)
                        fr = px[0] * 255f; fg = px[1] * 255f; fb = px[2] * 255f
                    }
                    // 4. Halation: warm glow spreading from nearby highlights.
                    if (haloMap != null) {
                        val glow = haloMap.sample(x, y) * halation
                        fr += HALATION_R * glow; fg += HALATION_G * glow; fb += HALATION_B * glow
                    }
                    // 5. Tone curves.
                    var outR = lut[fr.roundToInt().coerceIn(0, 255)].toFloat()
                    var outG = lut[256 + fg.roundToInt().coerceIn(0, 255)].toFloat()
                    var outB = lut[512 + fb.roundToInt().coerceIn(0, 255)].toFloat()
                    // 6. Clarity: add (or remove) local detail, mostly in the midtones.
                    if (clarityMap != null) {
                        val detail = (LR * r + LG * g + LB * b) / 255f - clarityMap.sample(x, y)
                        val l = (LR * outR + LG * outG + LB * outB) / 255f
                        val d = clarity * detail * 4f * l * (1f - l) * 255f
                        outR += d; outG += d; outB += d
                    }
                    // 7. Vignette: soft radial darkening toward the corners.
                    if (vignette > 0f) {
                        val dx = x - cx
                        val dy = y - cy
                        val radius = sqrt(dx * dx + dy * dy) / halfDiagonal
                        val f = 1f - vignette * smoothstep(VIGNETTE_START, 1f, radius)
                        outR *= f; outG *= f; outB *= f
                    }
                    // 8. Grain, weighted toward the midtones; optionally per-channel (colour grain).
                    if (grainLevels > 0f) {
                        val l = ((LR * outR + LG * outG + LB * outB) / 255f).coerceIn(0f, 1f)
                        val amp = grainLevels * 4f * l * (1f - l)
                        val n = grainAt(x, y, seed)
                        if (grainColor > 0f) {
                            outR += amp * (n + (grainAt(x, y, seed + 1) - n) * grainColor)
                            outG += amp * (n + (grainAt(x, y, seed + 2) - n) * grainColor)
                            outB += amp * (n + (grainAt(x, y, seed + 3) - n) * grainColor)
                        } else {
                            outR += amp * n; outG += amp * n; outB += amp * n
                        }
                    }
                    val pr = outR.roundToInt().coerceIn(0, 255)
                    val pg = outG.roundToInt().coerceIn(0, 255)
                    val pb = outB.roundToInt().coerceIn(0, 255)
                    row[x] = (c and 0xFF000000.toInt()) or (pr shl 16) or (pg shl 8) or pb
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
// Live preview: the same pipeline as a GPU shader
// ---------------------------------------------------------------------------------------------

private const val FILM_AGSL = """
uniform shader content;
uniform shader lut;
uniform float3x3 cm;
uniform float colorChrome;
uniform float4 chromeParams;   // low, high, desat, deepen
uniform float hslOn;
uniform float4 hslCenterA;
uniform float4 hslCenterB;
uniform float4 hslRightA;
uniform float4 hslRightB;
uniform float4 hslLeftA;
uniform float4 hslLeftB;
uniform float4 hslHueA;
uniform float4 hslHueB;
uniform float4 hslSatA;
uniform float4 hslSatB;
uniform float4 hslLumA;
uniform float4 hslLumB;
uniform float hslSatFade;
uniform float halation;        // amount * gain
uniform float4 halationParams; // r, g, b, threshold
uniform float halationRadius;
uniform float clarity;         // amount * gain
uniform float clarityRadius;
uniform float vignette;        // amount * depth
uniform float vignetteStart;
uniform float grainAmount;
uniform float grainCellFraction; // cell size (fraction of image width) at this grain size
uniform float grainColor;
uniform float grainBoost;
uniform float seed;
uniform float4 frame;          // image rect inside the view: left, top, width, height (px)

float luma(float3 c) { return dot(c, float3(0.2126, 0.7152, 0.0722)); }

float hash(float2 p) {
    return fract(sin(dot(p, float2(12.9898, 78.233)) + seed) * 43758.5453);
}

float lumaAt(float2 p) {
    // Stay inside the image so the black bars don't darken blurs at the edges.
    float2 q = clamp(p, frame.xy, frame.xy + frame.zw - 1.0);
    return luma(float3(content.eval(q).rgb));
}

float noise01(float2 p, float2 o) {
    float cell = max(1.0, frame.z * grainCellFraction);
    if (cell <= 1.01) return hash(floor(p) + o);
    float2 q = p / cell;
    float2 i = floor(q);
    float2 f = fract(q);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash(i + o);
    float b = hash(i + float2(1.0, 0.0) + o);
    float c = hash(i + float2(0.0, 1.0) + o);
    float d = hash(i + float2(1.0, 1.0) + o);
    float v = mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
    return clamp((v - 0.5) * grainBoost + 0.5, 0.0, 1.0);
}

float3 rgb2hsv(float3 c) {
    float mx = max(c.r, max(c.g, c.b));
    float mn = min(c.r, min(c.g, c.b));
    float d = mx - mn;
    float h = 0.0;
    if (d > 0.0) {
        if (mx == c.r) h = fract(((c.g - c.b) / d) / 6.0);
        else if (mx == c.g) h = ((c.b - c.r) / d + 2.0) / 6.0;
        else h = ((c.r - c.g) / d + 4.0) / 6.0;
    }
    return float3(h, mx > 0.0 ? d / mx : 0.0, mx);
}

float3 hsv2rgb(float3 c) {
    float3 p = abs(fract(c.x + float3(1.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    return c.z * (1.0 + c.y * (clamp(p - 1.0, 0.0, 1.0) - 1.0));
}

float4 bandWeights(float h, float4 center, float4 right, float4 left) {
    float4 d = fract(h - center + 0.5) - 0.5;
    return max(float4(0.0), 1.0 - max(d / right, -d / left));
}

half4 main(float2 p) {
    half4 c = content.eval(p);
    float3 src = float3(c.rgb);
    // Leave the black letterbox bars alone.
    if (max(src.r, max(src.g, src.b)) < 0.004) return c;

    // 1. Colour matrix.
    float3 rgb = cm * src;

    // 2. Colour chrome.
    if (colorChrome > 0.0) {
        float chroma = max(rgb.r, max(rgb.g, rgb.b)) - min(rgb.r, min(rgb.g, rgb.b));
        float w = smoothstep(chromeParams.x, chromeParams.y, chroma) * colorChrome;
        float l = luma(rgb);
        rgb = (l + (rgb - l) * (1.0 - chromeParams.z * w)) * (1.0 - chromeParams.w * w);
    }
    rgb = clamp(rgb, 0.0, 1.0);

    // 3. Per-colour HSL.
    if (hslOn > 0.0 && max(rgb.r, max(rgb.g, rgb.b)) > 0.0) {
        float3 hsv = rgb2hsv(rgb);
        float4 wA = bandWeights(hsv.x, hslCenterA, hslRightA, hslLeftA);
        float4 wB = bandWeights(hsv.x, hslCenterB, hslRightB, hslLeftB);
        float hueShift = dot(wA, hslHueA) + dot(wB, hslHueB);
        float satAdj = dot(wA, hslSatA) + dot(wB, hslSatB);
        float lumAdj = dot(wA, hslLumA) + dot(wB, hslLumB);
        float fade = smoothstep(0.0, hslSatFade, hsv.y);
        hsv.x = fract(hsv.x + hueShift * fade);
        hsv.y = clamp(hsv.y * (1.0 + satAdj), 0.0, 1.0);
        hsv.z = hsv.z * (1.0 + lumAdj * fade);
        rgb = hsv2rgb(hsv);
    }

    // 4. Halation: glow from nearby highlights (12-tap ring blur of the highlight mask).
    if (halation > 0.0) {
        float r = frame.z * halationRadius;
        float glow = 0.0;
        for (int i = 0; i < 6; i++) {
            float a = float(i) * 1.0471976;
            glow += smoothstep(halationParams.w, 1.0, lumaAt(p + float2(cos(a), sin(a)) * r));
            glow += smoothstep(halationParams.w, 1.0,
                lumaAt(p + float2(cos(a + 0.5235988), sin(a + 0.5235988)) * r * 0.5));
        }
        rgb += halationParams.rgb * (glow / 12.0) * halation;
    }
    rgb = clamp(rgb, 0.0, 1.0);

    // 5. Tone curves.
    rgb = float3(
        lut.eval(float2(rgb.r * 255.0 + 0.5, 0.5)).r,
        lut.eval(float2(rgb.g * 255.0 + 0.5, 0.5)).g,
        lut.eval(float2(rgb.b * 255.0 + 0.5, 0.5)).b);

    // 6. Clarity (12-tap ring blur approximates the capture's blur map).
    if (clarity != 0.0) {
        float r = frame.z * clarityRadius;
        float blur = 0.0;
        for (int i = 0; i < 6; i++) {
            float a = float(i) * 1.0471976;
            blur += lumaAt(p + float2(cos(a), sin(a)) * r);
            blur += lumaAt(p + float2(cos(a + 0.5235988), sin(a + 0.5235988)) * r * 0.5);
        }
        float detail = luma(src) - blur / 12.0;
        float l = luma(rgb);
        rgb += clarity * detail * 4.0 * l * (1.0 - l);
    }

    // 7. Vignette.
    if (vignette > 0.0) {
        float2 center = frame.xy + frame.zw * 0.5;
        float radius = length(p - center) / (0.5 * length(frame.zw));
        rgb *= 1.0 - vignette * smoothstep(vignetteStart, 1.0, radius);
    }

    // 8. Grain.
    if (grainAmount > 0.0) {
        float l = clamp(luma(rgb), 0.0, 1.0);
        float amp = grainAmount * 4.0 * l * (1.0 - l);
        float n = noise01(p, float2(0.0, 0.0)) * 2.0 - 1.0;
        float3 g = float3(n);
        if (grainColor > 0.0) {
            float3 cn = float3(
                noise01(p, float2(17.0, 3.0)),
                noise01(p, float2(5.0, 29.0)),
                noise01(p, float2(41.0, 11.0))) * 2.0 - 1.0;
            g = mix(g, cn, grainColor);
        }
        rgb += g * amp;
    }
    return half4(half3(clamp(rgb, 0.0, 1.0)), c.a);
}
"""

/**
 * Live-preview shader. One instance is compiled per screen; [update] swaps the recipe without
 * recompiling (fast enough for dragging editor sliders), [effect] produces each frame's effect.
 */
@RequiresApi(33)
class FilmPreviewShader {
    private val shader = RuntimeShader(FILM_AGSL)

    init {
        // Constants that never change between recipes.
        shader.setFloatUniform("chromeParams", CHROME_LOW, CHROME_HIGH, CHROME_DESAT, CHROME_DEEPEN)
        shader.setFloatUniform("hslCenterA", BandCenters[0], BandCenters[1], BandCenters[2], BandCenters[3])
        shader.setFloatUniform("hslCenterB", BandCenters[4], BandCenters[5], BandCenters[6], BandCenters[7])
        shader.setFloatUniform("hslRightA", BandRight[0], BandRight[1], BandRight[2], BandRight[3])
        shader.setFloatUniform("hslRightB", BandRight[4], BandRight[5], BandRight[6], BandRight[7])
        shader.setFloatUniform("hslLeftA", BandLeft[0], BandLeft[1], BandLeft[2], BandLeft[3])
        shader.setFloatUniform("hslLeftB", BandLeft[4], BandLeft[5], BandLeft[6], BandLeft[7])
        shader.setFloatUniform("hslSatFade", HSL_SAT_FADE)
        shader.setFloatUniform("halationParams", HALATION_R, HALATION_G, HALATION_B, HALATION_THRESHOLD)
        shader.setFloatUniform("halationRadius", HALATION_RADIUS)
        shader.setFloatUniform("clarityRadius", CLARITY_RADIUS)
        shader.setFloatUniform("vignetteStart", VIGNETTE_START)
        shader.setFloatUniform("grainBoost", GRAIN_SMOOTH_BOOST)
        shader.setFloatUniform("seed", 0f)
        shader.setFloatUniform("frame", 0f, 0f, 1f, 1f)
        update(FilmRecipe(id = "neutral", label = ""))
    }

    fun update(recipe: FilmRecipe) {
        val a = recipe.colorMatrix.array
        // AGSL matrices are column-major.
        shader.setFloatUniform("cm", floatArrayOf(a[0], a[5], a[10], a[1], a[6], a[11], a[2], a[7], a[12]))
        val lut = recipe.lut
        val pixels = IntArray(256) { i ->
            (0xFF shl 24) or (lut[i] shl 16) or (lut[256 + i] shl 8) or lut[512 + i]
        }
        val lutBitmap = Bitmap.createBitmap(pixels, 256, 1, Bitmap.Config.ARGB_8888)
        shader.setInputShader("lut", BitmapShader(lutBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        shader.setFloatUniform("colorChrome", recipe.colorChrome)
        shader.setFloatUniform("hslOn", if (recipe.hasHsl) 1f else 0f)
        val hue = recipe.hslHue
        val sat = recipe.hslSat
        val lum = recipe.hslLum
        shader.setFloatUniform("hslHueA", hue[0], hue[1], hue[2], hue[3])
        shader.setFloatUniform("hslHueB", hue[4], hue[5], hue[6], hue[7])
        shader.setFloatUniform("hslSatA", sat[0], sat[1], sat[2], sat[3])
        shader.setFloatUniform("hslSatB", sat[4], sat[5], sat[6], sat[7])
        shader.setFloatUniform("hslLumA", lum[0], lum[1], lum[2], lum[3])
        shader.setFloatUniform("hslLumB", lum[4], lum[5], lum[6], lum[7])
        shader.setFloatUniform("halation", recipe.halation * HALATION_GAIN)
        shader.setFloatUniform("clarity", recipe.clarity * CLARITY_GAIN)
        shader.setFloatUniform("vignette", recipe.vignette * VIGNETTE_DEPTH)
        shader.setFloatUniform("grainAmount", recipe.grain)
        shader.setFloatUniform("grainCellFraction", GRAIN_CELL * recipe.grainSize)
        shader.setFloatUniform("grainColor", recipe.grainColor)
    }

    /**
     * Effect for the current frame. [frameLeft]..[frameHeight] locate the camera image inside
     * the preview view (it's letterboxed), so vignette, clarity and halation follow the image.
     */
    fun effect(seed: Float, frameLeft: Float, frameTop: Float, frameWidth: Float, frameHeight: Float): RenderEffect {
        shader.setFloatUniform("seed", seed)
        shader.setFloatUniform("frame", frameLeft, frameTop, frameWidth, frameHeight)
        return RenderEffect.createRuntimeShaderEffect(shader, "content")
    }
}

/** Android 12 fallback for the preview: colour matrix only (no curves, HSL or texture). */
@RequiresApi(31)
fun filmColorFilterEffect(recipe: FilmRecipe): RenderEffect =
    RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(recipe.colorMatrix))
