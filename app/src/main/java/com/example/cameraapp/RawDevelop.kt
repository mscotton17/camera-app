package com.example.cameraapp

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ForkJoinPool
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

private const val TAG = "RawDevelop"

/**
 * Our own development of the app's DNGs, from the raw sensor data, so recipes grade the real
 * scene instead of Android's already-developed (tone-curved, clipped) render:
 *
 * 1. Read the uncompressed Bayer data, black / white levels, lens-shading gain maps
 *    (OpcodeList2), as-shot neutral, and the colour matrix straight from the DNG.
 * 2. Normalise to 0..1 linear sensor values and correct lens shading.
 * 3. Demosaic (edge-directed green, bilinear red / blue), white balance, and convert camera RGB
 *    to linear sRGB through the DNG colour matrix. Clipped highlights are eased to neutral so
 *    they don't turn magenta.
 * 4. Roll highlight headroom (values above 1, e.g. warm lights after white balance) off with a
 *    smooth, hue-preserving shoulder instead of clipping.
 * 5. Encode with the standard sRGB curve — the same base the viewfinder gets with NATURAL tone —
 *    so the recipe pipeline (FilmCpu.kt) and the live preview stay identical.
 *
 * Returns an upright ARGB_8888 bitmap, or null when the file isn't a DNG this can read (tiled,
 * compressed, not 2x2 Bayer...), in which case the caller falls back to Android's decoder.
 */
fun developRawDng(context: Context, uri: Uri, exposureGain: Float = 1f): Bitmap? {
    val bytes = try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: Exception) {
        null
    } ?: return null
    val raw = try {
        DngReader(bytes).read()
    } catch (e: Exception) {
        Log.w(TAG, "Not a DNG we can develop", e)
        null
    } ?: return null
    val bitmap = try {
        develop(raw, exposureGain)
    } catch (e: OutOfMemoryError) {
        Log.e(TAG, "Out of memory developing the DNG", e)
        return null
    }
    val exif = try {
        context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
    } catch (e: Exception) {
        null
    }
    val rotation = exif?.rotationDegrees ?: raw.orientationDegrees
    val flipped = exif?.isFlipped ?: false
    return if (rotation != 0 || flipped) uprightBitmap(bitmap, rotation, flipped) else bitmap
}

// ---------------------------------------------------------------------------------------------
// What we need from a DNG
// ---------------------------------------------------------------------------------------------

private class RawImage(
    val width: Int,
    val height: Int,
    /** Normalised linear sensor values (0 = black, 1 = white level), lens shading corrected. */
    val data: FloatArray,
    /** 1 where the sensor clipped. */
    val clipped: ByteArray,
    /** Colour (0 R, 1 G, 2 B) at (y % 2, x % 2) as index (y % 2) * 2 + x % 2. */
    val cfa: IntArray,
    /** Camera-space white balance multipliers (green = 1). */
    val wb: FloatArray,
    /** Camera RGB (white balanced) -> linear sRGB, row-major 3x3. */
    val camToRgb: FloatArray,
    /** Output crop inside [width] x [height]. */
    val cropX: Int, val cropY: Int, val cropW: Int, val cropH: Int,
    val orientationDegrees: Int,
    val baselineGain: Float,
)

private class Entry(val type: Int, val count: Int, val valueOffset: Int)

/** Minimal TIFF / DNG reader for the uncompressed Bayer DNGs Android's DngCreator writes. */
private class DngReader(private val bytes: ByteArray) {
    private val buf: ByteBuffer = ByteBuffer.wrap(bytes)

    fun read(): RawImage? {
        buf.order(
            when {
                bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
                bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
                else -> return null
            }
        )
        if (u16(2) != 42) return null
        val ifd0 = readIfd(u32(4))
        // The raw image is IFD0 itself, one of its SubIFDs, or a later IFD in the chain.
        val candidates = mutableListOf(ifd0)
        ifd0[330]?.let { e -> values(e).forEach { candidates += readIfd(it.toInt()) } }
        var next = nextIfdOffset
        var guard = 0
        while (next != 0 && guard++ < 8) {
            val ifd = readIfd(next)
            candidates += ifd
            next = nextIfdOffset
        }
        val rawIfd = candidates.firstOrNull { ifd ->
            num(ifd, 262) == 32803.0 && ((num(ifd, 254) ?: 0.0).toInt() and 1) == 0
        } ?: return null
        fun tag(t: Int): Entry? = rawIfd[t] ?: ifd0[t]

        val width = num(rawIfd, 256)?.toInt() ?: return null
        val height = num(rawIfd, 257)?.toInt() ?: return null
        if ((num(rawIfd, 259) ?: 1.0).toInt() != 1) return null // compressed
        if ((num(rawIfd, 258) ?: 16.0).toInt() != 16) return null
        if ((num(rawIfd, 277) ?: 1.0).toInt() != 1) return null
        if (rawIfd[322] != null) return null // tiled
        val repeat = rawIfd[33421]?.let { values(it) }
        if (repeat != null && (repeat[0].toInt() != 2 || repeat[1].toInt() != 2)) return null
        val pattern = rawIfd[33422]?.let { values(it).map { v -> v.toInt() } } ?: return null
        if (pattern.size < 4 || pattern.take(4).any { it !in 0..2 }) return null

        // Sensor samples, row-major, from the strips.
        val offsets = values(rawIfd[273] ?: return null)
        val counts = values(rawIfd[279] ?: return null)
        val samples = ShortArray(width * height)
        var k = 0
        for (s in offsets.indices) {
            var p = offsets[s].toInt()
            val end = p + counts[s].toInt()
            while (p + 1 < end && k < samples.size) {
                samples[k++] = buf.getShort(p)
                p += 2
            }
        }
        if (k < samples.size) return null

        // Active area (inside the sensor array) and the default crop (inside the active area).
        val active = rawIfd[50829]?.let { values(it).map { v -> v.toInt() } } // top, left, bottom, right
        val aTop = active?.get(0) ?: 0
        val aLeft = active?.get(1) ?: 0
        val aW = (active?.get(3) ?: width) - aLeft
        val aH = (active?.get(2) ?: height) - aTop

        // Black level per CFA position, white level.
        val blackDim = rawIfd[50713]?.let { values(it).map { v -> v.toInt().coerceAtLeast(1) } } ?: listOf(1, 1)
        val blackVals = rawIfd[50714]?.let { values(it) } ?: doubleArrayOf(0.0)
        val white = (rawIfd[50717]?.let { values(it)[0] } ?: 65535.0).toFloat()
        fun black(y: Int, x: Int): Float {
            val i = (y % blackDim[0]) * blackDim[1] + (x % blackDim[1])
            return blackVals[i.coerceAtMost(blackVals.size - 1)].toFloat()
        }
        val blacks = FloatArray(4) { black(it / 2, it % 2) }

        // Lens shading gain maps (one per CFA channel, as DngCreator writes them).
        val gainMaps = rawIfd[51009]?.let { parseGainMaps(it) }.orEmpty()

        val data = FloatArray(aW * aH)
        val clipped = ByteArray(aW * aH)
        for (y in 0 until aH) {
            val sy = y + aTop
            for (x in 0 until aW) {
                val sx = x + aLeft
                val v = samples[sy * width + sx].toInt() and 0xFFFF
                val bl = blacks[(sy % 2) * 2 + (sx % 2)]
                val i = y * aW + x
                if (v >= white * 0.985f) clipped[i] = 1
                data[i] = ((v - bl) / (white - bl)).coerceAtLeast(0f)
            }
        }
        for (g in gainMaps) g.apply(data, aW, aH)

        // Colour: XYZ -> camera (ColorMatrix for the daylight illuminant if there is one),
        // with analog balance and camera calibration, and the as-shot neutral.
        val ill1 = num(ifd0, 50778) ?: num(rawIfd, 50778)
        val ill2 = num(ifd0, 50779) ?: num(rawIfd, 50779)
        val cm1 = tag(50721)?.let { values(it) }
        val cm2 = tag(50722)?.let { values(it) }
        val daylight = setOf(21.0, 20.0, 22.0, 23.0, 1.0, 4.0, 9.0) // D65, D55, D75, D50, daylight, flash, fine weather
        val useSecond = cm2 != null && (ill2 in daylight || ill1 !in daylight)
        val cm = (if (useSecond) cm2 else cm1)?.takeIf { it.size >= 9 } ?: return null
        val cc = (if (useSecond) tag(50624) else tag(50623))?.let { values(it) }?.takeIf { it.size >= 9 }
        val ab = tag(50727)?.let { values(it) }?.takeIf { it.size >= 3 }
        var xyzToCam = FloatArray(9) { cm[it].toFloat() }
        if (cc != null) xyzToCam = mul3(FloatArray(9) { cc[it].toFloat() }, xyzToCam)
        if (ab != null) xyzToCam = mul3(floatArrayOf(ab[0].toFloat(), 0f, 0f, 0f, ab[1].toFloat(), 0f, 0f, 0f, ab[2].toFloat()), xyzToCam)
        val neutral = tag(50728)?.let { values(it) }?.takeIf { it.size >= 3 }?.map { it.toFloat().coerceAtLeast(1e-4f) }
            ?: listOf(1f, 1f, 1f)
        val wb = floatArrayOf(neutral[1] / neutral[0], 1f, neutral[1] / neutral[2])

        // dcraw's method: camera <- sRGB, rows normalised so white stays white, then inverted.
        val camFromRgb = mul3(xyzToCam, XYZ_FROM_SRGB)
        for (r in 0 until 3) {
            val sum = camFromRgb[r * 3] + camFromRgb[r * 3 + 1] + camFromRgb[r * 3 + 2]
            if (abs(sum) > 1e-6f) for (c in 0 until 3) camFromRgb[r * 3 + c] /= sum
        }
        val camToRgb = invert3(camFromRgb) ?: return null

        val cropOrigin = rawIfd[50719]?.let { values(it) }
        val cropSize = rawIfd[50720]?.let { values(it) }
        val cx = (cropOrigin?.get(0)?.roundToInt() ?: 0).coerceIn(0, aW - 1)
        val cy = (cropOrigin?.get(1)?.roundToInt() ?: 0).coerceIn(0, aH - 1)
        val cw = (cropSize?.get(0)?.roundToInt() ?: aW).coerceIn(1, aW - cx)
        val ch = (cropSize?.get(1)?.roundToInt() ?: aH).coerceIn(1, aH - cy)

        // The CFA pattern is given for the sensor array; shift it to the active area's origin.
        val cfa = IntArray(4) { i ->
            val y = i / 2 + aTop
            val x = i % 2 + aLeft
            pattern[(y % 2) * 2 + (x % 2)]
        }
        val orientation = when ((num(ifd0, 274) ?: 1.0).toInt()) {
            3, 4 -> 180
            6, 5 -> 90
            8, 7 -> 270
            else -> 0
        }
        val baseline = (tag(50730)?.let { values(it)[0] } ?: 0.0).toFloat()
        return RawImage(aW, aH, data, clipped, cfa, wb, camToRgb, cx, cy, cw, ch, orientation, 2f.pow(baseline))
    }

    // --- TIFF plumbing ---

    private var nextIfdOffset = 0

    private fun u16(p: Int) = buf.getShort(p).toInt() and 0xFFFF
    private fun u32(p: Int) = buf.getInt(p)

    private fun readIfd(offset: Int): Map<Int, Entry> {
        val n = u16(offset)
        val map = HashMap<Int, Entry>(n * 2)
        for (i in 0 until n) {
            val e = offset + 2 + i * 12
            val tag = u16(e)
            val type = u16(e + 2)
            val count = u32(e + 4)
            val size = typeSize(type) * count
            map[tag] = Entry(type, count, if (size <= 4) e + 8 else u32(e + 8))
        }
        nextIfdOffset = u32(offset + 2 + n * 12)
        return map
    }

    private fun typeSize(type: Int) = when (type) {
        1, 2, 6, 7 -> 1
        3, 8 -> 2
        4, 9, 11, 13 -> 4
        5, 10, 12 -> 8
        else -> 1
    }

    private fun num(ifd: Map<Int, Entry>, tag: Int): Double? = ifd[tag]?.let { values(it).firstOrNull() }

    fun values(e: Entry): DoubleArray {
        val p = e.valueOffset
        return DoubleArray(e.count) { i ->
            when (e.type) {
                1, 7 -> (bytes[p + i].toInt() and 0xFF).toDouble()
                6 -> bytes[p + i].toDouble()
                3 -> u16(p + i * 2).toDouble()
                8 -> buf.getShort(p + i * 2).toDouble()
                4, 13 -> (u32(p + i * 4).toLong() and 0xFFFFFFFFL).toDouble()
                9 -> u32(p + i * 4).toDouble()
                5 -> {
                    val num = u32(p + i * 8).toLong() and 0xFFFFFFFFL
                    val den = u32(p + i * 8 + 4).toLong() and 0xFFFFFFFFL
                    if (den == 0L) 0.0 else num.toDouble() / den
                }
                10 -> {
                    val den = u32(p + i * 8 + 4)
                    if (den == 0) 0.0 else u32(p + i * 8).toDouble() / den
                }
                11 -> buf.getFloat(p + i * 4).toDouble()
                12 -> buf.getDouble(p + i * 8)
                else -> 0.0
            }
        }
    }

    /** GainMap opcodes (id 9) from an OpcodeList, which is always big-endian. */
    private fun parseGainMaps(e: Entry): List<GainMap> {
        val b = ByteBuffer.wrap(bytes, e.valueOffset, e.count).slice().order(ByteOrder.BIG_ENDIAN)
        val maps = mutableListOf<GainMap>()
        val n = b.getInt(0)
        var p = 4
        repeat(n) {
            val id = b.getInt(p)
            val paramBytes = b.getInt(p + 12)
            val q = p + 16
            if (id == 9) {
                val pointsV = b.getInt(q + 32)
                val pointsH = b.getInt(q + 36)
                val mapPlanes = b.getInt(q + 72)
                val gains = FloatArray(pointsV * pointsH * mapPlanes) { b.getFloat(q + 76 + it * 4) }
                maps += GainMap(
                    top = b.getInt(q), left = b.getInt(q + 4), bottom = b.getInt(q + 8), right = b.getInt(q + 12),
                    rowPitch = b.getInt(q + 24).coerceAtLeast(1), colPitch = b.getInt(q + 28).coerceAtLeast(1),
                    pointsV = pointsV, pointsH = pointsH,
                    spacingV = b.getDouble(q + 40).toFloat(), spacingH = b.getDouble(q + 48).toFloat(),
                    originV = b.getDouble(q + 56).toFloat(), originH = b.getDouble(q + 64).toFloat(),
                    mapPlanes = mapPlanes, gains = gains,
                )
            }
            p = q + paramBytes
        }
        return maps
    }
}

/** DNG GainMap: a grid of gains (relative to the image area) applied to a pixel subset. */
private class GainMap(
    val top: Int, val left: Int, val bottom: Int, val right: Int,
    val rowPitch: Int, val colPitch: Int,
    val pointsV: Int, val pointsH: Int,
    val spacingV: Float, val spacingH: Float,
    val originV: Float, val originH: Float,
    val mapPlanes: Int, val gains: FloatArray,
) {
    fun apply(data: FloatArray, w: Int, h: Int) {
        if (pointsV < 1 || pointsH < 1) return
        fun g(r: Int, c: Int) = gains[(r.coerceIn(0, pointsV - 1) * pointsH + c.coerceIn(0, pointsH - 1)) * mapPlanes]
        val y1 = min(bottom, h)
        val x1 = min(right, w)
        var y = top
        while (y < y1) {
            val mv = if (spacingV > 0f) ((y.toFloat() / h - originV) / spacingV).coerceIn(0f, (pointsV - 1).toFloat()) else 0f
            val r0 = mv.toInt()
            val ty = mv - r0
            var x = left
            while (x < x1) {
                val mh = if (spacingH > 0f) ((x.toFloat() / w - originH) / spacingH).coerceIn(0f, (pointsH - 1).toFloat()) else 0f
                val c0 = mh.toInt()
                val tx = mh - c0
                val a = g(r0, c0) + (g(r0, c0 + 1) - g(r0, c0)) * tx
                val b = g(r0 + 1, c0) + (g(r0 + 1, c0 + 1) - g(r0 + 1, c0)) * tx
                data[y * w + x] *= a + (b - a) * ty
                x += colPitch
            }
            y += rowPitch
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Development
// ---------------------------------------------------------------------------------------------

/** Linear sRGB (D65) -> XYZ. */
private val XYZ_FROM_SRGB = floatArrayOf(
    0.4124564f, 0.3575761f, 0.1804375f,
    0.2126729f, 0.7151522f, 0.0721750f,
    0.0193339f, 0.1191920f, 0.9503041f,
)

/** Shoulder: linear values above this roll smoothly toward 1 instead of clipping. */
private const val SHOULDER_KNEE = 0.82f

/** Linear 0..1 -> sRGB 0..255 lookup, fine enough for 8-bit output. */
private val SrgbEncode: IntArray by lazy {
    IntArray(4097) { i ->
        val x = i / 4096f
        val y = if (x <= 0.0031308f) 12.92f * x else 1.055f * x.pow(1f / 2.4f) - 0.055f
        (y * 255f).roundToInt().coerceIn(0, 255)
    }
}

private fun develop(raw: RawImage, exposureGain: Float): Bitmap {
    val w = raw.width
    val h = raw.height
    val d = raw.data
    val clip = raw.clipped
    val cfa = raw.cfa
    val m = raw.camToRgb
    val wb = raw.wb
    val gain = exposureGain * raw.baselineGain
    val out = Bitmap.createBitmap(raw.cropW, raw.cropH, Bitmap.Config.ARGB_8888)
    val enc = SrgbEncode
    val shoulderRange = 1f - SHOULDER_KNEE

    fun at(y: Int, x: Int) = d[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
    fun colorAt(y: Int, x: Int) = cfa[((y.coerceIn(0, h - 1)) and 1) * 2 + ((x.coerceIn(0, w - 1)) and 1)]

    val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
    val band = (raw.cropH + threads - 1) / threads
    val pool = ForkJoinPool.commonPool()
    (0 until threads).map { t ->
        pool.submit(Runnable {
            val row = IntArray(raw.cropW)
            val cam = FloatArray(3)
            val y0 = t * band
            val y1 = min(raw.cropH, y0 + band)
            for (oy in y0 until y1) {
                val y = oy + raw.cropY
                for (ox in 0 until raw.cropW) {
                    val x = ox + raw.cropX
                    val c = colorAt(y, x)
                    cam[c] = at(y, x)
                    // Green at red / blue sites: average along the smoother direction.
                    if (c != 1) {
                        val l = at(y, x - 1); val r = at(y, x + 1); val u = at(y - 1, x); val dn = at(y + 1, x)
                        val gh = abs(l - r) + abs(2f * cam[c] - at(y, x - 2) - at(y, x + 2)) * 0.5f
                        val gv = abs(u - dn) + abs(2f * cam[c] - at(y - 2, x) - at(y + 2, x)) * 0.5f
                        cam[1] = when {
                            gh < gv * 0.8f -> (l + r) * 0.5f
                            gv < gh * 0.8f -> (u + dn) * 0.5f
                            else -> (l + r + u + dn) * 0.25f
                        }
                    }
                    // Red / blue: average of the 3x3 neighbours of that colour.
                    for (want in 0..2 step 2) {
                        if (want == c) continue
                        var sum = 0f
                        var n = 0
                        for (dy in -1..1) for (dx in -1..1) {
                            if ((dy != 0 || dx != 0) && colorAt(y + dy, x + dx) == want) {
                                sum += at(y + dy, x + dx); n++
                            }
                        }
                        cam[want] = if (n > 0) sum / n else 0f
                    }
                    // White balance, then ease clipped highlights toward neutral (no magenta).
                    var cr = cam[0] * wb[0]
                    var cg = cam[1]
                    var cb = cam[2] * wb[2]
                    var clipped = 0
                    for (dy in -1..1) for (dx in -1..1) {
                        clipped += clip[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]
                    }
                    if (clipped > 0) {
                        val f = min(1f, clipped / 3f)
                        val top = max(cr, max(cg, cb))
                        cr += (top - cr) * f; cg += (top - cg) * f; cb += (top - cb) * f
                    }
                    // Camera -> linear sRGB, exposure.
                    var lr = (m[0] * cr + m[1] * cg + m[2] * cb) * gain
                    var lg = (m[3] * cr + m[4] * cg + m[5] * cb) * gain
                    var lb = (m[6] * cr + m[7] * cg + m[8] * cb) * gain
                    lr = max(lr, 0f); lg = max(lg, 0f); lb = max(lb, 0f)
                    // Highlight shoulder on the brightest channel, keeping the hue.
                    val peak = max(lr, max(lg, lb))
                    if (peak > SHOULDER_KNEE) {
                        val mapped = SHOULDER_KNEE + shoulderRange * (1f - exp(-(peak - SHOULDER_KNEE) / shoulderRange))
                        val s = mapped / peak
                        lr *= s; lg *= s; lb *= s
                    }
                    val pr = enc[(lr * 4096f).toInt().coerceIn(0, 4096)]
                    val pg = enc[(lg * 4096f).toInt().coerceIn(0, 4096)]
                    val pb = enc[(lb * 4096f).toInt().coerceIn(0, 4096)]
                    row[ox] = (0xFF shl 24) or (pr shl 16) or (pg shl 8) or pb
                }
                out.setPixels(row, 0, raw.cropW, 0, oy, raw.cropW, 1)
            }
        })
    }.forEach { it.get() }
    return out
}

private fun mul3(a: FloatArray, b: FloatArray) = FloatArray(9) { i ->
    val r = i / 3
    val c = i % 3
    a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
}

private fun invert3(a: FloatArray): FloatArray? {
    val det = a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6])
    if (abs(det) < 1e-9f) return null
    val inv = 1f / det
    return floatArrayOf(
        (a[4] * a[8] - a[5] * a[7]) * inv, (a[2] * a[7] - a[1] * a[8]) * inv, (a[1] * a[5] - a[2] * a[4]) * inv,
        (a[5] * a[6] - a[3] * a[8]) * inv, (a[0] * a[8] - a[2] * a[6]) * inv, (a[2] * a[3] - a[0] * a[5]) * inv,
        (a[3] * a[7] - a[4] * a[6]) * inv, (a[1] * a[6] - a[0] * a[7]) * inv, (a[0] * a[4] - a[1] * a[3]) * inv,
    )
}
