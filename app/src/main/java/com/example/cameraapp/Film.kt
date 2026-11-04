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
 * Plus three in-camera "engine" controls:
 *  - [colorChrome]: compresses and deepens highly saturated colours so they don't clip.
 *  - [clarity]: midtone micro-contrast; negative = soft vintage glow, positive = punchy.
 *  - [vignette]: soft radial edge darkening.
 *
 * Processing order: colour matrix -> colour chrome -> tone curve -> clarity -> vignette -> grain.
 * Pillars 1 and 3 are linear and become a [ColorMatrix]; pillar 2 is a lookup table. The same
 * maths drives the live preview shader and the capture pass.
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
    /** Radial edge darkening, 0..1 (1 = corners at ~25% brightness), softly feathered. */
    val vignette: Float = 0f,
    /** Midtone micro-contrast, -0.5..+0.5: negative softens/glows, positive adds punch. */
    val clarity: Float = 0f,
    /** 0..1: compresses and deepens highly saturated colours so they don't clip. */
    val colorChrome: Float = 0f,
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
    // Gritty street look: punchy micro-contrast, dense colour that holds saturated signs and
    // clothing without clipping, cool shadows, a strong vignette. Exercises the engine controls.
    FilmRecipe(
        id = "street", label = "STREET",
        satR = 1.08f, satG = 0.88f, satB = 1.02f,
        shadows = -0.045f, highlights = 0.015f,
        shadowTint = Tint(r = -0.01f, b = 0.01f),
        grain = 0.035f, vignette = 0.35f, clarity = 0.35f, colorChrome = 0.7f,
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

// Engine-control tuning, shared by the CPU pass and the preview shader (keep in sync).
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

private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * Low-resolution blurred luminance (0..1) of the original image, for clarity. Built once per
 * photo at ~400px wide, so a small box blur equals a wide one at full resolution.
 */
private class BlurMap(source: Bitmap) {
    val w: Int = min(source.width, 400)
    val h: Int = max(1, (source.height * w / source.width.toFloat()).roundToInt())
    private val data = FloatArray(w * h)
    private val sx = (w - 1).toFloat() / max(1, source.width - 1)
    private val sy = (h - 1).toFloat() / max(1, source.height - 1)

    init {
        val small = Bitmap.createScaledBitmap(source, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== source) small.recycle()
        for (i in px.indices) {
            val c = px[i]
            data[i] = (LR * ((c shr 16) and 0xFF) + LG * ((c shr 8) and 0xFF) + LB * (c and 0xFF)) / 255f
        }
        val radius = max(1, (w * CLARITY_RADIUS).roundToInt())
        // Two box passes in each direction approximate a Gaussian.
        repeat(2) {
            boxBlur(data, w, h, radius, horizontal = true)
            boxBlur(data, w, h, radius, horizontal = false)
        }
    }

    /** Bilinear sample at full-resolution pixel (x, y). */
    fun sample(x: Int, y: Int): Float {
        val fx = x * sx
        val fy = y * sy
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

    private fun boxBlur(a: FloatArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val line = FloatArray(len)
        for (l in 0 until lines) {
            fun idx(i: Int) = if (horizontal) l * w + i else i * w + l
            for (i in 0 until len) line[i] = a[idx(i)]
            var sum = 0f
            for (i in -r..r) sum += line[i.coerceIn(0, len - 1)]
            for (i in 0 until len) {
                a[idx(i)] = sum / (2 * r + 1)
                sum += line[(i + r + 1).coerceAtMost(len - 1)] - line[(i - r).coerceAtLeast(0)]
            }
        }
    }
}

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
    // Clarity compares each pixel with its surroundings in the original image.
    val blurMap = if (recipe.clarity != 0f) BlurMap(bitmap) else null
    val clarity = recipe.clarity * CLARITY_GAIN
    val chrome = recipe.colorChrome
    val vignette = recipe.vignette * VIGNETTE_DEPTH
    val cx = (w - 1) / 2f
    val cy = (h - 1) / 2f
    val halfDiagonal = kotlin.math.sqrt(cx * cx + cy * cy).coerceAtLeast(1f)

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
                    // Pillars 1 + 3: colour matrix (kept unclamped so colour chrome sees the overshoot).
                    var fr = cm[0] * r + cm[1] * g + cm[2] * b + cm[4]
                    var fg = cm[5] * r + cm[6] * g + cm[7] * b + cm[9]
                    var fb = cm[10] * r + cm[11] * g + cm[12] * b + cm[14]
                    // Colour chrome: pull in and deepen highly saturated colours before they clip.
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
                    // Pillar 2: tone curves.
                    var outR = lut[fr.roundToInt().coerceIn(0, 255)].toFloat()
                    var outG = lut[256 + fg.roundToInt().coerceIn(0, 255)].toFloat()
                    var outB = lut[512 + fb.roundToInt().coerceIn(0, 255)].toFloat()
                    // Clarity: add (or remove) local detail, mostly in the midtones.
                    if (blurMap != null) {
                        val detail = (LR * r + LG * g + LB * b) / 255f - blurMap.sample(x, y)
                        val l = (LR * outR + LG * outG + LB * outB) / 255f
                        val d = clarity * detail * 4f * l * (1f - l) * 255f
                        outR += d; outG += d; outB += d
                    }
                    // Vignette: soft radial darkening toward the corners.
                    if (vignette > 0f) {
                        val dx = x - cx
                        val dy = y - cy
                        val radius = kotlin.math.sqrt(dx * dx + dy * dy) / halfDiagonal
                        val f = 1f - vignette * smoothstep(VIGNETTE_START, 1f, radius)
                        outR *= f; outG *= f; outB *= f
                    }
                    // Pillar 4: monochromatic grain, weighted toward the midtones.
                    if (grainLevels > 0f) {
                        val l = ((LR * outR + LG * outG + LB * outB) / 255f).coerceIn(0f, 1f)
                        val d = grainNoise(x / cell, y / cell, seed) * grainLevels * 4f * l * (1f - l)
                        outR += d; outG += d; outB += d
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
// Live preview: the same matrix + curves (+ grain) as a GPU shader
// ---------------------------------------------------------------------------------------------

private const val FILM_AGSL = """
uniform shader content;
uniform shader lut;
uniform float3x3 cm;
uniform float grainAmount;
uniform float seed;
uniform float vignette;     // already multiplied by VIGNETTE_DEPTH
uniform float vignetteStart;
uniform float clarity;      // already multiplied by CLARITY_GAIN
uniform float clarityRadius;
uniform float colorChrome;
uniform float4 chromeParams; // low, high, desat, deepen
uniform float4 frame;       // image rect inside the view: left, top, width, height (px)

float luma(float3 c) { return dot(c, float3(0.2126, 0.7152, 0.0722)); }

float hash(float2 p) {
    return fract(sin(dot(p, float2(12.9898, 78.233)) + seed) * 43758.5453);
}

float lumaAt(float2 p) {
    // Stay inside the image so the black bars don't darken the blur at the edges.
    float2 q = clamp(p, frame.xy, frame.xy + frame.zw - 1.0);
    return luma(float3(content.eval(q).rgb));
}

half4 main(float2 p) {
    half4 c = content.eval(p);
    float3 src = float3(c.rgb);
    // Leave the black letterbox bars alone.
    if (max(src.r, max(src.g, src.b)) < 0.004) return c;

    float3 rgb = cm * src;
    if (colorChrome > 0.0) {
        float chroma = max(rgb.r, max(rgb.g, rgb.b)) - min(rgb.r, min(rgb.g, rgb.b));
        float w = smoothstep(chromeParams.x, chromeParams.y, chroma) * colorChrome;
        float l = luma(rgb);
        rgb = (l + (rgb - l) * (1.0 - chromeParams.z * w)) * (1.0 - chromeParams.w * w);
    }
    rgb = clamp(rgb, 0.0, 1.0);
    rgb = float3(
        lut.eval(float2(rgb.r * 255.0 + 0.5, 0.5)).r,
        lut.eval(float2(rgb.g * 255.0 + 0.5, 0.5)).g,
        lut.eval(float2(rgb.b * 255.0 + 0.5, 0.5)).b);

    if (clarity != 0.0) {
        // 12-tap ring blur (radius and half radius) approximates the capture's blur map.
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

    if (vignette > 0.0) {
        float2 center = frame.xy + frame.zw * 0.5;
        float radius = length(p - center) / (0.5 * length(frame.zw));
        rgb *= 1.0 - vignette * smoothstep(vignetteStart, 1.0, radius);
    }

    if (grainAmount > 0.0) {
        float l = clamp(luma(rgb), 0.0, 1.0);
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
        shader.setFloatUniform("vignette", recipe.vignette * VIGNETTE_DEPTH)
        shader.setFloatUniform("vignetteStart", VIGNETTE_START)
        shader.setFloatUniform("clarity", recipe.clarity * CLARITY_GAIN)
        shader.setFloatUniform("clarityRadius", CLARITY_RADIUS)
        shader.setFloatUniform("colorChrome", recipe.colorChrome)
        shader.setFloatUniform("chromeParams", CHROME_LOW, CHROME_HIGH, CHROME_DESAT, CHROME_DEEPEN)
        shader.setFloatUniform("frame", 0f, 0f, 1f, 1f)
    }

    /**
     * Effect for the current frame. [frameLeft]..[frameHeight] locate the camera image inside
     * the preview view (it's letterboxed), so vignette and clarity follow the image.
     */
    fun effect(seed: Float, frameLeft: Float, frameTop: Float, frameWidth: Float, frameHeight: Float): RenderEffect {
        shader.setFloatUniform("seed", seed)
        shader.setFloatUniform("frame", frameLeft, frameTop, frameWidth, frameHeight)
        return RenderEffect.createRuntimeShaderEffect(shader, "content")
    }
}

/** Android 12 fallback for the preview: colour matrix only (no curves or grain). */
@RequiresApi(31)
fun filmColorFilterEffect(recipe: FilmRecipe): RenderEffect =
    RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(recipe.colorMatrix))
