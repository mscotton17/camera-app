package com.example.cameraapp

import android.graphics.ColorMatrix
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ---------------------------------------------------------------------------------------------
// Constants shared by the CPU pass and the preview shader (keep the two in sync)
// ---------------------------------------------------------------------------------------------

internal const val LR = 0.2126f
internal const val LG = 0.7152f
internal const val LB = 0.0722f

/** Clarity blur radius at clarityRadius 1, as a fraction of image width. */
internal const val CLARITY_RADIUS = 0.01f
/** Detail gain at clarity = 1 (so ±0.5 doubles or halves local midtone contrast). */
internal const val CLARITY_GAIN = 2f
internal const val VIGNETTE_DEPTH = 0.75f
/** Colour chrome: chroma range over which it fades in, and how much it compresses/deepens. */
internal const val CHROME_LOW = 0.35f
internal const val CHROME_HIGH = 0.9f
internal const val CHROME_DESAT = 0.25f
internal const val CHROME_DEEPEN = 0.18f
/** Halation / bloom glow radius at radius 1, as a fraction of image width, and their gains. */
internal const val HALATION_RADIUS = 0.02f
internal const val HALATION_GAIN = 0.6f
internal const val BLOOM_RADIUS = 0.03f
internal const val BLOOM_GAIN = 0.5f
/** Local tone (highlight / shadow recovery) blur radius and strengths. */
internal const val LOCAL_RADIUS = 0.04f
internal const val HIGHLIGHT_RECOVERY_GAIN = 0.35f
internal const val SHADOW_RECOVERY_GAIN = 0.8f
/** Grain cell size at grainSize 1, as a fraction of image width. */
internal const val GRAIN_CELL = 1f / 2400f
/** Value noise averages neighbouring cells; this restores its contrast to match per-pixel grain. */
internal const val GRAIN_SMOOTH_BOOST = 1.4f
/** Grain: smallest cell in pixels (so it always forms clusters, never single-pixel static), the
 * fine octave's and clump envelope's scales relative to the cell, and the gain that restores
 * the strength lost by mixing octaves. */
internal const val GRAIN_MIN_CELL = 1.6f
internal const val GRAIN_FINE_SCALE = 0.55f
internal const val GRAIN_CLUMP_SCALE = 2.8f
internal const val GRAIN_CLUMP_NORM = 1.6f
/** Colour density: how far fully saturated colours (reds / oranges most) are darkened at colour chrome 1. */
internal const val DENSITY_GAIN = 0.22f
/** HSL changes fade in over this much saturation, so greys are never shifted. */
internal const val HSL_SAT_FADE = 0.25f
/** Lens effects at strength 1, as a fraction of image width at the corners. */
internal const val CA_SHIFT = 0.003f
internal const val CORNER_BLUR = 0.004f
/** Skin hue (turns) and half-width used by skin protection. */
internal const val SKIN_HUE = 25f / 360f
internal const val SKIN_WIDTH = 22f / 360f

/** Band centres (turns) and the distance to the next/previous band, for tent weighting. */
internal val BandCenters = FloatArray(8) { ColorBand.entries[it].hueDeg / 360f }
internal val BandRight = FloatArray(8) { i -> (BandCenters[(i + 1) % 8] + if (i == 7) 1f else 0f) - BandCenters[i] }
internal val BandLeft = FloatArray(8) { i -> BandCenters[i] - (BandCenters[(i + 7) % 8] - if (i == 0) 1f else 0f) }
internal const val BLUE_BAND = 5

internal fun smoothstep(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

internal fun fract(x: Float): Float = x - floor(x)

/** Weight of hue [h] (turns) in band [i]: 1 at its centre, 0 at the neighbouring bands. */
internal fun bandWeight(h: Float, i: Int): Float {
    val d = fract(h - BandCenters[i] + 0.5f) - 0.5f
    return max(0f, 1f - max(d / BandRight[i], -d / BandLeft[i]))
}

/**
 * Blue chrome's hue weight: the blue band tent, eased so cyans part-way to aqua get much less
 * (a cyan halfway to aqua gets ~0.26 instead of 0.5), which keeps sky gradients smooth.
 */
internal fun blueChromeHueWeight(h: Float): Float = smoothstep(0.25f, 1f, bandWeight(h, BLUE_BAND))

/** Fallback colour-grain tint: dye clouds read cool magenta in the shadows. */
private val DefaultGrainTint = Tint(0.2f, -0.45f, 0.25f)

/** A zero-sum colour direction scaled so its largest component is 1. */
private fun grainDirection(t: Tint, fallback: Tint): FloatArray {
    val src = if (abs(t.r) + abs(t.g) + abs(t.b) < 1e-4f) fallback else t
    val mean = (src.r + src.g + src.b) / 3f
    val v = floatArrayOf(src.r - mean, src.g - mean, src.b - mean)
    val m = max(abs(v[0]), max(abs(v[1]), abs(v[2]))).takeIf { it > 1e-6f } ?: return floatArrayOf(0f, 0f, 0f)
    return floatArrayOf(v[0] / m, v[1] / m, v[2] / m)
}

/** Colour-grain direction for the shadows (from shadowTint) and the mids (from midTint). */
internal fun grainShadowDirection(r: FilmRecipe): FloatArray = grainDirection(r.shadowTint, DefaultGrainTint)
internal fun grainMidDirection(r: FilmRecipe): FloatArray =
    grainDirection(r.midTint, Tint(grainShadowDirection(r)[0], grainShadowDirection(r)[1], grainShadowDirection(r)[2]))

/** How much colour grain shows at luminance [l]: full in shadows/mids, mostly clean highlights. */
internal fun colorGrainToneWeight(l: Float): Float = 1f - 0.8f * smoothstep(0.55f, 0.9f, l)

/** Grain weight by tone: bumps centred on shadows, mids and highlights. */
internal fun grainToneWeight(l: Float, r: FilmRecipe): Float {
    fun bump(c: Float, w: Float) = max(0f, 1f - ((l - c) / w).let { it * it })
    return r.grainShadows * bump(0.15f, 0.35f) + r.grainMids * bump(0.5f, 0.5f) + r.grainHighlights * bump(0.85f, 0.35f)
}

// ---------------------------------------------------------------------------------------------
// Colour matrix: temperature, gains, tint -> channel mixer -> hue rotation -> saturation / mono
// ---------------------------------------------------------------------------------------------

/** Approximate RGB of a black body at [kelvin] (Tanner Helland's fit), each 1..255. */
private fun blackbody(kelvin: Float): FloatArray {
    val t = kelvin / 100f
    val r = if (t <= 66f) 255f else 329.69873f * (t - 60f).pow(-0.13320476f)
    val g = if (t <= 66f) 99.4708f * ln(t) - 161.11957f else 288.12216f * (t - 60f).pow(-0.07551485f)
    val b = when {
        t >= 66f -> 255f
        t <= 19f -> 0f
        else -> 138.51773f * ln(t - 10f) - 305.04479f
    }
    return floatArrayOf(r.coerceIn(1f, 255f), g.coerceIn(1f, 255f), b.coerceIn(1f, 255f))
}

/**
 * White-balance gains for a temperature setting, like a raw editor's slider: higher kelvin
 * means warmer, lower means cooler, 6500K is neutral. Green stays at 1.
 */
internal fun temperatureGains(kelvin: Float): FloatArray {
    val ref = blackbody(6500f)
    val c = blackbody(kelvin.coerceIn(2000f, 15000f))
    val g = ref[1] / c[1]
    return floatArrayOf(ref[0] / c[0] / g, 1f, ref[2] / c[2] / g)
}

private fun mul(a: Array<FloatArray>, b: Array<FloatArray>): Array<FloatArray> =
    Array(3) { i -> FloatArray(3) { j -> a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j] } }

/** Hue rotation about the grey axis (the SVG hueRotate matrix). */
private fun hueRotation(degrees: Float): Array<FloatArray> {
    val rad = Math.toRadians(degrees.toDouble())
    val c = cos(rad).toFloat()
    val s = sin(rad).toFloat()
    return arrayOf(
        floatArrayOf(0.213f + c * 0.787f - s * 0.213f, 0.715f - c * 0.715f - s * 0.715f, 0.072f - c * 0.072f + s * 0.928f),
        floatArrayOf(0.213f - c * 0.213f + s * 0.143f, 0.715f + c * 0.285f + s * 0.140f, 0.072f - c * 0.072f - s * 0.283f),
        floatArrayOf(0.213f - c * 0.213f - s * 0.787f, 0.715f - c * 0.715f + s * 0.715f, 0.072f + c * 0.928f + s * 0.072f),
    )
}

internal fun buildColorMatrix(r: FilmRecipe): ColorMatrix {
    val tg = temperatureGains(r.temperature)
    val gains = floatArrayOf(r.redGain * tg[0], (1f - r.tint) * tg[1], r.blueGain * tg[2])
    val wb = Array(3) { i -> FloatArray(3) { j -> if (i == j) gains[i] else 0f } }
    var m = mul(r.mixer.rows(), wb)
    if (r.hueShift != 0f) m = mul(hueRotation(r.hueShift), m)
    val last: Array<FloatArray> = if (r.mono != null) {
        val (wr, wg, wb2) = r.mono
        val sum = (wr + wg + wb2).takeIf { it > 0f } ?: 1f
        Array(3) { floatArrayOf(wr / sum, wg / sum, wb2 / sum) }
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
    m = mul(last, m)
    val values = FloatArray(20)
    for (row in 0 until 3) for (col in 0 until 3) values[row * 5 + col] = m[row][col]
    values[18] = 1f // alpha passes through
    return ColorMatrix(values)
}

// ---------------------------------------------------------------------------------------------
// Tone: one 256-entry table per channel combining every per-channel tone setting
// ---------------------------------------------------------------------------------------------

internal fun buildLut(r: FilmRecipe): IntArray {
    val lut = IntArray(768)
    val xs5 = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
    val exposureGain = 2f.pow(r.exposure / 2.2f)
    val drStops = r.effectiveDrLift
    val masterCustom = customCurve(r.curve(CurveChannel.MASTER))
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
        val base = monotoneCubic(xs5, ys)
        val channelCustom = customCurve(r.curve(CurveChannel.entries[ch + 1]))
        for (i in 0 until 256) {
            var x = i / 255f
            if (drStops > 0.01f) x = dynamicRangeLift(x, drStops)
            x = (x * exposureGain).coerceIn(0f, 1f)
            x = toeShoulder(x, r.toe, r.shoulder)
            var y = base(x)
            y += r.contrast * (smoothstep(0f, 1f, y) - y)
            y = channelCustom(masterCustom(y.coerceIn(0f, 1f)))
            lut[ch * 256 + i] = (y * 255f).roundToInt().coerceIn(0, 255)
        }
    }
    return lut
}

/**
 * Undo a DR200/DR400 underexposure: brighten by 2^stops in linear light, then roll the
 * highlights off so the extra headroom isn't clipped (extended Reinhard, white = 2^stops).
 */
private fun dynamicRangeLift(x: Float, stops: Float): Float {
    val w = 2f.pow(stops)
    val l = x.pow(2.2f) * w
    val mapped = l * (1f + l / (w * w)) / (1f + l)
    return mapped.coerceIn(0f, 1f).pow(1f / 2.2f)
}

/** Film-style toe (gentler entry into black) and shoulder (gentler roll into white). */
private fun toeShoulder(x: Float, toe: Float, shoulder: Float): Float {
    var y = x
    if (shoulder > 0f) {
        val a = shoulder * 3f
        y = y * (1f + a) / (1f + a * y)
    }
    if (toe > 0f) {
        val b = toe * 3f
        val inv = 1f - y
        y = 1f - inv * (1f + b) / (1f + b * inv)
    }
    return y
}

/** A nine-point custom curve (offsets from the diagonal) as a smooth, rising function. */
private fun customCurve(offsets: List<Float>): (Float) -> Float {
    if (offsets.size != CURVE_POINTS || offsets.all { it == 0f }) return { it }
    val xs = FloatArray(CURVE_POINTS) { it / (CURVE_POINTS - 1f) }
    val ys = FloatArray(CURVE_POINTS) { (xs[it] + offsets[it]).coerceIn(0f, 1f) }
    for (i in 1 until ys.size) ys[i] = max(ys[i], ys[i - 1] + 0.0005f)
    val f = monotoneCubic(xs, ys)
    return { x -> f(x).coerceIn(0f, 1f) }
}

/** Fritsch–Carlson monotone cubic interpolation: smooth, with no overshoot between points. */
internal fun monotoneCubic(xs: FloatArray, ys: FloatArray): (Float) -> Float {
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
