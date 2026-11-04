package com.example.cameraapp

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
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

/** Sends [settings] to the camera; they apply to the preview and to every still capture. */
@SuppressLint("UnsafeOptInUsageError") // Camera2CameraControl is @ExperimentalCamera2Interop
fun applyExposure(camera: Camera, settings: ExposureSettings, awbMode: Int? = null) {
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
    if (any) control.setCaptureRequestOptions(builder.build()) else control.clearCaptureRequestOptions()
}

/**
 * Mean brightness (0..1) of the camera image within a FIT_CENTER [preview] bitmap, measured
 * only inside the recipe's [aspect] crop so priority metering ignores what won't be saved.
 */
fun meanLuma(preview: Bitmap, aspect: AspectRatio = AspectRatio.NATIVE): Float? {
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
    for (c in pixels) {
        sum += 0.2126 * ((c shr 16) and 0xFF) + 0.7152 * ((c shr 8) and 0xFF) + 0.0722 * (c and 0xFF)
    }
    return (sum / pixels.size / 255.0).toFloat()
}
