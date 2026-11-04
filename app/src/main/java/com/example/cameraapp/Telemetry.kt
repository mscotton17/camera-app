package com.example.cameraapp

import android.annotation.SuppressLint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Subdued amber used by the on-screen display. */
val HudAmber = Color(0xFFE8B04A)

/** Live exposure parameters reported by the camera for each preview frame. */
data class CameraTelemetry(
    val apertureF: Float? = null,
    val exposureTimeNs: Long? = null,
    val iso: Int? = null,
    val focalLengthMm: Float? = null,
    /** 35mm-equivalent focal length; null if the sensor size is unknown. */
    val focalLength35mm: Float? = null,
)

/** Diagonal of a 36x24mm full-frame sensor. */
private const val FULL_FRAME_DIAGONAL_MM = 43.27f

/** Ratio of full-frame diagonal to this camera's sensor diagonal, used for 35mm equivalents. */
@SuppressLint("UnsafeOptInUsageError") // Camera2CameraInfo is marked @ExperimentalCamera2Interop
fun cropFactorOf(camera: Camera): Float? = try {
    val size = Camera2CameraInfo.from(camera.cameraInfo)
        .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
    val diagonal = size?.let { sqrt(it.width * it.width + it.height * it.height) } ?: 0f
    if (diagonal > 0f) FULL_FRAME_DIAGONAL_MM / diagonal else null
} catch (e: Exception) {
    null
}

fun formatAperture(f: Float?): String =
    if (f == null || f <= 0f) "f/--" else String.format(Locale.US, "f/%.1f", f)

/** 8_000_000ns -> "1/125s"; 500_000_000ns -> "0.5s"; 2_000_000_000ns -> "2s". */
fun formatShutter(ns: Long?): String {
    if (ns == null || ns <= 0L) return "--s"
    val seconds = ns / 1_000_000_000.0
    return if (seconds >= 0.5) {
        String.format(Locale.US, "%.1f", seconds).removeSuffix(".0") + "s"
    } else {
        "1/${(1.0 / seconds).roundToInt()}s"
    }
}

fun formatIso(iso: Int?): String = if (iso == null || iso <= 0) "ISO --" else "ISO $iso"

/** Phone lenses are physically short (e.g. 4.4mm), so keep one decimal below 10mm. */
fun formatFocalLength(mm: Float?): String = when {
    mm == null || mm <= 0f -> "--mm"
    mm < 10f -> String.format(Locale.US, "%.1fmm", mm).replace(".0mm", "mm")
    else -> String.format(Locale.US, "%.0fmm", mm)
}

/**
 * Receives Camera2 capture results for the preview stream and exposes them as Compose state.
 * UI updates are throttled to ~10 Hz; [latest] always holds the newest frame's values.
 */
class TelemetryTracker {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _state = mutableStateOf(CameraTelemetry())
    val state: State<CameraTelemetry> get() = _state

    @Volatile
    var latest: CameraTelemetry = CameraTelemetry()
        private set

    /** Set after binding a camera; see [cropFactorOf]. */
    @Volatile
    var cropFactor: Float? = null

    @Volatile
    private var lastPublishMs = 0L

    val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val focal: Float? = result.get(CaptureResult.LENS_FOCAL_LENGTH)
            val crop = cropFactor
            val t = CameraTelemetry(
                apertureF = result.get(CaptureResult.LENS_APERTURE),
                exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                focalLengthMm = focal,
                focalLength35mm = if (focal != null && crop != null) focal * crop else null,
            )
            latest = t
            val now = SystemClock.uptimeMillis()
            if (now - lastPublishMs >= 100L) {
                lastPublishMs = now
                mainHandler.post { if (_state.value != t) _state.value = t }
            }
        }
    }

    fun reset() {
        latest = CameraTelemetry()
        mainHandler.post { _state.value = CameraTelemetry() }
    }
}

/** Attaches [tracker]'s Camera2 capture callback to this Preview via Camera2Interop. */
@SuppressLint("UnsafeOptInUsageError") // Camera2Interop is marked @ExperimentalCamera2Interop
fun Preview.Builder.attachTelemetry(tracker: TelemetryTracker): Preview.Builder {
    Camera2Interop.Extender(this).setSessionCaptureCallback(tracker.captureCallback)
    return this
}

/** Retro mirrorless-style OSD bar: f/1.8  |  1/250s  |  ISO 100  |  24mm (35mm equivalent) */
@Composable
fun TelemetryHud(telemetry: CameraTelemetry, modifier: Modifier = Modifier) {
    val items = listOf(
        formatAperture(telemetry.apertureF),
        formatShutter(telemetry.exposureTimeNs),
        formatIso(telemetry.iso),
        // Prefer the 35mm equivalent; fall back to the physical focal length.
        formatFocalLength(telemetry.focalLength35mm ?: telemetry.focalLengthMm),
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.45f))
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { i, text ->
                if (i > 0) {
                    Box(
                        Modifier
                            .width(1.dp)
                            .height(14.dp)
                            .background(HudAmber.copy(alpha = 0.4f))
                    )
                }
                Text(
                    text = text,
                    color = HudAmber,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    letterSpacing = 0.5.sp,
                    maxLines = 1
                )
            }
        }
    }
}
