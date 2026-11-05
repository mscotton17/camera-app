package com.example.cameraapp

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import android.util.Size
import android.graphics.ImageFormat
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
    /** 4:3 JPEG sizes this lens offers apps, largest first (one per megapixel count). */
    val jpegSizes: List<JpegSize> = emptyList(),
    /** The sensor's full resolution in megapixels, if it reports one larger than apps get. */
    val sensorMaxMp: Int? = null,
    /** Camera2 id CameraX opens for this lens (the logical camera). */
    val cameraId: String = "",
    /** Full-resolution ("50 MP") capture through Camera2, when the sensor supports it. */
    val maxRes: MaxResInfo? = null,
    /** What the camera reports about full-resolution mode (diagnostics). */
    val fullResReport: String? = null,
)

/**
 * How to take a full-resolution shot with Camera2: open [Lens.cameraId], stream [size] JPEG in
 * maximum-resolution sensor mode, from [physicalId] when it's one lens of a logical camera.
 */
data class MaxResInfo(
    val size: Size,
    val physicalId: String?,
    val sensorOrientation: Int,
    /** ImageFormat.JPEG, or YUV_420_888 when the sensor only offers YUV at full resolution. */
    val format: Int,
)

/** "12.5 MP" / "50 MP". */
fun mpLabel(width: Int, height: Int): String {
    val tenths = ((width.toLong() * height + 50_000L) / 100_000L).toInt()
    return if (tenths % 10 == 0 || tenths >= 200) "${(tenths + 5) / 10} MP" else "${tenths / 10}.${tenths % 10} MP"
}

/**
 * The best full-resolution output: needs either the ULTRA_HIGH_RESOLUTION_SENSOR capability or
 * SENSOR_PIXEL_MODE as a supported request key (Android allows either), plus a maximum-
 * resolution stream map. JPEG preferred; YUV accepted (the app encodes the JPEG).
 */
private fun CameraCharacteristics.maxResOutput(): Pair<Size, Int>? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val caps = get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
    val hasCapability = caps?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR) == true
    val hasPixelModeKey = availableCaptureRequestKeys.contains(CaptureRequest.SENSOR_PIXEL_MODE)
    if (!hasCapability && !hasPixelModeKey) return null
    val map = get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION) ?: return null
    fun best(format: Int): Size? = map.getOutputSizes(format).orEmpty()
        .filter { abs(it.width.toFloat() / it.height - 4f / 3f) < 0.02f }
        .maxByOrNull { it.width.toLong() * it.height }
    // The full-resolution map often repeats the normal sizes for JPEG and lists the true full
    // size only for YUV / RAW, so take the largest across JPEG and YUV (JPEG when it ties).
    val jpeg = best(ImageFormat.JPEG)
    val yuv = best(ImageFormat.YUV_420_888)
    fun area(s: Size?) = s?.let { it.width.toLong() * it.height } ?: 0L
    return when {
        jpeg == null && yuv == null -> null
        area(jpeg) >= area(yuv) -> jpeg!! to ImageFormat.JPEG
        else -> yuv!! to ImageFormat.YUV_420_888
    }
}

/** What this camera reports about full-resolution mode, for the PRO panel's diagnostic line. */
private fun CameraCharacteristics.fullResReport(label: String): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return "$label: needs Android 12"
    val caps = get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
    val cap = caps?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR) == true
    val key = availableCaptureRequestKeys.contains(CaptureRequest.SENSOR_PIXEL_MODE)
    val map = get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
    fun sizes(format: Int) = map?.getOutputSizes(format)?.maxByOrNull { it.width.toLong() * it.height }
        ?.let { "${it.width}x${it.height}" } ?: "none"
    return "$label: capability ${if (cap) "yes" else "no"}, pixel-mode key ${if (key) "yes" else "no"}, " +
        if (map == null) "no full-res map" else "JPEG ${sizes(ImageFormat.JPEG)}, YUV ${sizes(ImageFormat.YUV_420_888)}, RAW ${sizes(ImageFormat.RAW_SENSOR)}"
}

/** Full-resolution info from [primary], or from [fallback] (a physical lens) if only it has it. */
private fun maxResInfo(
    primary: CameraCharacteristics, primaryPhysicalId: String?,
    fallback: CameraCharacteristics? = null, fallbackPhysicalId: String? = null,
): MaxResInfo? {
    primary.maxResOutput()?.let { (size, format) ->
        return MaxResInfo(size, primaryPhysicalId, primary.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90, format)
    }
    if (fallback == null) return null
    return fallback.maxResOutput()?.let { (size, format) ->
        MaxResInfo(size, fallbackPhysicalId, fallback.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90, format)
    }
}

/** Puts the full-resolution size first when it's bigger than anything CameraX offers. */
private fun withMaxRes(sizes: List<JpegSize>, maxRes: MaxResInfo?): List<JpegSize> {
    val m = maxRes ?: return sizes
    val top = sizes.firstOrNull()
    if (top != null && m.size.width.toLong() * m.size.height <= top.width.toLong() * top.height) return sizes
    return listOf(JpegSize(m.size.width, m.size.height, highRes = false, maxRes = true)) + sizes
}

/**
 * A JPEG capture size. [highRes] sizes need CameraX's "prefer higher resolution" mode;
 * [maxRes] is the sensor's full resolution, captured through the Camera2 single-shot path.
 */
data class JpegSize(val width: Int, val height: Int, val highRes: Boolean, val maxRes: Boolean = false) {
    /** Megapixels in tenths (125 = 12.5 MP); identifies a size in the picker. */
    val mpTenths: Int get() = ((width.toLong() * height + 50_000L) / 100_000L).toInt()
    /** "12.5 MP", "50 MP". */
    val label: String get() = mpLabel(width, height)
    val size: Size get() = Size(width, height)
}

private fun CameraCharacteristics.jpegSizes(): List<JpegSize> {
    val map = get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
    val normal = map.getOutputSizes(ImageFormat.JPEG).orEmpty().map { JpegSize(it.width, it.height, false) }
    val high = map.getHighResolutionOutputSizes(ImageFormat.JPEG).orEmpty().map { JpegSize(it.width, it.height, true) }
    return (high + normal)
        .filter { abs(it.width.toFloat() / it.height - 4f / 3f) < 0.02f }
        .sortedByDescending { it.width.toLong() * it.height }
        .distinctBy { it.mpTenths }
        .filter { it.mpTenths >= 20 }
        .take(4)
}

/** Full sensor resolution (Android 12+ "maximum resolution" mode), in megapixels. */
private fun CameraCharacteristics.sensorMaxMp(): Int? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val size = get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION) ?: return null
    return ((size.width.toLong() * size.height + 500_000L) / 1_000_000L).toInt()
}

/** What manual exposure control a lens allows. */
data class ExposureCaps(
    /** Shutter speed and ISO can be set manually (Camera2 MANUAL_SENSOR). */
    val manualSensor: Boolean,
    val exposureRangeNs: Range<Long>?,
    val isoRange: Range<Int>?,
    /** f-stops the lens can use; phone lenses almost always have exactly one. */
    val apertures: List<Float>,
    /** White-balance modes the camera accepts (Camera2 CONTROL_AWB_MODE_*). */
    val awbModes: Set<Int> = emptySet(),
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
        awbModes = cameraChars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toSet().orEmpty(),
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
                val maxRes = maxResInfo(chars, null)
                found += Lens(
                    logicalId, info, null, facing, chars.equivFocalMm(crop), chars.focalMm(), crop,
                    formats, exposureCaps(chars, chars),
                    jpegSizes = withMaxRes(chars.jpegSizes(), maxRes), sensorMaxMp = chars.sensorMaxMp(),
                    cameraId = logicalId, maxRes = maxRes,
                    fullResReport = chars.fullResReport("Camera $logicalId"),
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
                // The main lens is used through the logical camera; if only its physical sensor
                // reports full-resolution mode, target that physical camera for 50 MP shots.
                val maxRes = if (isMain) maxResInfo(chars, null, pc, pid) else maxResInfo(pc, pid)
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
                    jpegSizes = withMaxRes((if (isMain) chars else pc).jpegSizes(), maxRes),
                    sensorMaxMp = (if (isMain) chars else pc).sensorMaxMp(),
                    cameraId = logicalId,
                    maxRes = maxRes,
                    fullResReport = if (isMain) {
                        chars.fullResReport("Camera $logicalId") + "; " + pc.fullResReport("lens $pid")
                    } else {
                        pc.fullResReport("Lens $pid")
                    },
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
