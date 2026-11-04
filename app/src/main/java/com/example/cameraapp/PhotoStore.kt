package com.example.cameraapp

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.ImageFormat
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
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

/** "CHROME 2" -> "CHROME-2", for file names. */
private fun fileSafe(label: String) = label.uppercase(Locale.US).replace(Regex("[^A-Z0-9]+"), "-").trim('-').ifEmpty { "RECIPE" }

private fun albumValues(displayName: String, mime: String) = ContentValues().apply {
    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
    put(MediaStore.MediaColumns.MIME_TYPE, mime)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$ALBUM")
    }
}

/**
 * Captures into MediaStore (Pictures/CameraApp) as JPEG, DNG, or both. [imageCapture] must have
 * been built with [format]'s output format. Recipe and EXIF work runs on [ioExecutor]; [onDone]
 * is always invoked on the main thread once the capture has finished or failed.
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
    val time = System.currentTimeMillis()
    // Same base name for both files of a RAW+JPEG pair; the recipe name says which look it got.
    val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(time) +
        (recipe?.let { "_" + fileSafe(it.label) } ?: "")

    fun outputOptions(mimeType: String, applyMirror: Boolean): ImageCapture.OutputFileOptions {
        val builder = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            albumValues(name, mimeType)
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
                try {
                    writeExifIfMissing(context, uri, telemetry)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not write EXIF exposure data", e)
                }
                // Film recipes apply to the JPEG only; a DNG stays untouched sensor data.
                if (recipe != null) {
                    if (recipe.keepOriginal) {
                        try {
                            copyToAlbum(context, uri, name.substringBeforeLast('_') + "_STD", MIME_JPEG)
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not keep the unedited JPEG", e)
                        }
                    }
                    try {
                        applyRecipeToJpeg(context, uri, recipe, time)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Applying ${recipe.label} failed; keeping the original JPEG", e)
                    }
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

/** Copies the file at [uri] into the album as a new item. */
private fun copyToAlbum(context: Context, uri: Uri, displayName: String, mime: String) {
    val resolver = context.contentResolver
    val target = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, albumValues(displayName, mime)) ?: return
    resolver.openInputStream(uri)?.use { input ->
        resolver.openOutputStream(target)?.use { input.copyTo(it) }
    }
}

// ---------------------------------------------------------------------------------------------
// EXIF
// ---------------------------------------------------------------------------------------------

/** EXIF tags not carried over to a re-rendered image (they describe the old pixels). */
private val SkippedExifTags = setOf(
    ExifInterface.TAG_IMAGE_WIDTH, ExifInterface.TAG_IMAGE_LENGTH,
    ExifInterface.TAG_PIXEL_X_DIMENSION, ExifInterface.TAG_PIXEL_Y_DIMENSION,
    ExifInterface.TAG_ORIENTATION,
)

/** All EXIF tag names ExifInterface knows, except embedded-thumbnail bookkeeping. */
private val ExifTags: List<String> by lazy {
    ExifInterface::class.java.fields
        .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.name.startsWith("TAG_") && it.type == String::class.java }
        .mapNotNull { it.get(null) as? String }
        .filterNot { it.startsWith("JPEGInterchangeFormat") || it.startsWith("Thumbnail") || it in SkippedExifTags }
        .distinct()
}

private fun readExif(exif: ExifInterface): Map<String, String> =
    ExifTags.mapNotNull { tag -> exif.getAttribute(tag)?.let { tag to it } }.toMap()

private fun writeExif(context: Context, uri: Uri, values: Map<String, String>, orientation: Int, recipe: FilmRecipe) {
    context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
        val exif = ExifInterface(pfd.fileDescriptor)
        for ((tag, value) in values) {
            try {
                exif.setAttribute(tag, value)
            } catch (e: Exception) {
                Log.w(TAG, "Could not restore EXIF $tag", e)
            }
        }
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "CameraApp - ${recipe.label} recipe")
        exif.saveAttributes()
    }
}

// ---------------------------------------------------------------------------------------------
// Rendering recipes into files
// ---------------------------------------------------------------------------------------------

/**
 * Re-renders the JPEG at [uri] with [recipe]: decode, (turn upright if a border or date stamp
 * needs to know which way is down), crop, process, frame, re-encode, then restore the EXIF.
 */
fun applyRecipeToJpeg(context: Context, uri: Uri, recipe: FilmRecipe, timeMillis: Long) {
    val resolver = context.contentResolver
    var savedExif: Map<String, String> = emptyMap()
    var orientation = ExifInterface.ORIENTATION_NORMAL
    var rotation = 0
    var flipped = false
    resolver.openInputStream(uri)?.use { input ->
        val exif = ExifInterface(input)
        savedExif = readExif(exif)
        orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        rotation = exif.rotationDegrees
        flipped = exif.isFlipped
    }

    val options = BitmapFactory.Options().apply {
        inMutable = true
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    var bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return
    // Borders and date stamps have a "bottom", so render on the upright image.
    val makeUpright = recipe.border != BorderStyle.NONE || recipe.dateStamp
    var savedOrientation = orientation
    if (makeUpright && (rotation != 0 || flipped)) {
        bitmap = uprightBitmap(bitmap, rotation, flipped)
        savedOrientation = ExifInterface.ORIENTATION_NORMAL
    }
    val out = renderRecipe(context, bitmap, recipe, timeMillis)
    try {
        resolver.openOutputStream(uri, "wt")?.use { out.compress(Bitmap.CompressFormat.JPEG, recipe.jpegQuality, it) }
    } finally {
        if (out !== bitmap) out.recycle()
        bitmap.recycle()
    }
    writeExif(context, uri, savedExif, savedOrientation, recipe)
}

/**
 * Develops an existing photo (DNG or JPEG) with [recipe] into a new JPEG in the album.
 * Returns the new photo, or null if the file couldn't be read.
 */
fun developImage(context: Context, source: Uri, recipe: FilmRecipe): Uri? {
    val resolver = context.contentResolver
    val savedExif = try {
        resolver.openInputStream(source)?.use { readExif(ExifInterface(it)) }.orEmpty()
    } catch (e: Exception) {
        emptyMap()
    }
    val time = savedExif[ExifInterface.TAG_DATETIME_ORIGINAL]?.let {
        try {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(it)?.time
        } catch (e: Exception) {
            null
        }
    } ?: System.currentTimeMillis()

    // ImageDecoder renders DNGs and applies orientation, so the result is already upright.
    var bitmap: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, source)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }
    } else {
        val opts = BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val decoded = resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val exif = resolver.openInputStream(source)?.use { ExifInterface(it) }
        uprightBitmap(decoded, exif?.rotationDegrees ?: 0, exif?.isFlipped ?: false)
    }
    if (bitmap.config != Bitmap.Config.ARGB_8888 || !bitmap.isMutable) {
        val converted = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        bitmap.recycle()
        bitmap = converted
    }

    val sourceName = resolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }?.substringBeforeLast('.') ?: SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(time)
    val target = resolver.insert(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        albumValues("${sourceName}_${fileSafe(recipe.label)}", MIME_JPEG)
    ) ?: return null

    val out = renderRecipe(context, bitmap, recipe, time)
    try {
        resolver.openOutputStream(target)?.use { out.compress(Bitmap.CompressFormat.JPEG, recipe.jpegQuality, it) }
    } finally {
        if (out !== bitmap) out.recycle()
        bitmap.recycle()
    }
    writeExif(context, target, savedExif, ExifInterface.ORIENTATION_NORMAL, recipe)
    return target
}

// ---------------------------------------------------------------------------------------------
// Sharing recipes
// ---------------------------------------------------------------------------------------------

/** Opens the share sheet with [recipes] as a .json file. */
fun shareRecipes(context: Context, recipes: List<FilmRecipe>) {
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    val base = if (recipes.size == 1) fileSafe(recipes[0].label) else "recipes"
    val file = File(dir, "$base.camerarecipe.json")
    file.writeText(recipeFileText(recipes))
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "CameraApp recipe: ${recipes.joinToString { it.label }}")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share recipe").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Reads recipes from a shared file. */
fun importRecipes(context: Context, uri: Uri): List<FilmRecipe> {
    val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return emptyList()
    return parseRecipeFile(text)
}

// ---------------------------------------------------------------------------------------------
// Exposure EXIF
// ---------------------------------------------------------------------------------------------

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
