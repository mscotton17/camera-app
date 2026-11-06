package com.example.cameraapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.concurrent.ForkJoinPool
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ---------------------------------------------------------------------------------------------
// Low-resolution helpers for clarity, local tone, halation and bloom
// ---------------------------------------------------------------------------------------------

/** The region's luminance (0..1) at ~400px wide, shared by the blur maps. */
private class LowResLuma(bitmap: Bitmap, rect: Rect) {
    val w: Int = min(rect.width(), 400)
    val h: Int = max(1, (rect.height() * w / rect.width().toFloat()).roundToInt())
    val luma = FloatArray(w * h)
    val sx = (w - 1).toFloat() / max(1, rect.width() - 1)
    val sy = (h - 1).toFloat() / max(1, rect.height() - 1)

    init {
        val rw = rect.width()
        val row = IntArray(rw)
        val step = rw / w.toFloat()
        for (ly in 0 until h) {
            val y = rect.top + ((ly + 0.5f) * rect.height() / h).toInt().coerceIn(0, rect.height() - 1)
            bitmap.getPixels(row, 0, rw, rect.left, y, rw, 1)
            for (lx in 0 until w) {
                // Average a few columns across this cell.
                var sum = 0f
                for (k in 0 until 4) {
                    val c = row[((lx + (k + 0.5f) / 4f) * step).toInt().coerceIn(0, rw - 1)]
                    sum += LR * ((c shr 16) and 0xFF) + LG * ((c shr 8) and 0xFF) + LB * (c and 0xFF)
                }
                luma[ly * w + lx] = sum / 4f / 255f
            }
        }
    }
}

/** A blurred map of [transform] applied to the region's luminance, sampled at full resolution. */
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
// Noise
// ---------------------------------------------------------------------------------------------

/** Integer hash -> [-1, 1]. */
internal fun hashNoise(x: Int, y: Int, seed: Int): Float {
    var h = x * 374761393 + y * 668265263 + seed * 1442695041
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0xFFFF) / 32767.5f - 1f
}

private fun hash01(x: Int, y: Int, seed: Int): Float = (hashNoise(x, y, seed) + 1f) * 0.5f

/** Smooth value noise in [-1, 1] ([x], [y] in cell units). */
private fun valueNoise(x: Float, y: Float, seed: Int): Float {
    val x0 = floor(x).toInt()
    val y0 = floor(y).toInt()
    var tx = x - x0
    var ty = y - y0
    tx = tx * tx * (3f - 2f * tx)
    ty = ty * ty * (3f - 2f * ty)
    val a = hashNoise(x0, y0, seed)
    val b = hashNoise(x0 + 1, y0, seed)
    val c = hashNoise(x0, y0 + 1, seed)
    val d = hashNoise(x0 + 1, y0 + 1, seed)
    val v = (a + (b - a) * tx) * (1 - ty) + (c + (d - c) * tx) * ty
    return (v * GRAIN_SMOOTH_BOOST).coerceIn(-1f, 1f)
}

// ---------------------------------------------------------------------------------------------
// Colour stage (same formulas as the shader)
// ---------------------------------------------------------------------------------------------

/** RGB (0..1) -> HSV into [out] (hue in turns). */
private fun rgbToHsv(r: Float, g: Float, b: Float, out: FloatArray) {
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    val d = mx - mn
    out[0] = when {
        d <= 0f -> 0f
        mx == r -> fract(((g - b) / d) / 6f)
        mx == g -> ((b - r) / d + 2f) / 6f
        else -> ((r - g) / d + 4f) / 6f
    }
    out[1] = if (mx > 0f) d / mx else 0f
    out[2] = mx
}

private fun hsvToRgb(h: Float, s: Float, v: Float, out: FloatArray) {
    fun channel(k: Float): Float {
        val p = abs(fract(h + k) * 6f - 3f)
        return v * (1f + s * ((p - 1f).coerceIn(0f, 1f) - 1f))
    }
    out[0] = channel(1f)
    out[1] = channel(2f / 3f)
    out[2] = channel(1f / 3f)
}

/**
 * Step 3: vibrance, highlight/shadow saturation, blue chrome, HSL bands and skin protection.
 * [rgb] (0..1) is modified in place; [orig] is the pixel before the recipe, for skin detection;
 * [chromeWeight] is how strongly colour chrome (step 2) already acted on this pixel.
 */
private fun colorStage(rgb: FloatArray, orig: FloatArray, r: FilmRecipe, hsv: FloatArray, chromeWeight: Float) {
    rgbToHsv(rgb[0], rgb[1], rgb[2], hsv)
    var h = hsv[0]
    var s = hsv[1]
    var v = hsv[2]
    if (r.vibrance != 0f) s = (s * (1f + r.vibrance * (1f - s))).coerceIn(0f, 1f)
    if (r.highlightSat != 0f || r.shadowSat != 0f) {
        val k = 1f + r.highlightSat * smoothstep(0.55f, 1f, v) + r.shadowSat * (1f - smoothstep(0f, 0.45f, v))
        s = (s * k).coerceIn(0f, 1f)
    }
    if (r.colorChromeBlue > 0f) {
        // Only does what colour chrome hasn't already done to this pixel, so the two never
        // stack past blue chrome's own maximum, and eases off toward cyan.
        val w = blueChromeHueWeight(h) * smoothstep(0.35f, 0.8f, s) * r.colorChromeBlue * (1f - chromeWeight)
        s *= 1f - 0.2f * w
        v *= 1f - 0.22f * w
    }
    if (r.hasHsl) {
        var hueShift = 0f
        var satAdj = 0f
        var lumAdj = 0f
        for (i in 0 until 8) {
            val w = bandWeight(h, i)
            if (w > 0f) {
                hueShift += w * r.hslHue[i]
                satAdj += w * r.hslSat[i]
                lumAdj += w * r.hslLum[i]
            }
        }
        val fade = smoothstep(0f, HSL_SAT_FADE, s)
        h = fract(h + hueShift * fade)
        s = (s * (1f + satAdj)).coerceIn(0f, 1f)
        v *= 1f + lumAdj * fade
    }
    if (r.colorChrome > 0f) {
        // Colour density: deep, saturated colours get darker in proportion to their saturation,
        // reds and oranges most, so they read rich and dense like dye rather than glowing.
        val hueBias = 0.4f + 0.6f * max(bandWeight(h, 0), bandWeight(h, 1))
        v *= 1f - DENSITY_GAIN * r.colorChrome * s * smoothstep(0.2f, 0.9f, s) * hueBias
    }
    hsvToRgb(h, s, v, rgb)
    if (r.skinProtect > 0f) {
        rgbToHsv(orig[0], orig[1], orig[2], hsv)
        val d = abs(fract(hsv[0] - SKIN_HUE + 0.5f) - 0.5f)
        val w = max(0f, 1f - d / SKIN_WIDTH) *
            smoothstep(0.1f, 0.2f, hsv[1]) * (1f - smoothstep(0.65f, 0.85f, hsv[1])) *
            smoothstep(0.15f, 0.3f, hsv[2]) * r.skinProtect * 0.85f
        for (c in 0 until 3) rgb[c] += (orig[c] - rgb[c]) * w
    }
}

/** Bleach bypass for one channel [v] (0..255) of a pixel with luminance [l] (0..1). */
private fun bleach(v: Float, l: Float, amount: Float): Float {
    val bv = (v / 255f).coerceIn(0f, 1f)
    val o = if (bv < 0.5f) 2f * bv * l else 1f - 2f * (1f - bv) * (1f - l)
    val mixed = bv + (o - bv) * amount
    return (mixed + (l - mixed) * amount * 0.35f) * 255f
}

// ---------------------------------------------------------------------------------------------
// The fused per-pixel pass
// ---------------------------------------------------------------------------------------------

/**
 * Applies [recipe] to [rect] of a mutable ARGB_8888 [bitmap] in place, using all CPU cores.
 * [cube] is the recipe's 3D LUT, if it has one.
 */
fun applyRecipe(bitmap: Bitmap, recipe: FilmRecipe, rect: Rect = Rect(0, 0, bitmap.width, bitmap.height), cube: CubeLut? = null) {
    val rw = rect.width()
    val rh = rect.height()
    if (rw <= 0 || rh <= 0) return
    val cm = recipe.colorMatrix.array
    val lut = recipe.lut
    val seed = (System.nanoTime() and 0x7FFFFFFF).toInt()

    // Blur maps from the original image.
    val needsBase = recipe.clarity != 0f || recipe.halation > 0f || recipe.bloom > 0f || recipe.hasLocalTone
    val base = if (needsBase) LowResLuma(bitmap, rect) else null
    val clarityMap = if (base != null && recipe.clarity != 0f) {
        BlurMap(base, CLARITY_RADIUS * recipe.clarityRadius) { it }
    } else null
    val localMap = if (base != null && recipe.hasLocalTone) BlurMap(base, LOCAL_RADIUS) { it } else null
    val haloMap = if (base != null && recipe.halation > 0f) {
        BlurMap(base, HALATION_RADIUS * recipe.halationRadius) { smoothstep(recipe.halationThreshold, 1f, it) }
    } else null
    val bloomMap = if (base != null && recipe.bloom > 0f) {
        BlurMap(base, BLOOM_RADIUS * recipe.bloomRadius) { smoothstep(recipe.bloomThreshold, 1f, it) }
    } else null

    // Lens effects read neighbouring pixels, so they read from an untouched copy of the rows
    // around the strip being processed: rows [srcTop, srcTop + srcRows). Copying only a strip
    // (not the whole image, ~200 MB at 50 MP) keeps memory small at high resolutions.
    val hasOptics = recipe.hasOptics
    var src = IntArray(0)
    var srcTop = 0
    var srcRows = 0

    val clarity = recipe.clarity * CLARITY_GAIN
    val halation = recipe.halation * HALATION_GAIN
    val bloom = recipe.bloom * BLOOM_GAIN
    val chrome = recipe.colorChrome
    val hasColorStage = recipe.hasColorStage
    val cx = (rw - 1) / 2f
    val cy = (rh - 1) / 2f
    val halfDiagonal = sqrt(cx * cx + cy * cy).coerceAtLeast(1f)
    val vignette = recipe.vignette * VIGNETTE_DEPTH
    val vignetteStart = 1f - recipe.vignetteFeather.coerceIn(0.05f, 1f)
    val grainLevels = recipe.grain * 255f
    val grainCell = max(GRAIN_MIN_CELL, rw * GRAIN_CELL * recipe.grainSize)
    val desatAmount = recipe.highlightDesatAmount.coerceIn(0f, 1f)
    val desatStart = recipe.highlightDesatStart
    val desatEnd = max(desatStart + 0.02f, recipe.whitePoint)
    val grainShadow = grainShadowDirection(recipe)
    val grainMid = grainMidDirection(recipe)
    val leakAngle = Math.toRadians(recipe.lightLeakAngle.toDouble())
    // The leak's centre sits just beyond the frame edge in the chosen direction.
    val leakX = cos(leakAngle).toFloat() * (cx / halfDiagonal) * 1.15f
    val leakY = sin(leakAngle).toFloat() * (cy / halfDiagonal) * 1.15f
    val leakSize = recipe.lightLeakSize.coerceIn(0.05f, 1.5f)
    val dustCell = max(8f, rw / 40f)
    val scratchCell = max(8f, rw / 60f)
    val speckScale = rw / 900f
    val scratchWidth = max(1f, rw / 2500f)
    val caShift = recipe.chromaticAberration * CA_SHIFT * rw
    val cornerBlur = recipe.cornerSoftness * CORNER_BLUR * rw

    /** Original pixel at ([x], [y]) from the current strip copy (clamped to it). */
    fun srcPixel(x: Int, y: Int): Int =
        src[(y.coerceIn(srcTop, srcTop + srcRows - 1) - srcTop) * rw + x.coerceIn(0, rw - 1)]

    fun srcAt(fx: Float, fy: Float, out: FloatArray) {
        val x0 = floor(fx).toInt().coerceIn(0, rw - 1)
        val y0 = floor(fy).toInt()
        val x1 = min(x0 + 1, rw - 1)
        val tx = (fx - floor(fx)).coerceIn(0f, 1f)
        val ty = (fy - y0).coerceIn(0f, 1f)
        val a = srcPixel(x0, y0); val b = srcPixel(x1, y0); val c = srcPixel(x0, y0 + 1); val d = srcPixel(x1, y0 + 1)
        for (k in 0 until 3) {
            val sh = 16 - k * 8
            val va = ((a shr sh) and 0xFF).toFloat(); val vb = ((b shr sh) and 0xFF).toFloat()
            val vc = ((c shr sh) and 0xFF).toFloat(); val vd = ((d shr sh) and 0xFF).toFloat()
            out[k] = (va + (vb - va) * tx) * (1 - ty) + (vc + (vd - vc) * tx) * ty
        }
    }

    /** Source sample with corner blur of radius [blurR] (a 9-tap ring) when it's large enough. */
    fun lensSample(fx: Float, fy: Float, blurR: Float, out: FloatArray, acc: FloatArray) {
        srcAt(fx, fy, out)
        if (blurR < 0.5f) return
        acc[0] = out[0]; acc[1] = out[1]; acc[2] = out[2]
        for (i in 0 until 8) {
            val a = i * 0.7853982f
            srcAt(fx + cos(a) * blurR, fy + sin(a) * blurR, out)
            acc[0] += out[0]; acc[1] += out[1]; acc[2] += out[2]
        }
        out[0] = acc[0] / 9f; out[1] = acc[1] / 9f; out[2] = acc[2] / 9f
    }

    /**
     * Grain value in [-1, 1], formed in small organic clusters: two octaves of smooth noise (the
     * grain and finer sub-grains) shaped by a larger clump envelope, so grains bunch together
     * the way silver halide does. Roughness mixes in some sharp per-pixel grit within the clumps.
     */
    fun grainAt(x: Int, y: Int, s: Int): Float {
        val fx = x / grainCell
        val fy = y / grainCell
        val coarse = valueNoise(fx, fy, s)
        val fine = valueNoise(fx / GRAIN_FINE_SCALE + 17.3f, fy / GRAIN_FINE_SCALE + 5.1f, s + 7)
        val clump = valueNoise(fx / GRAIN_CLUMP_SCALE + 3.7f, fy / GRAIN_CLUMP_SCALE + 9.2f, s + 13)
        val env = 0.35f + 0.65f * (clump * 0.5f + 0.5f)
        var n = (0.6f * coarse + 0.4f * fine) * env * GRAIN_CLUMP_NORM
        if (recipe.grainRoughness > 0f) n += (hashNoise(x, y, s) * env - n) * recipe.grainRoughness * 0.5f
        return n.coerceIn(-1f, 1f)
    }

    val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
    val pool = ForkJoinPool.commonPool()

    /** Processes rows [yStart, yEnd) across all cores. */
    fun processRows(yStart: Int, yEnd: Int) {
    val band = (yEnd - yStart + threads - 1) / threads
    (0 until threads).map { t ->
        pool.submit(Runnable {
            val row = IntArray(rw)
            val px = FloatArray(3)
            val orig = FloatArray(3)
            val hsv = FloatArray(3)
            val tmp = FloatArray(3)
            val acc = FloatArray(3)
            val start = yStart + t * band
            val end = min(yEnd, start + band)
            for (y in start until end) {
                bitmap.getPixels(row, 0, rw, rect.left, rect.top + y, rw, 1)
                for (x in 0 until rw) {
                    val c = row[x]
                    var r: Float
                    var g: Float
                    var b: Float
                    val dx = x - cx
                    val dy = y - cy
                    val dist = sqrt(dx * dx + dy * dy)
                    val rn = dist / halfDiagonal

                    // Lens: corner softness, chromatic aberration and sharpening.
                    if (hasOptics) {
                        val r2 = rn * rn
                        val blurR = cornerBlur * r2
                        lensSample(x.toFloat(), y.toFloat(), blurR, px, acc)
                        r = px[0]; g = px[1]; b = px[2]
                        if (caShift > 0f && dist > 0f) {
                            val shift = caShift * r2
                            val ux = dx / dist * shift
                            val uy = dy / dist * shift
                            lensSample(x + ux, y + uy, blurR, tmp, acc); r = tmp[0]
                            lensSample(x - ux, y - uy, blurR, tmp, acc); b = tmp[2]
                        }
                        if (recipe.sharpen != 0f) {
                            val n = srcPixel(x, max(0, y - 1))
                            val so = srcPixel(x, min(rh - 1, y + 1))
                            val e = srcPixel(x + 1, y)
                            val w = srcPixel(x - 1, y)
                            val sc = srcPixel(x, y)
                            val k = recipe.sharpen * 1.2f
                            val avgR = (((n shr 16) and 0xFF) + ((so shr 16) and 0xFF) + ((e shr 16) and 0xFF) + ((w shr 16) and 0xFF)) / 4f
                            val avgG = (((n shr 8) and 0xFF) + ((so shr 8) and 0xFF) + ((e shr 8) and 0xFF) + ((w shr 8) and 0xFF)) / 4f
                            val avgB = ((n and 0xFF) + (so and 0xFF) + (e and 0xFF) + (w and 0xFF)) / 4f
                            r += k * (((sc shr 16) and 0xFF) - avgR)
                            g += k * (((sc shr 8) and 0xFF) - avgG)
                            b += k * ((sc and 0xFF) - avgB)
                        }
                    } else {
                        r = ((c shr 16) and 0xFF).toFloat()
                        g = ((c shr 8) and 0xFF).toFloat()
                        b = (c and 0xFF).toFloat()
                    }
                    val srcLuma = (LR * r + LG * g + LB * b) / 255f

                    // 1. Colour matrix (kept unclamped so colour chrome sees the overshoot).
                    var fr = cm[0] * r + cm[1] * g + cm[2] * b + cm[4]
                    var fg = cm[5] * r + cm[6] * g + cm[7] * b + cm[9]
                    var fb = cm[10] * r + cm[11] * g + cm[12] * b + cm[14]
                    // 2. Colour chrome.
                    var chromeW = 0f
                    if (chrome > 0f) {
                        val chroma = (max(fr, max(fg, fb)) - min(fr, min(fg, fb))) / 255f
                        val wgt = smoothstep(CHROME_LOW, CHROME_HIGH, chroma) * chrome
                        chromeW = wgt
                        if (wgt > 0f) {
                            val l = LR * fr + LG * fg + LB * fb
                            val keep = 1f - CHROME_DESAT * wgt
                            val deepen = 1f - CHROME_DEEPEN * wgt
                            fr = (l + (fr - l) * keep) * deepen
                            fg = (l + (fg - l) * keep) * deepen
                            fb = (l + (fb - l) * keep) * deepen
                        }
                    }
                    // 3. Colour stage.
                    if (hasColorStage) {
                        px[0] = (fr / 255f).coerceIn(0f, 1f); px[1] = (fg / 255f).coerceIn(0f, 1f); px[2] = (fb / 255f).coerceIn(0f, 1f)
                        orig[0] = (r / 255f).coerceIn(0f, 1f); orig[1] = (g / 255f).coerceIn(0f, 1f); orig[2] = (b / 255f).coerceIn(0f, 1f)
                        colorStage(px, orig, recipe, hsv, chromeW)
                        fr = px[0] * 255f; fg = px[1] * 255f; fb = px[2] * 255f
                    }
                    // 4. Local tone: lift dark areas, hold back bright ones.
                    if (localMap != null) {
                        val bl = localMap.sample(x, y)
                        val gain = (1f + recipe.shadowRecovery * SHADOW_RECOVERY_GAIN * (1f - smoothstep(0.05f, 0.45f, bl))) *
                            (1f - recipe.highlightRecovery * HIGHLIGHT_RECOVERY_GAIN * smoothstep(0.55f, 0.95f, bl))
                        fr *= gain; fg *= gain; fb *= gain
                    }
                    // 5. Halation (coloured glow) and bloom (soft white glow).
                    if (haloMap != null) {
                        val glow = haloMap.sample(x, y) * halation * 255f
                        fr += recipe.halationColor.r * glow; fg += recipe.halationColor.g * glow; fb += recipe.halationColor.b * glow
                    }
                    if (bloomMap != null) {
                        val glow = (bloomMap.sample(x, y) * bloom).coerceIn(0f, 1f)
                        fr = 255f - (255f - fr.coerceIn(0f, 255f)) * (1f - glow) + recipe.bloom * 0.03f * (255f - fr)
                        fg = 255f - (255f - fg.coerceIn(0f, 255f)) * (1f - glow) + recipe.bloom * 0.03f * (255f - fg)
                        fb = 255f - (255f - fb.coerceIn(0f, 255f)) * (1f - glow) + recipe.bloom * 0.03f * (255f - fb)
                    }
                    // 6. Tone curves.
                    var outR = lut[fr.roundToInt().coerceIn(0, 255)].toFloat()
                    var outG = lut[256 + fg.roundToInt().coerceIn(0, 255)].toFloat()
                    var outB = lut[512 + fb.roundToInt().coerceIn(0, 255)].toFloat()
                    // 6b. Highlight saturation roll-off: ease colours toward neutral white as they
                    // approach the white point, instead of clipping one channel at a time.
                    if (desatAmount > 0f) {
                        val l = (LR * outR + LG * outG + LB * outB) / 255f
                        val w = smoothstep(desatStart, desatEnd, l) * desatAmount
                        if (w > 0f) {
                            val n = l * 255f
                            outR += (n - outR) * w; outG += (n - outG) * w; outB += (n - outB) * w
                        }
                    }
                    // 7. Bleach bypass: overlay a black-and-white copy, then pull saturation down.
                    if (recipe.bleachBypass > 0f) {
                        val l = (LR * outR + LG * outG + LB * outB) / 255f
                        outR = bleach(outR, l, recipe.bleachBypass)
                        outG = bleach(outG, l, recipe.bleachBypass)
                        outB = bleach(outB, l, recipe.bleachBypass)
                    }
                    // 8. 3D LUT.
                    if (cube != null && recipe.lutStrength > 0f) {
                        cube.sample(outR / 255f, outG / 255f, outB / 255f, tmp)
                        val k = recipe.lutStrength
                        outR += (tmp[0] * 255f - outR) * k
                        outG += (tmp[1] * 255f - outG) * k
                        outB += (tmp[2] * 255f - outB) * k
                    }
                    // 9. Clarity.
                    if (clarityMap != null) {
                        val detail = srcLuma - clarityMap.sample(x, y)
                        val l = ((LR * outR + LG * outG + LB * outB) / 255f).coerceIn(0f, 1f)
                        val d = clarity * detail * 4f * l * (1f - l) * 255f
                        outR += d; outG += d; outB += d
                    }
                    // 10. Light leak (screen blend of a warm glow from beyond one edge).
                    if (recipe.lightLeak > 0f) {
                        val u = dx / halfDiagonal - leakX
                        val v = dy / halfDiagonal - leakY
                        val k = recipe.lightLeak * exp(-(u * u + v * v) / (leakSize * leakSize))
                        outR = 255f - (255f - outR.coerceIn(0f, 255f)) * (1f - recipe.lightLeakColor.r * k)
                        outG = 255f - (255f - outG.coerceIn(0f, 255f)) * (1f - recipe.lightLeakColor.g * k)
                        outB = 255f - (255f - outB.coerceIn(0f, 255f)) * (1f - recipe.lightLeakColor.b * k)
                    }
                    // 11. Vignette: round, or following the frame's shape; darkens toward a colour.
                    if (vignette > 0f) {
                        val ellipse = sqrt((dx / max(1f, cx)).pow(2) + (dy / max(1f, cy)).pow(2)) / 1.4142135f
                        val shape = ellipse + (rn - ellipse) * recipe.vignetteRoundness
                        val m = vignette * smoothstep(vignetteStart, 1f, shape)
                        outR += (outR * recipe.vignetteColor.r - outR) * m
                        outG += (outG * recipe.vignetteColor.g - outG) * m
                        outB += (outB * recipe.vignetteColor.b - outB) * m
                    }
                    // 12. Dust (dark specks) and scratches (thin light lines).
                    if (recipe.dust > 0f) {
                        val ix = floor(x / dustCell).toInt()
                        val iy = floor(y / dustCell).toInt()
                        if (hash01(ix, iy, seed + 11) < recipe.dust * 0.12f) {
                            val sx = (ix + hash01(ix, iy, seed + 12)) * dustCell
                            val sy = (iy + hash01(ix, iy, seed + 13)) * dustCell
                            val rad = (0.4f + 1.6f * hash01(ix, iy, seed + 14)) * speckScale
                            val dd = sqrt((x - sx).pow(2) + (y - sy).pow(2))
                            val m = 1f - smoothstep(rad * 0.4f, rad, dd)
                            if (m > 0f) { val k = 1f - 0.75f * m; outR *= k; outG *= k; outB *= k }
                        }
                    }
                    if (recipe.scratches > 0f) {
                        val col = floor(x / scratchCell).toInt()
                        if (hash01(col, 0, seed + 21) < recipe.scratches * 0.08f) {
                            val lx = (col + hash01(col, 1, seed + 22)) * scratchCell
                            val m = 1f - smoothstep(scratchWidth * 0.5f, scratchWidth, abs(x - lx))
                            // Fade in and out along the length so lines don't look ruled.
                            val along = 0.5f + 0.5f * sin(y / rh.toFloat() * 6.2831855f * (1f + hash01(col, 2, seed + 23)) + col)
                            val k = 0.35f * m * along
                            outR += (255f - outR) * k; outG += (255f - outG) * k; outB += (255f - outB) * k
                        }
                    }
                    // 13. Grain: smooth/rough mix, weighted by tone, optionally per-channel.
                    if (grainLevels > 0f) {
                        val l = ((LR * outR + LG * outG + LB * outB) / 255f).coerceIn(0f, 1f)
                        val amp = grainLevels * grainToneWeight(l, recipe)
                        if (amp > 0f) {
                            val n = grainAt(x, y, seed)
                            if (recipe.grainColor > 0f) {
                                // Colour part: random per-channel noise blended with noise along
                                // the recipe's tint direction (dye clouds), fading in highlights.
                                val ws = 1f - smoothstep(0.2f, 0.6f, l)
                                val dr = grainShadow[0] * ws + grainMid[0] * (1f - ws)
                                val dg = grainShadow[1] * ws + grainMid[1] * (1f - ws)
                                val db = grainShadow[2] * ws + grainMid[2] * (1f - ws)
                                val tinted = grainAt(x, y, seed + 4)
                                val bias = recipe.grainColorBias
                                val k = recipe.grainColor * colorGrainToneWeight(l)
                                outR += amp * (n + ((grainAt(x, y, seed + 1) - n) * (1f - bias) + tinted * dr * bias) * k)
                                outG += amp * (n + ((grainAt(x, y, seed + 2) - n) * (1f - bias) + tinted * dg * bias) * k)
                                outB += amp * (n + ((grainAt(x, y, seed + 3) - n) * (1f - bias) + tinted * db * bias) * k)
                            } else {
                                outR += amp * n; outG += amp * n; outB += amp * n
                            }
                        }
                    }
                    val pr = outR.roundToInt().coerceIn(0, 255)
                    val pg = outG.roundToInt().coerceIn(0, 255)
                    val pb = outB.roundToInt().coerceIn(0, 255)
                    row[x] = (c and 0xFF000000.toInt()) or (pr shl 16) or (pg shl 8) or pb
                }
                bitmap.setPixels(row, 0, rw, rect.left, rect.top + y, rw, 1)
            }
        })
    }.forEach { it.get() }
    }

    if (!hasOptics) {
        processRows(0, rh)
    } else {
        // Strip by strip: each strip's source copy includes enough original rows above
        // (kept from the previous strip, since those are already overwritten) and below.
        val halo = (cornerBlur + caShift).toInt() + 3
        val strip = 256
        var tail = IntArray(0)
        var tailRows = 0
        var y0 = 0
        while (y0 < rh) {
            val y1 = min(rh, y0 + strip)
            val top = y0 - tailRows
            val bottom = min(rh, y1 + halo)
            val buf = IntArray((bottom - top) * rw)
            System.arraycopy(tail, 0, buf, 0, tailRows * rw)
            bitmap.getPixels(buf, tailRows * rw, rw, rect.left, rect.top + y0, rw, bottom - y0)
            src = buf
            srcTop = top
            srcRows = bottom - top
            processRows(y0, y1)
            tailRows = min(halo, y1 - top)
            tail = buf.copyOfRange((y1 - tailRows - top) * rw, (y1 - top) * rw)
            y0 = y1
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Output: crop, border, date stamp
// ---------------------------------------------------------------------------------------------

/** The centred crop of a [w]x[h] image for [aspect] (long:short, so it follows the image's orientation). */
fun cropRect(w: Int, h: Int, aspect: AspectRatio): Rect {
    if (aspect == AspectRatio.NATIVE || w <= 0 || h <= 0) return Rect(0, 0, w, h)
    val landscape = w >= h
    val long = if (landscape) w else h
    val short = if (landscape) h else w
    val current = long / short.toFloat()
    val (newLong, newShort) = if (current > aspect.ratio) {
        (short * aspect.ratio).roundToInt() to short
    } else {
        long to (long / aspect.ratio).roundToInt()
    }
    val cw = if (landscape) newLong else newShort
    val ch = if (landscape) newShort else newLong
    val left = (w - cw) / 2
    val top = (h - ch) / 2
    return Rect(left, top, left + cw, top + ch)
}

/** Border thickness on each side (left, top, right, bottom) for an image of [w]x[h]. */
fun borderInsets(recipe: FilmRecipe, w: Int, h: Int): RectF {
    val b = recipe.borderWidth * min(w, h)
    return when (recipe.border) {
        BorderStyle.NONE -> RectF(0f, 0f, 0f, 0f)
        BorderStyle.THIN, BorderStyle.FILED -> RectF(b, b, b, b)
        BorderStyle.INSTANT -> RectF(b * 1.4f, b * 1.4f, b * 1.4f, b * 4.5f)
        BorderStyle.REBATE -> if (w >= h) RectF(b * 0.6f, b * 3f, b * 0.6f, b * 3f) else RectF(b * 3f, b * 0.6f, b * 3f, b * 0.6f)
    }
}

/**
 * Crops [bitmap] (mutable, already upright) to the recipe's aspect, applies the recipe, then
 * adds the border and date stamp. Returns the finished image (may be [bitmap] itself).
 */
fun renderRecipe(context: Context, bitmap: Bitmap, recipe: FilmRecipe, timeMillis: Long): Bitmap {
    val crop = cropRect(bitmap.width, bitmap.height, recipe.aspect)
    applyRecipe(bitmap, recipe, crop, CubeLuts.load(context, recipe.lutName))

    val cw = crop.width()
    val ch = crop.height()
    val needsCanvas = crop.width() != bitmap.width || crop.height() != bitmap.height || recipe.border != BorderStyle.NONE
    val out: Bitmap
    val image: RectF
    if (needsCanvas) {
        val ins = borderInsets(recipe, cw, ch)
        out = Bitmap.createBitmap(
            (cw + ins.left + ins.right).roundToInt(),
            (ch + ins.top + ins.bottom).roundToInt(),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(out)
        val borderArgb = if (recipe.border == BorderStyle.REBATE) 0xFF0C0C0C.toInt() else recipe.borderColor.argb
        canvas.drawColor(borderArgb)
        image = RectF(ins.left, ins.top, ins.left + cw, ins.top + ch)
        canvas.drawBitmap(bitmap, crop, image, Paint(Paint.FILTER_BITMAP_FLAG))
        drawBorderDetails(canvas, recipe, image, out.width, out.height, borderArgb)
    } else {
        out = bitmap
        image = RectF(0f, 0f, cw.toFloat(), ch.toFloat())
    }
    if (recipe.dateStamp) drawDateStamp(Canvas(out), recipe, image, timeMillis)
    return out
}

internal fun drawBorderDetails(canvas: Canvas, recipe: FilmRecipe, image: RectF, w: Int, h: Int, borderArgb: Int) {
    val short = min(image.width(), image.height())
    val b = recipe.borderWidth * short
    when (recipe.border) {
        BorderStyle.FILED -> {
            // Irregular "filed-out" negative carrier edge: random bites into the image edge.
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = borderArgb }
            val rnd = Random(image.hashCode().toLong())
            fun bites(len: Float, place: (Float, Float, Float) -> RectF) {
                var p = 0f
                while (p < len) {
                    val seg = b * (0.3f + rnd.nextFloat() * 1.2f)
                    val depth = b * rnd.nextFloat() * 0.35f
                    canvas.drawRect(place(p, seg, depth), paint)
                    p += seg
                }
            }
            bites(image.width()) { p, s, d -> RectF(image.left + p, image.top, image.left + p + s, image.top + d) }
            bites(image.width()) { p, s, d -> RectF(image.left + p, image.bottom - d, image.left + p + s, image.bottom) }
            bites(image.height()) { p, s, d -> RectF(image.left, image.top + p, image.left + d, image.top + p + s) }
            bites(image.height()) { p, s, d -> RectF(image.right - d, image.top + p, image.right, image.top + p + s) }
        }
        BorderStyle.REBATE -> {
            // 35mm strip: sprocket holes along both long edges, plus edge markings.
            val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD9D4C7.toInt() }
            val landscape = image.width() >= image.height()
            val bandSize = b * 3f
            val holeShort = bandSize * 0.32f
            val holeLong = holeShort * 0.75f
            val pitch = holeShort * 1.9f
            val length = if (landscape) w.toFloat() else h.toFloat()
            var p = pitch * 0.5f
            while (p + holeLong < length) {
                if (landscape) {
                    val y1 = bandSize * 0.2f
                    canvas.drawRoundRect(RectF(p, y1, p + holeLong, y1 + holeShort), holeShort * 0.2f, holeShort * 0.2f, hole)
                    val y2 = h - bandSize * 0.2f - holeShort
                    canvas.drawRoundRect(RectF(p, y2, p + holeLong, y2 + holeShort), holeShort * 0.2f, holeShort * 0.2f, hole)
                } else {
                    val x1 = bandSize * 0.2f
                    canvas.drawRoundRect(RectF(x1, p, x1 + holeShort, p + holeLong), holeShort * 0.2f, holeShort * 0.2f, hole)
                    val x2 = w - bandSize * 0.2f - holeShort
                    canvas.drawRoundRect(RectF(x2, p, x2 + holeShort, p + holeLong), holeShort * 0.2f, holeShort * 0.2f, hole)
                }
                p += pitch
            }
            if (recipe.borderText) {
                val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFFE0A040.toInt()
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    textSize = bandSize * 0.22f
                }
                val label = "CAMERAAPP  ${recipe.label}  ▸ 12  400"
                if (landscape) {
                    canvas.drawText(label, image.left + bandSize, image.top - bandSize * 0.08f, text)
                } else {
                    canvas.save()
                    canvas.rotate(90f, image.right + bandSize * 0.08f, image.top + bandSize)
                    canvas.drawText(label, image.right + bandSize * 0.08f, image.top + bandSize, text)
                    canvas.restore()
                }
            }
        }
        BorderStyle.INSTANT -> {
            if (recipe.borderText) {
                val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (recipe.borderColor == BorderColor.BLACK) 0xFFB8B4AA.toInt() else 0xFF55524C.toInt()
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                    textSize = b * 0.9f
                }
                canvas.drawText(recipe.label, image.left, image.bottom + b * 2.6f, text)
            }
        }
        else -> Unit
    }
}

/** Point-and-shoot style date imprint in the chosen corner of [image]. */
fun drawDateStamp(canvas: Canvas, recipe: FilmRecipe, image: RectF, timeMillis: Long) {
    val short = min(image.width(), image.height())
    val text = SimpleDateFormat(recipe.dateFormat.pattern, Locale.US).format(Date(timeMillis))
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = recipe.dateColor.argb
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = short * 0.035f * recipe.dateSize
        // A soft glow like the LED imprint bleeding into the film.
        setShadowLayer(textSize * 0.35f, 0f, 0f, (recipe.dateColor.argb and 0x00FFFFFF) or 0xAA000000.toInt())
    }
    val margin = short * 0.05f
    val tw = paint.measureText(text)
    val x = when (recipe.dateCorner) {
        StampCorner.BOTTOM_RIGHT, StampCorner.TOP_RIGHT -> image.right - margin - tw
        else -> image.left + margin
    }
    val y = when (recipe.dateCorner) {
        StampCorner.BOTTOM_RIGHT, StampCorner.BOTTOM_LEFT -> image.bottom - margin
        else -> image.top + margin + paint.textSize
    }
    canvas.drawText(text, x, y, paint)
}

/** Rotates/flips [bitmap] upright according to EXIF; returns a new bitmap (or the same one). */
fun uprightBitmap(bitmap: Bitmap, rotationDegrees: Int, flipped: Boolean): Bitmap {
    if (rotationDegrees == 0 && !flipped) return bitmap
    val m = Matrix()
    if (flipped) m.postScale(-1f, 1f)
    m.postRotate(rotationDegrees.toFloat())
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    val mutable = if (rotated.isMutable) rotated else rotated.copy(Bitmap.Config.ARGB_8888, true).also { rotated.recycle() }
    if (mutable !== bitmap) bitmap.recycle()
    return mutable
}
