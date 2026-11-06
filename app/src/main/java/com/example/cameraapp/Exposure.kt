package com.example.cameraapp

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import android.graphics.PointF
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.TonemapCurve
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Shutter speeds offered in the picker, slowest to fastest (1s .. 1/8000s), filtered per lens. */
private val ShutterStopsNs: List<Long> =
    listOf(1_000_000_000L) + listOf(2, 4, 8, 15, 30, 60, 125, 250, 500, 1000, 2000, 4000, 8000)
        .map { 1_000_000_000L / it }

/** Exposure compensation choices: -3 to +3 EV in 1/3 stops. */
val EvOptions: List<Float> = (-9..9).map { it / 3f }

/** "+0.7", "-1.3", or "±0.0". */
fun formatEv(ev: Float): String =
    if (abs(ev) < 0.05f) "\u00B10.0" else String.format(java.util.Locale.US, "%+.1f", ev)

/**
 * Applies [ev] through the camera's own auto-exposure compensation, rounded to its step size.
 * Only meaningful while auto-exposure is on.
 */
fun applyAeCompensation(camera: Camera, ev: Float) {
    val state = camera.cameraInfo.exposureState
    if (!state.isExposureCompensationSupported) return
    val step = state.exposureCompensationStep.toFloat()
    if (step <= 0f) return
    val index = state.exposureCompensationRange.clamp((ev / step).roundToInt())
    camera.cameraControl.setExposureCompensationIndex(index)
}

/** The exposure compensation range in EV the camera supports, or null if it has none. */
fun aeCompensationRangeEv(camera: Camera): ClosedFloatingPointRange<Float>? {
    val state = camera.cameraInfo.exposureState
    if (!state.isExposureCompensationSupported) return null
    val step = state.exposureCompensationStep.toFloat()
    if (step <= 0f) return null
    return state.exposureCompensationRange.lower * step..state.exposureCompensationRange.upper * step
}

/** Longest shutter the app picks by itself in ISO priority, to keep the viewfinder usable. */
private const val AUTO_SHUTTER_MAX_NS = 1_000_000_000L / 8

/** Mean preview brightness (gamma-encoded, 0..1) the metering loop aims for: a mid-tone. */
private const val TARGET_LUMA = 0.46f

/** ISO values offered in the picker, filtered per lens. */
private val IsoStops = listOf(25, 50, 100, 200, 400, 800, 1600, 3200, 6400, 12800)

fun ExposureCaps.shutterOptions(): List<Long> =
    exposureRangeNs?.let { r -> ShutterStopsNs.filter { r.contains(it) } }.orEmpty()

fun ExposureCaps.isoOptions(): List<Int> =
    isoRange?.let { r -> IsoStops.filter { r.contains(it) } }.orEmpty()

/**
 * User exposure overrides. Everything is automatic by default.
 *
 * Camera2 has no shutter- or ISO-priority mode: turning off auto-exposure means sending both
 * values. When the user locks only one ([shutterLocked] / [isoLocked]), the other starts at a
 * value that keeps the current brightness and is then kept on target by [meteredStep], which
 * the screen runs a few times a second from the preview's brightness ("priority" modes).
 */
data class ExposureSettings(
    val shutterNs: Long? = null,
    val iso: Int? = null,
    val shutterLocked: Boolean = false,
    val isoLocked: Boolean = false,
    /** Chosen f-stop; only used on lenses with a variable aperture. */
    val aperture: Float? = null,
) {
    val manualSensor: Boolean get() = shutterNs != null && iso != null

    /** Exactly one of shutter/ISO is locked, so the app meters and adjusts the other. */
    val priorityMode: Boolean get() = manualSensor && shutterLocked != isoLocked

    /** Fix the shutter speed; derive ISO from [current] brightness unless the user set it. */
    fun withShutter(ns: Long, current: CameraTelemetry, caps: ExposureCaps): ExposureSettings {
        val newIso = if (isoLocked && iso != null) {
            iso
        } else {
            val curIso = current.iso
            val curNs = current.exposureTimeNs
            if (curIso != null && curNs != null && curNs > 0) (curIso.toDouble() * curNs / ns).roundToInt() else 100
        }
        return copy(shutterNs = ns, iso = caps.isoRange?.clamp(newIso) ?: newIso, shutterLocked = true)
    }

    /** Fix ISO; derive the shutter speed from [current] brightness unless the user set it. */
    fun withIso(value: Int, current: CameraTelemetry, caps: ExposureCaps): ExposureSettings {
        val newNs = if (shutterLocked && shutterNs != null) {
            shutterNs
        } else {
            val curIso = current.iso
            val curNs = current.exposureTimeNs
            if (curIso != null && curNs != null && curNs > 0) {
                (curNs.toDouble() * curIso / value).roundToLong()
            } else {
                1_000_000_000L / 60
            }
        }
        return copy(shutterNs = caps.exposureRangeNs?.clamp(newNs) ?: newNs, iso = value, isoLocked = true)
    }

    /** Back to automatic shutter speed and ISO (keeps any chosen aperture). */
    fun autoSensor(): ExposureSettings =
        copy(shutterNs = null, iso = null, shutterLocked = false, isoLocked = false)

    /** Shutter back to auto: ISO priority if ISO is locked, otherwise full auto. */
    fun autoShutter(): ExposureSettings = if (isoLocked) copy(shutterLocked = false) else autoSensor()

    /** ISO back to auto: shutter priority if the shutter is locked, otherwise full auto. */
    fun autoIso(): ExposureSettings = if (shutterLocked) copy(isoLocked = false) else autoSensor()

    /**
     * One step of the app's own metering in priority modes: moves the unlocked value toward
     * [TARGET_LUMA] given the preview's mean brightness [meanLuma] (0..1).
     */
    fun meteredStep(meanLuma: Float, caps: ExposureCaps, evComp: Float = 0f): ExposureSettings {
        val ns = shutterNs ?: return this
        val sensitivity = iso ?: return this
        if (!priorityMode) return this
        // The preview is gamma-encoded (~2.2), so a shift of N stops moves mean luma by 2^(N/2.2).
        val target = (TARGET_LUMA * 2.0.pow(evComp / 2.2).toFloat()).coerceIn(0.05f, 0.95f)
        val errorEv = log2(target / meanLuma.coerceIn(0.01f, 0.99f))
        if (abs(errorEv) < 0.15f) return this
        // Damped so it settles smoothly; limited per step because a clipped frame
        // under-reports how far off it is.
        val factor = 2.0.pow((errorEv * 0.7f).coerceIn(-1.5f, 1.5f).toDouble())
        return if (shutterLocked) {
            val target = (sensitivity * factor).roundToInt()
            copy(iso = caps.isoRange?.clamp(target) ?: target)
        } else {
            val range = caps.exposureRangeNs
            val lower = range?.lower ?: 1L
            val upper = min(range?.upper ?: AUTO_SHUTTER_MAX_NS, AUTO_SHUTTER_MAX_NS).coerceAtLeast(lower)
            copy(shutterNs = (ns * factor).roundToLong().coerceIn(lower, upper))
        }
    }

    /** These settings limited to what [caps] supports (e.g. after switching lens). */
    fun resolvedFor(caps: ExposureCaps): ExposureSettings {
        val ns = shutterNs
        val sensitivity = iso
        val sensor = if (caps.manualSensor && ns != null && sensitivity != null) {
            copy(
                shutterNs = caps.exposureRangeNs?.clamp(ns) ?: ns,
                iso = caps.isoRange?.clamp(sensitivity) ?: sensitivity,
            )
        } else {
            autoSensor()
        }
        return sensor.copy(aperture = aperture?.takeIf { caps.variableAperture && it in caps.apertures })
    }
}

/** How auto-exposure (and shutter / ISO priority) judges brightness. */
enum class MeteringMode(val label: String, val description: String) {
    MATRIX("MATRIX", "The phone's own metering across the whole frame"),
    CENTER("CENTER", "Centre-weighted, like a classic film camera"),
    SPOT("SPOT", "A small spot in the middle, or wherever you tap"),
}

/** Spot size as a fraction of the frame's short side. */
private const val SPOT_SIZE = 0.08f

/** sRGB transfer curve as (in, out) pairs, for cameras that only take a contrast curve. */
private val SrgbCurve: FloatArray by lazy {
    val n = 64
    FloatArray(n * 2) { i ->
        val x = (i / 2) / (n - 1f)
        if (i % 2 == 0) x
        else if (x <= 0.0031308f) 12.92f * x else (1.055f * x.toDouble().pow(1.0 / 2.4) - 0.055).toFloat()
    }
}

/**
 * Sends [settings] to the camera; they apply to the preview and to every still capture.
 * [fixedTone] swaps the phone's scene-adaptive tone curve (which lifts shadows and pulls down
 * highlights on every scene) for a fixed sRGB curve, like a dedicated camera. [metering] sets
 * where auto-exposure measures; [spot] is the spot's centre in normalised sensor coordinates.
 */
@SuppressLint("UnsafeOptInUsageError") // Camera2CameraControl is @ExperimentalCamera2Interop
fun applyExposure(
    camera: Camera,
    settings: ExposureSettings,
    awbMode: Int? = null,
    caps: ExposureCaps? = null,
    fixedTone: Boolean = false,
    metering: MeteringMode = MeteringMode.MATRIX,
    spot: PointF? = null,
) {
    val control = Camera2CameraControl.from(camera.cameraControl)
    val shutter = settings.shutterNs
    val iso = settings.iso
    val aperture = settings.aperture
    // One options set carries everything, because setting it replaces whatever was there.
    val builder = CaptureRequestOptions.Builder()
    var any = false
    if (shutter != null && iso != null) {
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
            .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            // Long exposures need a long enough frame; otherwise keep ~30fps.
            .setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, max(shutter, 33_333_333L))
        any = true
    }
    if (aperture != null) {
        builder.setCaptureRequestOption(CaptureRequest.LENS_APERTURE, aperture)
        any = true
    }
    if (awbMode != null && awbMode != CaptureRequest.CONTROL_AWB_MODE_AUTO) {
        // A fixed white balance, like film balanced for one kind of light.
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awbMode)
        any = true
    }
    if (fixedTone && caps != null) {
        val modes = caps.toneModes
        when {
            CameraMetadata.TONEMAP_MODE_PRESET_CURVE in modes -> {
                builder.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_PRESET_CURVE)
                    .setCaptureRequestOption(CaptureRequest.TONEMAP_PRESET_CURVE, CaptureRequest.TONEMAP_PRESET_CURVE_SRGB)
                any = true
            }
            CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE in modes -> {
                builder.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
                    .setCaptureRequestOption(CaptureRequest.TONEMAP_CURVE, TonemapCurve(SrgbCurve, SrgbCurve, SrgbCurve))
                any = true
            }
            CameraMetadata.TONEMAP_MODE_GAMMA_VALUE in modes -> {
                builder.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_GAMMA_VALUE)
                    .setCaptureRequestOption(CaptureRequest.TONEMAP_GAMMA, 2.2f)
                any = true
            }
        }
    }
    if (caps?.lensShadingMap == true) {
        // Report the lens-shading map, so DNGs include it (as gain maps) and our raw development
        // can correct the corners the way the camera's own processing does.
        builder.setCaptureRequestOption(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)
        any = true
    }
    val area = caps?.activeArray
    val autoExposure = shutter == null || iso == null
    if (autoExposure && area != null && (caps?.maxAeRegions ?: 0) > 0 && metering != MeteringMode.MATRIX) {
        val w = area.width()
        val h = area.height()
        val rect = when (metering) {
            MeteringMode.CENTER -> {
                // The middle 60% of the frame, the way centre-weighted metering favours it.
                MeteringRectangle(area.left + (w * 0.2f).toInt(), area.top + (h * 0.2f).toInt(), (w * 0.6f).toInt(), (h * 0.6f).toInt(), MeteringRectangle.METERING_WEIGHT_MAX)
            }
            else -> {
                val side = (min(w, h) * SPOT_SIZE).toInt().coerceAtLeast(16)
                val cx = area.left + ((spot?.x ?: 0.5f).coerceIn(0f, 1f) * w).toInt()
                val cy = area.top + ((spot?.y ?: 0.5f).coerceIn(0f, 1f) * h).toInt()
                val x = (cx - side / 2).coerceIn(area.left, area.right - side)
                val y = (cy - side / 2).coerceIn(area.top, area.bottom - side)
                MeteringRectangle(x, y, side, side, MeteringRectangle.METERING_WEIGHT_MAX)
            }
        }
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(rect))
        any = true
    }
    if (any) control.setCaptureRequestOptions(builder.build()) else control.clearCaptureRequestOptions()
}

/**
 * Mean brightness (0..1) of the camera image within a FIT_CENTER [preview] bitmap, measured
 * only inside the recipe's [aspect] crop so priority metering ignores what won't be saved.
 */
fun meanLuma(
    preview: Bitmap,
    aspect: AspectRatio = AspectRatio.NATIVE,
    metering: MeteringMode = MeteringMode.MATRIX,
    /** Spot centre within the crop, 0..1 each way. */
    spot: PointF? = null,
): Float? {
    val w = preview.width
    val h = preview.height
    if (w == 0 || h == 0) return null
    // Measure only the image, not the black bars around it.
    val ratio = if (h > w) 3f / 4f else 4f / 3f
    val frameW = min(w.toFloat(), h * ratio)
    val frameH = frameW / ratio
    val crop = cropRect(frameW.roundToInt().coerceAtLeast(1), frameH.roundToInt().coerceAtLeast(1), aspect)
    val left = ((w - frameW) / 2f).roundToInt() + crop.left
    val top = ((h - frameH) / 2f).roundToInt() + crop.top
    val cw = crop.width().coerceAtMost(w - left).coerceAtLeast(1)
    val ch = crop.height().coerceAtMost(h - top).coerceAtLeast(1)
    val small = Bitmap.createScaledBitmap(Bitmap.createBitmap(preview, left, top, cw, ch), 48, 36, true)
    val pixels = IntArray(small.width * small.height)
    small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
    var sum = 0.0
    var weights = 0.0
    val sw = small.width
    val sh = small.height
    val spotX = (spot?.x ?: 0.5f).coerceIn(0f, 1f)
    val spotY = (spot?.y ?: 0.5f).coerceIn(0f, 1f)
    // Spot radius in the scaled image, at least one sample's worth.
    val spotR = max(SPOT_SIZE / 2f * min(sw, sh) / sh, 1.5f / sh)
    for (i in pixels.indices) {
        val c = pixels[i]
        val nx = ((i % sw) + 0.5f) / sw
        val ny = ((i / sw) + 0.5f) / sh
        val weight = when (metering) {
            MeteringMode.MATRIX -> 1.0
            // Gaussian falloff from the centre: the middle counts most, edges still a little.
            MeteringMode.CENTER -> {
                val dx = (nx - 0.5f) / 0.3f
                val dy = (ny - 0.5f) / 0.3f
                kotlin.math.exp(-0.5 * (dx * dx + dy * dy))
            }
            MeteringMode.SPOT -> {
                val dx = (nx - spotX) * sw / sh
                val dy = ny - spotY
                if (dx * dx + dy * dy <= spotR * spotR) 1.0 else 0.0
            }
        }
        if (weight == 0.0) continue
        sum += weight * (0.2126 * ((c shr 16) and 0xFF) + 0.7152 * ((c shr 8) and 0xFF) + 0.0722 * (c and 0xFF))
        weights += weight
    }
    if (weights == 0.0) return null
    return (sum / weights / 255.0).toFloat()
}
