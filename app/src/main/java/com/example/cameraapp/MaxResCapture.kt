package com.example.cameraapp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private const val TAG = "MaxResCapture"

/** Everything the full-resolution shot needs, captured from the CameraX state at the shutter press. */
data class MaxResRequest(
    val cameraId: String,
    val info: MaxResInfo,
    /** Display rotation in degrees (0/90/180/270), to orient the JPEG like CameraX would. */
    val displayRotationDegrees: Int,
    val frontFacing: Boolean,
    /** Exposure compensation in EV (the screen's metering EV, including DR underexposure). */
    val ev: Float,
    /** Manual shutter (ns) and ISO, or null for auto-exposure. */
    val manualShutterNs: Long?,
    val manualIso: Int?,
    /** Camera2 CONTROL_AWB_MODE, or null for auto. */
    val awbMode: Int?,
    val jpegQuality: Int = 95,
)

/**
 * Takes one full-resolution JPEG directly through Camera2. Call only after CameraX has
 * released the camera. It opens the camera with two outputs: a small normal-mode stream that
 * runs a few frames so auto-exposure, focus and white balance settle, and the full-resolution
 * JPEG stream in maximum-resolution sensor mode for the single still. Everything is closed
 * again before returning the JPEG bytes.
 */
@RequiresApi(31)
suspend fun captureMaxResolution(context: Context, req: MaxResRequest): ByteArray = withContext(Dispatchers.IO) {
    val manager = context.getSystemService(CameraManager::class.java)
    val chars = manager.getCameraCharacteristics(req.cameraId)
    val thread = HandlerThread("MaxResCapture").apply { start() }
    val handler = Handler(thread.looper)
    val executor = Executor { handler.post(it) }

    val size = req.info.size
    val jpegReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
    val meterSize = meteringSize(chars)
    val meterReader = ImageReader.newInstance(meterSize.width, meterSize.height, ImageFormat.PRIVATE, 4)
    meterReader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, handler)

    var device: CameraDevice? = null
    var session: CameraCaptureSession? = null
    try {
        waitUntilAvailable(manager, req.cameraId, handler)
        device = openWithRetry(manager, req.cameraId, handler)

        val physicalId = req.info.physicalId
        val jpegOut = OutputConfiguration(jpegReader.surface).apply {
            addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
            physicalId?.let { setPhysicalCameraId(it) }
        }
        val meterOut = OutputConfiguration(meterReader.surface).apply {
            addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT)
            physicalId?.let { setPhysicalCameraId(it) }
        }
        session = createSession(device, listOf(meterOut, jpegOut), executor)

        val aeIndex = aeCompensationIndex(chars, req.ev)
        fun CaptureRequest.Builder.apply3A() {
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            if (afModes?.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true) {
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            val shutter = req.manualShutterNs
            val iso = req.manualIso
            if (shutter != null && iso != null) {
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
                set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                set(CaptureRequest.SENSOR_FRAME_DURATION, max(shutter, 33_333_333L))
            } else {
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, aeIndex)
            }
            set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
            req.awbMode?.let { set(CaptureRequest.CONTROL_AWB_MODE, it) }
        }

        // 1. Let 3A settle on the small normal-mode stream.
        val settled = CompletableDeferred<Unit>()
        var frames = 0
        val meterRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(meterReader.surface)
            apply3A()
            set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT)
        }.build()
        session.setRepeatingRequest(meterRequest, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                frames++
                val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                val af = result.get(CaptureResult.CONTROL_AF_STATE)
                val aeOk = ae == null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                    ae == CaptureResult.CONTROL_AE_STATE_LOCKED || ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                    req.manualShutterNs != null
                val afOk = af == null || af == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
                    af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || af == CaptureResult.CONTROL_AF_STATE_INACTIVE
                if (frames >= 4 && aeOk && afOk) settled.complete(Unit)
            }
        }, handler)
        withTimeoutOrNull(1500) { settled.await() }

        // 2. One full-resolution still.
        val jpeg = CompletableDeferred<ByteArray>()
        jpegReader.setOnImageAvailableListener({ r ->
            try {
                r.acquireNextImage()?.use { image ->
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    jpeg.complete(bytes)
                }
            } catch (e: Exception) {
                jpeg.completeExceptionally(e)
            }
        }, handler)
        val still = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(jpegReader.surface)
            apply3A()
            set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
            set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation(req.info.sensorOrientation, req.displayRotationDegrees, req.frontFacing))
            set(CaptureRequest.JPEG_QUALITY, req.jpegQuality.coerceIn(1, 100).toByte())
        }.build()
        session.stopRepeating()
        session.capture(still, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                jpeg.completeExceptionally(IllegalStateException("Full-resolution capture failed (reason ${failure.reason})"))
            }
        }, handler)
        withTimeout(10_000) { jpeg.await() }
    } finally {
        try { session?.close() } catch (e: Exception) { Log.w(TAG, "Session close", e) }
        try { device?.close() } catch (e: Exception) { Log.w(TAG, "Device close", e) }
        jpegReader.close()
        meterReader.close()
        thread.quitSafely()
    }
}

/** A small 4:3 size for the metering stream. */
private fun meteringSize(chars: CameraCharacteristics): Size {
    val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    val sizes = map?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
    return sizes
        .filter { abs(it.width.toFloat() / it.height - 4f / 3f) < 0.02f && it.width >= 320 }
        .minByOrNull { it.width * it.height }
        ?: sizes.minByOrNull { it.width * it.height }
        ?: Size(640, 480)
}

/** Same rule CameraX uses to orient JPEGs relative to the display. */
private fun jpegOrientation(sensorOrientation: Int, displayRotationDegrees: Int, frontFacing: Boolean): Int =
    if (frontFacing) (sensorOrientation + displayRotationDegrees) % 360
    else (sensorOrientation - displayRotationDegrees + 360) % 360

private fun aeCompensationIndex(chars: CameraCharacteristics, ev: Float): Int {
    val step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: return 0
    val range = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: return 0
    if (step <= 0f) return 0
    return range.clamp((ev / step).roundToInt())
}

/** Waits (up to 3 s) until no client, including CameraX, has the camera open. */
private suspend fun waitUntilAvailable(manager: CameraManager, id: String, handler: Handler) {
    val available = CompletableDeferred<Unit>()
    val callback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            if (cameraId == id) available.complete(Unit)
        }
    }
    manager.registerAvailabilityCallback(callback, handler)
    try {
        withTimeoutOrNull(3000) { available.await() }
    } finally {
        manager.unregisterAvailabilityCallback(callback)
    }
}

/** Opens the camera, retrying briefly in case CameraX is still letting go of it. */
private suspend fun openWithRetry(manager: CameraManager, id: String, handler: Handler): CameraDevice {
    var last: Exception? = null
    repeat(6) { attempt ->
        try {
            return open(manager, id, handler)
        } catch (e: Exception) {
            last = e
            Log.w(TAG, "Open attempt ${attempt + 1} failed", e)
            delay(250)
        }
    }
    throw last ?: CameraAccessException(CameraAccessException.CAMERA_ERROR)
}

@SuppressLint("MissingPermission") // The camera screen only runs with the camera permission.
private suspend fun open(manager: CameraManager, id: String, handler: Handler): CameraDevice =
    suspendCancellableCoroutine { cont ->
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (cont.isActive) cont.resume(camera) else camera.close()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                if (cont.isActive) cont.resumeWithException(CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED))
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                if (cont.isActive) cont.resumeWithException(CameraAccessException(CameraAccessException.CAMERA_ERROR, "Camera error $error"))
            }
        }, handler)
    }

@RequiresApi(31)
private suspend fun createSession(
    device: CameraDevice,
    outputs: List<OutputConfiguration>,
    executor: Executor,
): CameraCaptureSession = suspendCancellableCoroutine { cont ->
    val config = SessionConfiguration(
        SessionConfiguration.SESSION_REGULAR, outputs, executor,
        object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (cont.isActive) cont.resume(session) else session.close()
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                if (cont.isActive) cont.resumeWithException(IllegalStateException("This camera won't configure a full-resolution session"))
            }
        }
    )
    device.createCaptureSession(config)
}
