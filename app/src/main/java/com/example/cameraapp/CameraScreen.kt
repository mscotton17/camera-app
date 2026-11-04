package com.example.cameraapp

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.platform.LocalConfiguration
import android.net.Uri
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "CameraScreen"

/** Preview and capture both use the sensor's native 4:3 so the viewfinder shows the exact frame. */
private val FourByThree = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
    .build()

/** True if [p] lies inside the 4:3 image letterboxed (FIT_CENTER) within a [w]x[h] view. */
private fun insidePreviewFrame(w: Float, h: Float, p: Offset): Boolean {
    val ratio = if (h > w) 3f / 4f else 4f / 3f // width / height of the visible image
    val frameW = min(w, h * ratio)
    val frameH = frameW / ratio
    val left = (w - frameW) / 2f
    val top = (h - frameH) / 2f
    return p.x in left..(left + frameW) && p.y in top..(top + frameH)
}

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(FourByThree)
            .build()
    }
    val telemetry = remember { TelemetryTracker() }
    val ioExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { ioExecutor.shutdown() } }

    var capturing by remember { mutableStateOf(false) }
    var lensFacing by rememberSaveable { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var flash by rememberSaveable { mutableStateOf(FlashSetting.AUTO) }
    var canSwitchCamera by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var lastPhoto by remember { mutableStateOf<Uri?>(null) }
    var lastThumb by remember { mutableStateOf<ImageBitmap?>(null) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusKey by remember { mutableIntStateOf(0) }
    val focusScale = remember { Animatable(1f) }
    val hud by telemetry.state

    LaunchedEffect(flash) { imageCapture.flashMode = flash.mode }

    // Show the newest existing photo in the thumbnail on launch.
    LaunchedEffect(Unit) {
        val found = withContext(Dispatchers.IO) {
            queryLatestPhoto(context)?.let { uri -> uri to loadThumbnail(context, uri) }
        }
        if (found != null && lastPhoto == null) {
            lastPhoto = found.first
            lastThumb = found.second?.asImageBitmap()
        }
    }

    // Focus ring: pop in, settle, then fade away.
    LaunchedEffect(focusKey) {
        if (focusKey == 0) return@LaunchedEffect
        focusScale.snapTo(1.4f)
        focusScale.animateTo(1f, tween(250))
        delay(900)
        focusPoint = null
    }

    // Bind Preview + ImageCapture to the lifecycle; rebinds whenever lensFacing changes
    // and unbinds when this composable leaves.
    DisposableEffect(lifecycleOwner, lensFacing) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()
            provider = cameraProvider
            val hasBack = cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
            val hasFront = cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
            canSwitchCamera = hasBack && hasFront

            // Fall back to whichever camera exists if the requested one doesn't.
            val selector = when {
                lensFacing == CameraSelector.LENS_FACING_FRONT && hasFront ->
                    CameraSelector.DEFAULT_FRONT_CAMERA
                hasBack -> CameraSelector.DEFAULT_BACK_CAMERA
                else -> CameraSelector.DEFAULT_FRONT_CAMERA
            }
            val preview = Preview.Builder()
                .setResolutionSelector(FourByThree)
                .attachTelemetry(telemetry)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            try {
                cameraProvider.unbindAll()
                telemetry.reset()
                val bound = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
                telemetry.cropFactor = cropFactorOf(bound)
                camera = bound
                zoomRatio = 1f
            } catch (e: Exception) {
                Log.e(TAG, "Use case binding failed", e)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            provider?.unbindAll()
            camera = null
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Gesture layer over the preview: pinch to zoom, tap to focus/meter.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(camera) {
                    detectTransformGestures { _, _, zoom, _ ->
                        val cam = camera ?: return@detectTransformGestures
                        val zs = cam.cameraInfo.zoomState.value ?: return@detectTransformGestures
                        val newRatio = (zoomRatio * zoom).coerceIn(zs.minZoomRatio, zs.maxZoomRatio)
                        if (newRatio != zoomRatio) {
                            zoomRatio = newRatio
                            cam.cameraControl.setZoomRatio(newRatio)
                        }
                    }
                }
                .pointerInput(camera) {
                    detectTapGestures { offset ->
                        val cam = camera ?: return@detectTapGestures
                        // Ignore taps on the black bars around the image.
                        if (!insidePreviewFrame(size.width.toFloat(), size.height.toFloat(), offset)) {
                            return@detectTapGestures
                        }
                        val point = previewView.meteringPointFactory.createPoint(offset.x, offset.y)
                        val action = FocusMeteringAction.Builder(
                            point,
                            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                        ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                        cam.cameraControl.startFocusAndMetering(action)
                        focusPoint = offset
                        focusKey++
                    }
                }
        )

        focusPoint?.let { p ->
            Box(
                modifier = Modifier
                    .offset {
                        val half = 32.dp.toPx()
                        IntOffset((p.x - half).roundToInt(), (p.y - half).roundToInt())
                    }
                    .size(64.dp)
                    .graphicsLayer {
                        scaleX = focusScale.value
                        scaleY = focusScale.value
                    }
                    .border(1.5.dp, HudAmber, CircleShape)
            )
        }

        // Top: exposure OSD, with the flash toggle beneath it.
        Column(modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
            TelemetryHud(hud)
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                FlashButton(
                    setting = flash,
                    onClick = { flash = flash.next() },
                    modifier = Modifier.padding(start = 12.dp, top = 12.dp)
                )
            }
        }

        val zoomChip: @Composable () -> Unit = {
            if (camera != null) {
                ZoomChip(
                    ratio = zoomRatio,
                    onClick = {
                        zoomRatio = 1f
                        camera?.cameraControl?.setZoomRatio(1f)
                    }
                )
            }
        }
        val thumbnailButton: @Composable (Modifier) -> Unit = { m ->
            ThumbnailButton(
                thumbnail = lastThumb,
                onClick = { lastPhoto?.let { openInGallery(context, it) } },
                modifier = m
            )
        }
        val shutterButton: @Composable (Modifier) -> Unit = { m ->
            ShutterButton(
                enabled = !capturing,
                onClick = {
                    capturing = true
                    takePhoto(
                        context = context,
                        imageCapture = imageCapture,
                        mirror = lensFacing == CameraSelector.LENS_FACING_FRONT,
                        telemetry = telemetry.latest,
                        ioExecutor = ioExecutor
                    ) { saved ->
                        capturing = false
                        if (saved != null) {
                            lastPhoto = saved.uri
                            saved.thumbnail?.let { lastThumb = it.asImageBitmap() }
                        }
                    }
                },
                modifier = m
            )
        }
        val switchButton: @Composable (Modifier) -> Unit = { m ->
            if (canSwitchCamera) {
                SwitchCameraButton(
                    enabled = !capturing,
                    onClick = {
                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }
                    },
                    modifier = m
                )
            } else {
                Spacer(m.size(52.dp))
            }
        }

        val isLandscape =
            LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (isLandscape) {
            // Landscape: controls stacked on the right edge, shutter centred under the thumb,
            // zoom chip just inside it (toward the image).
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Vertical)
                    )
                    .padding(end = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                zoomChip()
                Spacer(Modifier.width(16.dp))
                Column(
                    verticalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    switchButton(Modifier)
                    shutterButton(Modifier)
                    thumbnailButton(Modifier)
                }
            }
        } else {
            // Portrait: zoom chip, then thumbnail / shutter / switch row along the bottom.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                zoomChip()
                if (camera != null) Spacer(Modifier.height(16.dp))
                Box(modifier = Modifier.fillMaxWidth()) {
                    thumbnailButton(Modifier.align(Alignment.CenterStart).padding(start = 40.dp))
                    shutterButton(Modifier.align(Alignment.Center))
                    switchButton(Modifier.align(Alignment.CenterEnd).padding(end = 40.dp))
                }
            }
        }
    }
}
