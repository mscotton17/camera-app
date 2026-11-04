package com.example.cameraapp

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

private const val TAG = "PhotoStore"
private const val ALBUM = "CameraApp"
private const val MIME_JPEG = "image/jpeg"
private const val MIME_DNG = "image/x-adobe-dng"

/**
 * Captures into MediaStore (Pictures/CameraApp) as JPEG, DNG, or both. [imageCapture] must have
 * been built with [format]'s output format. EXIF patching runs on [ioExecutor]; [onDone] is
 * always invoked on the main thread once the capture has finished or failed.
 */
fun takePhoto(
    context: Context,
    imageCapture: ImageCapture,
    format: CaptureFormat,
    mirror: Boolean,
    telemetry: CameraTelemetry,
    recipe: FilmRecipe?,
    ioExecutor: Executor,
    onDone: () -> Unit
) {
    val mainExecutor = ContextCompat.getMainExecutor(context)
    // Same base name for both files of a RAW+JPEG pair.
    val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
        .format(System.currentTimeMillis())

    fun outputOptions(mimeType: String, applyMirror: Boolean): ImageCapture.OutputFileOptions {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$ALBUM")
            }
        }
        val builder = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        )
        // Front-camera preview is mirrored; mirror the saved JPEG too so it matches.
        if (applyMirror) builder.setMetadata(ImageCapture.Metadata().apply { isReversedHorizontal = mirror })
        return builder.build()
    }

    val finished = AtomicBoolean(false)
    val pending = AtomicInteger(if (format == CaptureFormat.RAW_JPEG) 2 else 1)

    val callback = object : ImageCapture.OnImageSavedCallback {
        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
            val uri = output.savedUri
            if (uri != null && output.imageFormat != ImageFormat.RAW_SENSOR) {
                // Film recipes apply to the JPEG only; a DNG stays untouched sensor data.
                if (recipe != null) {
                    try {
                        applyRecipeToJpeg(context, uri, recipe)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Applying ${recipe.label} failed; keeping the original JPEG", e)
                    }
                }
                try {
                    writeExifIfMissing(context, uri, telemetry)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not write EXIF exposure data", e)
                }
            }
            if (pending.decrementAndGet() > 0 || !finished.compareAndSet(false, true)) return
            val message = when (format) {
                CaptureFormat.JPEG -> "Photo saved"
                CaptureFormat.RAW -> "DNG saved"
                CaptureFormat.RAW_JPEG -> "DNG + JPEG saved"
            }
            mainExecutor.execute {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                onDone()
            }
        }

        override fun onError(exc: ImageCaptureException) {
            Log.e(TAG, "Photo capture failed", exc)
            if (!finished.compareAndSet(false, true)) return
            mainExecutor.execute {
                Toast.makeText(context, "Capture failed: ${exc.message}", Toast.LENGTH_SHORT).show()
                onDone()
            }
        }
    }

    try {
        when (format) {
            CaptureFormat.JPEG ->
                imageCapture.takePicture(outputOptions(MIME_JPEG, true), ioExecutor, callback)
            CaptureFormat.RAW ->
                imageCapture.takePicture(outputOptions(MIME_DNG, false), ioExecutor, callback)
            CaptureFormat.RAW_JPEG -> imageCapture.takePicture(
                outputOptions(MIME_DNG, false),
                outputOptions(MIME_JPEG, true),
                ioExecutor,
                callback
            )
        }
    } catch (e: Exception) {
        Log.e(TAG, "takePicture rejected", e)
        Toast.makeText(context, "Capture failed: ${e.message}", Toast.LENGTH_SHORT).show()
        onDone()
    }
}

/**
 * Most devices' camera HAL already embeds exposure EXIF in the JPEG. Fill in only the tags
 * that are missing (or zero) using the telemetry captured at shutter press.
 */
fun writeExifIfMissing(context: Context, uri: Uri, t: CameraTelemetry) {
    context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
        val exif = ExifInterface(pfd.fileDescriptor)
        var changed = false
        fun setIfMissing(tag: String, value: String?) {
            if (value != null && exif.getAttributeDouble(tag, 0.0) == 0.0) {
                exif.setAttribute(tag, value)
                changed = true
            }
        }
        // F_NUMBER and EXPOSURE_TIME accept decimal strings; ExifInterface converts to rationals.
        setIfMissing(ExifInterface.TAG_F_NUMBER, t.apertureF?.takeIf { it > 0f }?.toString())
        setIfMissing(
            ExifInterface.TAG_EXPOSURE_TIME,
            t.exposureTimeNs?.takeIf { it > 0L }
                ?.let { String.format(Locale.US, "%.9f", it / 1_000_000_000.0) }
        )
        setIfMissing(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, t.iso?.takeIf { it > 0 }?.toString())
        setIfMissing(
            ExifInterface.TAG_FOCAL_LENGTH,
            t.focalLengthMm?.takeIf { it > 0f }?.let { "${(it * 1000).roundToInt()}/1000" }
        )
        setIfMissing(
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            t.focalLength35mm?.takeIf { it > 0f }?.roundToInt()?.toString()
        )
        if (changed) exif.saveAttributes()
    }
}
