package com.example.cameraapp

import android.annotation.SuppressLint
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
    /** Digital gain the camera applies after RAW (100 = none); a DNG is darker by this much. */
    val postRawBoost: Int? = null,
)

/** Diagonal of a 36x24mm full-frame sensor. */
internal const val FULL_FRAME_DIAGONAL_MM = 43.27f

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

    /** Crop factor of the bound lens, for the 35mm-equivalent focal length. */
    @Volatile
    var cropFactor: Float? = null

    /** When streaming from one physical lens, read that lens's own per-frame results. */
    @Volatile
    var physicalId: String? = null

    @Volatile
    private var lastPublishMs = 0L

    val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            total: TotalCaptureResult
        ) {
            val pid = physicalId
            @Suppress("DEPRECATION") // getPhysicalCameraTotalResults needs API 31
            val result: CaptureResult =
                if (pid != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    total.physicalCameraResults[pid] ?: total
                } else {
                    total
                }
            val focal: Float? = result.get(CaptureResult.LENS_FOCAL_LENGTH)
            val crop = cropFactor
            val t = CameraTelemetry(
                apertureF = result.get(CaptureResult.LENS_APERTURE),
                exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                focalLengthMm = focal,
                focalLength35mm = if (focal != null && crop != null) focal * crop else null,
                postRawBoost = result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST),
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

/** Adjustable values; EV and the film recipe live in the toggle row rather than the bar itself. */
enum class HudItem { APERTURE, SHUTTER, ISO, FOCAL, EV, RECIPE }

/**
 * Retro mirrorless-style OSD bar: f/1.8  |  1/250s  |  ISO 100  |  24mm (35mm equivalent).
 * Items in [pickable] are outlined buttons that call [onItemClick]; [openItem] is highlighted.
 * Values locked by the user are drawn in white; automatic ones (camera or app metered) in amber.
 */
@Composable
fun TelemetryHud(
    telemetry: CameraTelemetry,
    modifier: Modifier = Modifier,
    settings: ExposureSettings = ExposureSettings(),
    lensFocalMm: Int? = null,
    pickable: Set<HudItem> = emptySet(),
    openItem: HudItem? = null,
    onItemClick: (HudItem) -> Unit = {},
    /** Stack the values top to bottom, for the landscape side column. */
    vertical: Boolean = false,
) {
    val shutterNs = settings.shutterNs
    val iso = settings.iso
    val items = listOf(
        Triple(HudItem.APERTURE, formatAperture(settings.aperture ?: telemetry.apertureF), settings.aperture != null),
        Triple(
            HudItem.SHUTTER,
            formatShutter(shutterNs ?: telemetry.exposureTimeNs),
            settings.shutterLocked
        ),
        Triple(HudItem.ISO, formatIso(iso ?: telemetry.iso), settings.isoLocked),
        // The selected lens's focal length so it matches the picker; otherwise live values.
        Triple(
            HudItem.FOCAL,
            lensFocalMm?.let { "${it}mm" }
                ?: formatFocalLength(telemetry.focalLength35mm ?: telemetry.focalLengthMm),
            false
        ),
    )
    val itemContent: @Composable (HudItem, String, Boolean) -> Unit = { item, text, fixed ->
        val color = if (fixed) Color.White else HudAmber
        if (item in pickable) {
            val open = item == openItem
            val shape = RoundedCornerShape(50)
            Box(
                modifier = Modifier
                    .clip(shape)
                    .background(if (open) HudAmber.copy(alpha = 0.25f) else Color.Transparent)
                    .border(1.dp, HudAmber.copy(alpha = if (open) 0f else 0.35f), shape)
                    .clickable { onItemClick(item) }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .semantics { contentDescription = "$text, tap to change" }
            ) {
                HudText(text, color = color)
            }
        } else {
            HudText(text, Modifier.padding(horizontal = if (vertical) 7.dp else 0.dp, vertical = 5.dp), color = color)
        }
    }
    if (vertical) {
        // Landscape: one value per line in the side bar, clear of the image.
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEach { (item, text, fixed) -> itemContent(item, text, fixed) }
        }
        return
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.45f))
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { i, (item, text, fixed) ->
                if (i > 0) {
                    Box(
                        Modifier
                            .width(1.dp)
                            .height(14.dp)
                            .background(HudAmber.copy(alpha = 0.4f))
                    )
                }
                itemContent(item, text, fixed)
            }
        }
    }
}

@Composable
private fun HudText(text: String, modifier: Modifier = Modifier, color: Color = HudAmber) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        letterSpacing = 0.5.sp,
        maxLines = 1
    )
}
