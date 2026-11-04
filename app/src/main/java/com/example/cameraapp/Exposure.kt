package com.example.cameraapp

import android.annotation.SuppressLint
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Shutter speeds offered in the picker (1/8000s .. 1s), filtered per lens. */
private val ShutterStopsNs: List<Long> =
    listOf(8000, 4000, 2000, 1000, 500, 250, 125, 60, 30, 15, 8, 4, 2)
        .map { 1_000_000_000L / it } + 1_000_000_000L

/** ISO values offered in the picker, filtered per lens. */
private val IsoStops = listOf(25, 50, 100, 200, 400, 800, 1600, 3200, 6400, 12800)

fun ExposureCaps.shutterOptions(): List<Long> =
    exposureRangeNs?.let { r -> ShutterStopsNs.filter { r.contains(it) } }.orEmpty()

fun ExposureCaps.isoOptions(): List<Int> =
    isoRange?.let { r -> IsoStops.filter { r.contains(it) } }.orEmpty()

/**
 * User exposure overrides. Everything is automatic by default.
 *
 * Camera2 has no shutter- or ISO-priority mode: turning off auto-exposure means fixing both.
 * So when only one of them is picked, the other is derived to keep the current brightness
 * and then held ([shutterLocked]/[isoLocked] record which ones the user chose).
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
fun applyExposure(camera: Camera, settings: ExposureSettings) {
    val control = Camera2CameraControl.from(camera.cameraControl)
    val shutter = settings.shutterNs
    val iso = settings.iso
    val aperture = settings.aperture
    if (shutter == null || iso == null) {
        if (aperture == null) {
            control.clearCaptureRequestOptions()
        } else {
            control.setCaptureRequestOptions(
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.LENS_APERTURE, aperture)
                    .build()
            )
        }
        return
    }
    val builder = CaptureRequestOptions.Builder()
        .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
        .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
        // Long exposures need a long enough frame; otherwise keep ~30fps.
        .setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, max(shutter, 33_333_333L))
    if (aperture != null) builder.setCaptureRequestOption(CaptureRequest.LENS_APERTURE, aperture)
    control.setCaptureRequestOptions(builder.build())
}
