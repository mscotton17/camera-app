package com.example.cameraapp

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExtendableBuilder
import androidx.camera.core.ImageCapture
import androidx.camera.lifecycle.ProcessCameraProvider
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val TAG = "Lenses"

/**
 * One selectable lens. Phones often hide their extra lenses (ultra-wide, telephoto) behind a
 * single "logical" camera; those are exposed here with a [physicalId] so we can stream from
 * that lens directly instead of relying on zoom.
 */
data class Lens(
    /** Stable key for saving the selection. */
    val key: String,
    /** The camera CameraX binds to. */
    val cameraInfo: CameraInfo,
    /** Physical lens inside [cameraInfo] to stream from, or null to use the camera as-is. */
    val physicalId: String?,
    val facing: Int,
    /** 35mm-equivalent focal length, if the sensor size is known. */
    val equivFocalMm: Int?,
    /** Real (physical) focal length of the lens in mm. */
    val focalMm: Float?,
    val cropFactor: Float?,
    /** ImageCapture.OUTPUT_FORMAT_* values this lens can capture. */
    val supportedFormats: Set<Int>,
    val exposure: ExposureCaps,
)

/** What manual exposure control a lens allows. */
data class ExposureCaps(
    /** Shutter speed and ISO can be set manually (Camera2 MANUAL_SENSOR). */
    val manualSensor: Boolean,
    val exposureRangeNs: Range<Long>?,
    val isoRange: Range<Int>?,
    /** f-stops the lens can use; phone lenses almost always have exactly one. */
    val apertures: List<Float>,
) {
    val variableAperture: Boolean get() = apertures.size > 1
}

/** Ranges come from the lens itself; manual control is decided by the camera we send requests to. */
private fun exposureCaps(lensChars: CameraCharacteristics, cameraChars: CameraCharacteristics) =
    ExposureCaps(
        manualSensor = cameraChars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true,
        exposureRangeNs = lensChars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            ?: cameraChars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
        isoRange = lensChars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            ?: cameraChars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
        apertures = lensChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
            ?.toList()?.distinct()?.sorted().orEmpty(),
    )

data class LensSet(val lenses: List<Lens>, private val defaults: Map<Int, String>) {
    fun forFacing(facing: Int): List<Lens> = lenses.filter { it.facing == facing }

    fun defaultFor(facing: Int): Lens? =
        lenses.firstOrNull { it.key == defaults[facing] } ?: forFacing(facing).firstOrNull()

    fun byKey(key: String?): Lens? = lenses.firstOrNull { it.key == key }

    val hasFrontAndBack: Boolean
        get() = forFacing(CameraSelector.LENS_FACING_BACK).isNotEmpty() &&
            forFacing(CameraSelector.LENS_FACING_FRONT).isNotEmpty()
}

private fun CameraCharacteristics.cropFactor(): Float? {
    val size = get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
    val diagonal = sqrt(size.width * size.width + size.height * size.height)
    return if (diagonal > 0f) FULL_FRAME_DIAGONAL_MM / diagonal else null
}

private fun CameraCharacteristics.focalMm(): Float? =
    get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()

private fun CameraCharacteristics.equivFocalMm(crop: Float?): Int? {
    val focal = focalMm()
    return if (focal != null && crop != null) (focal * crop).roundToInt() else null
}

private fun CameraCharacteristics.hasRaw(): Boolean =
    get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true

private val RawFormats = setOf(ImageCapture.OUTPUT_FORMAT_RAW, ImageCapture.OUTPUT_FORMAT_RAW_JPEG)

/** Lists every back/front lens CameraX can open, sorted by focal length, without duplicates. */
@SuppressLint("UnsafeOptInUsageError") // Camera2CameraInfo is @ExperimentalCamera2Interop
fun enumerateLenses(context: Context, provider: ProcessCameraProvider): LensSet {
    val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val found = mutableListOf<Lens>()
    val defaults = mutableMapOf<Int, String>()

    for (info in provider.availableCameraInfos) {
        try {
            val facing = info.lensFacing
            if (facing != CameraSelector.LENS_FACING_BACK && facing != CameraSelector.LENS_FACING_FRONT) {
                continue
            }
            val logicalId = Camera2CameraInfo.from(info).cameraId
            val chars = manager.getCameraCharacteristics(logicalId)
            val formats = ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats +
                ImageCapture.OUTPUT_FORMAT_JPEG
            // CameraX lists the default camera for each facing first.
            defaults.putIfAbsent(facing, logicalId)

            val physicalIds =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) chars.physicalCameraIds else emptySet()
            if (physicalIds.size < 2) {
                val crop = chars.cropFactor()
                found += Lens(
                    logicalId, info, null, facing, chars.equivFocalMm(crop), chars.focalMm(), crop,
                    formats, exposureCaps(chars, chars)
                )
                continue
            }

            // The logical camera mirrors its default (main) lens's sensor; that lens is used
            // without a physical ID so it keeps all of the logical camera's capabilities.
            val logicalPixels = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val mainId = physicalIds.firstOrNull {
                manager.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) == logicalPixels
            }
            for (pid in physicalIds) {
                val pc = manager.getCameraCharacteristics(pid)
                val crop = pc.cropFactor()
                val isMain = pid == mainId
                found += Lens(
                    key = if (isMain) logicalId else "$logicalId/$pid",
                    cameraInfo = info,
                    physicalId = if (isMain) null else pid,
                    facing = facing,
                    equivFocalMm = pc.equivFocalMm(crop),
                    focalMm = pc.focalMm(),
                    cropFactor = crop,
                    supportedFormats = if (isMain || pc.hasRaw()) formats else formats - RawFormats,
                    exposure = exposureCaps(if (isMain) chars else pc, chars),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Skipping camera", e)
        }
    }

    // Some phones expose the same lens twice (as its own camera and inside a logical one).
    // Keep one per facing + focal length (within 2mm), preferring entries without a physical ID.
    val unique = mutableListOf<Lens>()
    for (lens in found.sortedBy { if (it.physicalId == null) 0 else 1 }) {
        val mm = lens.equivFocalMm
        val duplicate = mm != null && unique.any {
            it.facing == lens.facing && it.equivFocalMm != null && abs(it.equivFocalMm - mm) <= 2
        }
        if (!duplicate) unique += lens
    }
    // Drop sensor crops (e.g. Pixel's 2x "optical quality" modes): they use the same physical
    // lens as another entry (same real focal length) on a smaller part of its sensor, which
    // shows up as a larger crop factor. Only genuinely separate lenses remain.
    val realLenses = unique.filter { candidate -> unique.none { other -> isCropOf(candidate, other) } }
    return LensSet(realLenses.sortedBy { it.equivFocalMm ?: Int.MAX_VALUE }, defaults)
}

/** True if [crop] is a sensor crop of [parent]: same lens, smaller sensor area. */
private fun isCropOf(crop: Lens, parent: Lens): Boolean {
    if (crop === parent || crop.facing != parent.facing) return false
    val f1 = crop.focalMm ?: return false
    val f2 = parent.focalMm ?: return false
    val c1 = crop.cropFactor ?: return false
    val c2 = parent.cropFactor ?: return false
    return abs(f1 - f2) <= 0.02f * f2 && c1 > c2 * 1.3f
}

/** Streams this use case from one physical lens of a logical multi-camera (API 28+). */
@SuppressLint("UnsafeOptInUsageError", "NewApi")
fun <T> ExtendableBuilder<T>.usePhysicalCamera(physicalId: String) {
    Camera2Interop.Extender(this).setPhysicalCameraId(physicalId)
}
