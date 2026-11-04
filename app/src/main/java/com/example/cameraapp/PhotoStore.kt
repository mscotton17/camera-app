package com.example.cameraapp

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.roundToInt

private const val TAG = "PhotoStore"
private const val ALBUM = "CameraApp"

data class SavedPhoto(val uri: Uri, val thumbnail: Bitmap?)

/**
 * Captures a JPEG into MediaStore (Pictures/CameraApp). Disk work (EXIF patching, thumbnail
 * decoding) runs on [ioExecutor]; [onResult] is always invoked on the main thread, with null
 * on failure.
 */
fun takePhoto(
    context: Context,
    imageCapture: ImageCapture,
    mirror: Boolean,
    telemetry: CameraTelemetry,
    ioExecutor: Executor,
    onResult: (SavedPhoto?) -> Unit
) {
    val mainExecutor = ContextCompat.getMainExecutor(context)
    val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
        .format(System.currentTimeMillis())
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$ALBUM")
        }
    }
    val outputOptions = ImageCapture.OutputFileOptions.Builder(
        context.contentResolver,
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        contentValues
    )
        // Front-camera preview is mirrored; mirror the saved photo too so it matches.
        .setMetadata(ImageCapture.Metadata().apply { isReversedHorizontal = mirror })
        .build()

    imageCapture.takePicture(
        outputOptions,
        ioExecutor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val uri = output.savedUri
                if (uri == null) {
                    mainExecutor.execute { onResult(null) }
                    return
                }
                try {
                    writeExifIfMissing(context, uri, telemetry)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not write EXIF exposure data", e)
                }
                val thumb = loadThumbnail(context, uri)
                mainExecutor.execute {
                    Toast.makeText(context, "Photo saved", Toast.LENGTH_SHORT).show()
                    onResult(SavedPhoto(uri, thumb))
                }
            }

            override fun onError(exc: ImageCaptureException) {
                Log.e(TAG, "Photo capture failed", exc)
                mainExecutor.execute {
                    Toast.makeText(context, "Capture failed: ${exc.message}", Toast.LENGTH_SHORT)
                        .show()
                    onResult(null)
                }
            }
        }
    )
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
        if (changed) exif.saveAttributes()
    }
}

fun loadThumbnail(context: Context, uri: Uri, sizePx: Int = 192): Bitmap? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
    } else {
        decodeSampled(context, uri, sizePx)
    }
} catch (e: Exception) {
    Log.w(TAG, "Thumbnail load failed for $uri", e)
    null
}

private fun decodeSampled(context: Context, uri: Uri, sizePx: Int): Bitmap? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) {
        sample *= 2
    }
    val bitmap = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    val rotation = resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
    if (rotation == 0) return bitmap
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

/** Newest photo in Pictures/CameraApp that this app can see, or null. */
@Suppress("DEPRECATION") // MediaStore DATA column is only used on API < 29
fun queryLatestPhoto(context: Context): Uri? = try {
    val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val (selection, args) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?" to arrayOf("Pictures/$ALBUM%")
    } else {
        "${MediaStore.Images.Media.DATA} LIKE ?" to arrayOf("%/Pictures/$ALBUM/%")
    }
    context.contentResolver.query(
        collection,
        arrayOf(MediaStore.Images.Media._ID),
        selection,
        args,
        "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"
    )?.use { c ->
        if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null
    }
} catch (e: Exception) {
    Log.w(TAG, "Latest photo query failed", e)
    null
}

fun openInGallery(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "image/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No gallery app found", Toast.LENGTH_SHORT).show()
    }
}
