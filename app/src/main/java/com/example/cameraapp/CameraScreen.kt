package com.example.cameraapp

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalView
import android.view.Surface
import android.view.OrientationEventListener
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import kotlinx.coroutines.Job
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "CameraScreen"

/** Gap under the portrait shutter row (the mode toggle is placed relative to it). */
private val ShutterRowBottomPadding = 24.dp

/** Preview and capture both use the sensor's native 4:3 so the viewfinder shows the exact frame. */
private val FourByThree = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
    .build()

/** JPEG capture: the largest 4:3 size, including sizes that can't stream at full frame rate. */
private val LargestFourByThree = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
    .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
    .build()

/** Use cases currently bound to a lens. */
private class BoundCamera(
    val camera: Camera,
    val preview: Preview,
    val imageCapture: ImageCapture,
    val format: CaptureFormat,
)

@OptIn(ExperimentalLayoutApi::class) // FlowRow
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
    val telemetry = remember { TelemetryTracker() }
    val ioExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { ioExecutor.shutdown() } }

    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var lensSet by remember { mutableStateOf<LensSet?>(null) }
    var selectedLensKey by rememberSaveable { mutableStateOf<String?>(null) }
    // JPEG by default; RAW / RAW+JPG can be picked from the RAW badge or the menu.
    var format by rememberSaveable { mutableStateOf(CaptureFormat.JPEG) }
    var flash by rememberSaveable { mutableStateOf(FlashSetting.OFF) }
    var bound by remember { mutableStateOf<BoundCamera?>(null) }
    var capturing by remember { mutableStateOf(false) }
    // Which HUD item's picker is open (lens, shutter, ISO, f-stop), if any.
    var openPicker by remember { mutableStateOf<HudItem?>(null) }
    // Not saved: exposure is automatic every time the app opens.
    var exposure by remember { mutableStateOf(ExposureSettings()) }
    // Exposure compensation in EV; also resets to 0 on launch.
    var evComp by remember { mutableFloatStateOf(0f) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusKey by remember { mutableIntStateOf(0) }
    val focusScale = remember { Animatable(1f) }
    // Shutter feel: haptic ticks and the long-exposure ring sweep.
    val haptics = remember { ShutterHaptics(context) }
    val uiScope = rememberCoroutineScope()
    val exposureSweep = remember { Animatable(0f) }
    var exposureRunning by remember { mutableStateOf(false) }
    // UI: settings page values, PHOTO/PRO mode, the ☰ menu and the self-timer.
    val settings = remember { AppSettings(context) }
    var proMode by rememberSaveable { mutableStateOf(false) }
    var proPanelOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var timerSec by rememberSaveable { mutableIntStateOf(0) }
    var countdown by remember { mutableIntStateOf(0) }
    // Display rotation (Surface.ROTATION_*), kept current even across 90 <-> 270 flips.
    val rootView = LocalView.current
    var displayRotation by remember { mutableIntStateOf(rootView.display?.rotation ?: Surface.ROTATION_0) }
    // The screen is locked to portrait. How the phone is actually held (0/90/180/270 degrees,
    // clockwise) turns the small controls in place and sets the saved photo's orientation.
    var deviceOrientation by remember { mutableIntStateOf(0) }
    // Running icon angle, always turning the short way round (270 -> 0 is +90, not -270).
    var iconTarget by remember { mutableFloatStateOf(0f) }
    val iconRotation by animateFloatAsState(iconTarget, tween(260), label = "iconRotation")
    // Photo rotation for CameraX (Surface.ROTATION_*) from how the phone is held.
    val captureRotation = when (deviceOrientation) {
        90 -> Surface.ROTATION_270
        180 -> Surface.ROTATION_180
        270 -> Surface.ROTATION_90
        else -> Surface.ROTATION_0
    }
    var countdownJob by remember { mutableStateOf<Job?>(null) }
    val countdownSweep = remember { Animatable(0f) }
    val hud by telemetry.state

    // Selected lens (default: the main back lens) and the format it can actually shoot.
    val lens: Lens? = lensSet?.let { set ->
        set.byKey(selectedLensKey) ?: set.defaultFor(CameraSelector.LENS_FACING_BACK)
            ?: set.lenses.firstOrNull()
    }
    // Film recipes: built-in ones plus the user's own, saved on the phone.
    val recipeStore = remember { RecipeStore(context) }
    var library by remember { mutableStateOf(recipeStore.load()) }
    val customRecipes = library.recipes
    // Favourites first (in the order they were starred), then built-ins, then the user's own.
    val allRecipes = run {
        val all = FilmRecipes + customRecipes
        val favs = library.favorites.mapNotNull { id -> all.firstOrNull { it.id == id } }
        favs + all.filterNot { it in favs }
    }
    val updateLibrary: (RecipeLibrary) -> Unit = { library = it; recipeStore.save(it) }
    var luts by remember { mutableStateOf(CubeLuts.list(context)) }
    // Selected recipe. Kept while the app runs.
    var recipeId by rememberSaveable { mutableStateOf<String?>(null) }
    // There's no "no recipe" option: if nothing (or a deleted recipe) is selected, the first
    // recipe in the list is used.
    val selectedRecipe = allRecipes.firstOrNull { it.id == recipeId } ?: allRecipes.firstOrNull()
    // Recipe open in the editor, if any; the preview shows it live while editing.
    var draft by remember { mutableStateOf<FilmRecipe?>(null) }
    val recipe = draft ?: selectedRecipe

    val saveDraft: () -> Unit = save@{
        val d = draft ?: return@save
        val named = d.copy(label = d.label.trim().ifEmpty { "RECIPE" })
        // Built-in recipes are never overwritten: saving one creates a copy.
        val toSave = if (FilmRecipes.any { it.id == named.id }) named.copy(id = newRecipeId()) else named
        val recipes = if (customRecipes.any { it.id == toSave.id }) {
            customRecipes.map { if (it.id == toSave.id) toSave else it }
        } else {
            customRecipes + toSave
        }
        // A favourite built-in stays a favourite; its saved copy starts unstarred.
        updateLibrary(library.copy(recipes = recipes))
        recipeId = toSave.id
        draft = null
    }
    val deleteDraft: () -> Unit = delete@{
        val d = draft ?: return@delete
        updateLibrary(RecipeLibrary(customRecipes.filterNot { it.id == d.id }, library.favorites - d.id))
        if (recipeId == d.id) recipeId = null
        draft = null
    }
    val toggleFavorite: () -> Unit = fav@{
        val id = draft?.id ?: return@fav
        val favs = if (id in library.favorites) library.favorites - id else library.favorites + id
        updateLibrary(library.copy(favorites = favs))
    }
    val moveDraft: (Int) -> Unit = move@{ dir ->
        val id = draft?.id ?: return@move
        val i = customRecipes.indexOfFirst { it.id == id }
        val j = i + dir
        if (i < 0 || j !in customRecipes.indices) return@move
        val list = customRecipes.toMutableList()
        java.util.Collections.swap(list, i, j)
        updateLibrary(library.copy(recipes = list))
    }

    val importLutLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = CubeLuts.import(context, uri)
        if (name == null) {
            Toast.makeText(context, "That isn't a 3D .cube LUT", Toast.LENGTH_LONG).show()
        } else {
            luts = CubeLuts.list(context)
            draft = draft?.copy(lutName = name)
            Toast.makeText(context, "Imported LUT $name", Toast.LENGTH_SHORT).show()
        }
    }
    val importRecipeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val imported = try {
            importRecipes(context, uri)
        } catch (e: Exception) {
            Log.e(TAG, "Recipe import failed", e)
            emptyList()
        }
        if (imported.isEmpty()) {
            Toast.makeText(context, "No recipes found in that file", Toast.LENGTH_LONG).show()
        } else {
            updateLibrary(library.copy(recipes = customRecipes + imported))
            recipeId = imported.first().id
            val missing = imported.mapNotNull { it.lutName }.filter { it !in luts }.distinct()
            val note = if (missing.isEmpty()) "" else ". Import LUT ${missing.joinToString()} to use it"
            Toast.makeText(context, "Imported ${imported.size} recipe(s)$note", Toast.LENGTH_LONG).show()
        }
    }

    val baseFormat =
        if (lens != null && format.outputFormat in lens.supportedFormats) format else CaptureFormat.JPEG
    // A recipe can't be baked into a DNG, so like a film-simulation camera, RAW becomes
    // RAW+JPG while a recipe is on: the DNG stays clean and the JPEG gets the look.
    val activeFormat =
        if (recipe != null && baseFormat == CaptureFormat.RAW &&
            CaptureFormat.RAW_JPEG.outputFormat in lens?.supportedFormats.orEmpty()
        ) {
            CaptureFormat.RAW_JPEG
        } else {
            baseFormat
        }

    // Live film preview: GPU shader on Android 13+, colour matrix only on Android 12.
    // The shader is compiled once; switching or editing recipes only updates its inputs.
    val filmShader = remember {
        if (Build.VERSION.SDK_INT >= 33) {
            // AGSL is compiled on the device; fall back to the colour-matrix preview if it fails.
            try {
                FilmPreviewShader()
            } catch (e: Exception) {
                Log.e(TAG, "Film preview shader unavailable", e)
                null
            }
        } else {
            null
        }
    }
    // The recipe the shader currently holds (null when none is applied).

    // Re-develop an existing DNG or JPEG with the selected recipe into a new JPEG.
    val developLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val r = selectedRecipe
        if (uri == null || r == null) return@rememberLauncherForActivityResult
        Toast.makeText(context, "Developing with ${r.label}\u2026", Toast.LENGTH_SHORT).show()
        val main = ContextCompat.getMainExecutor(context)
        ioExecutor.execute {
            val result = try {
                developImage(context, uri, r)
            } catch (e: Throwable) {
                Log.e(TAG, "Develop failed", e)
                null
            }
            main.execute {
                Toast.makeText(
                    context,
                    if (result != null) "Saved ${r.label} version" else "Couldn't develop that file",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        val p = ProcessCameraProvider.awaitInstance(context)
        lensSet = enumerateLenses(context, p)
        provider = p
    }

    // The activity handles rotation itself (configChanges), so the camera keeps running.
    // Tell CameraX about each new display rotation so photos are saved upright; a display
    // listener also catches 180-degree flips, which don't trigger a configuration change.
    DisposableEffect(Unit) {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                val display = previewView.display ?: return
                if (display.displayId != displayId) return
                displayRotation = display.rotation
                bound?.let { it.preview.targetRotation = display.rotation }
            }
        }
        displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { displayManager.unregisterDisplayListener(listener) }
    }

    // Follow how the phone is held, snapping to the nearest quarter turn once it is clearly
    // past the halfway point (so it doesn't flicker when held near 45 degrees).
    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                var diff = (orientation - deviceOrientation) % 360
                if (diff < 0) diff += 360
                if (diff > 180) diff -= 360
                if (kotlin.math.abs(diff) < 60) return
                val snapped = ((orientation + 45) / 90 % 4) * 90
                if (snapped == deviceOrientation) return
                var turn = (-snapped - iconTarget) % 360f
                if (turn < -180f) turn += 360f
                if (turn > 180f) turn -= 360f
                iconTarget += turn
                deviceOrientation = snapped
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }
    LaunchedEffect(bound, captureRotation) {
        bound?.imageCapture?.targetRotation = captureRotation
    }

    // Picking a recipe applies its camera defaults (EV, format, lens), if it has any.
    LaunchedEffect(selectedRecipe?.id) {
        val r = selectedRecipe ?: return@LaunchedEffect
        if (r.defaultEvOn) evComp = r.defaultEv
        r.defaultFormat.format?.let { format = it }
        if (r.defaultLensMm > 0) {
            val facing = lens?.facing ?: CameraSelector.LENS_FACING_BACK
            lensSet?.forFacing(facing)
                ?.filter { it.equivFocalMm != null }
                ?.minByOrNull { kotlin.math.abs(it.equivFocalMm!! - r.defaultLensMm) }
                ?.let { selectedLensKey = it.key }
        }
    }

    // Exposure overrides limited to what the current lens supports, plus the recipe's fixed
    // white balance if the camera offers it.
    val resolvedExposure = lens?.let { exposure.resolvedFor(it.exposure) } ?: ExposureSettings()
    val awbMode = recipe?.whiteBalance?.awbMode?.takeIf { lens?.exposure?.awbModes?.contains(it) == true }
    LaunchedEffect(bound, resolvedExposure, awbMode) {
        bound?.camera?.let { applyExposure(it, resolvedExposure, awbMode) }
    }
    // DR200 / DR400 underexpose the sensor by 1 / 2 stops (camera exposure compensation in
    // auto, the metering target in shutter/ISO priority); the tone curve lifts it back by
    // exactly what was achieved. Full manual can't be underexposed for you, so no lift there.
    val drStops = recipe?.dynamicRange?.stops ?: 0
    val meteringEv = evComp - drStops
    val fullManual = resolvedExposure.shutterLocked && resolvedExposure.isoLocked
    val aeRange = bound?.camera?.let { aeCompensationRangeEv(it) }
    val drLift: Float = when {
        drStops == 0 -> 0f
        fullManual -> 0f
        resolvedExposure.priorityMode -> drStops.toFloat()
        aeRange == null -> 0f
        // The camera clamps compensation to its range (often ±2 EV), so DR400 with negative
        // EV of your own may only get part of the way.
        else -> (evComp.coerceIn(aeRange) - meteringEv.coerceIn(aeRange)).coerceIn(0f, drStops.toFloat())
    }
    // The recipe as actually rendered (preview and photo), with the achievable DR lift.
    val renderRecipe = recipe?.let { if (drStops > 0) it.copy(drLiftStops = drLift) else it }

    val recipeCube = remember(recipe?.lutName, luts) { CubeLuts.load(context, recipe?.lutName) }
    val shaderRecipe = remember(filmShader, renderRecipe, recipeCube) {
        if (filmShader != null && renderRecipe != null && Build.VERSION.SDK_INT >= 33) {
            try {
                filmShader.update(renderRecipe, recipeCube)
                renderRecipe
            } catch (e: Exception) {
                Log.e(TAG, "Film preview update failed", e)
                null
            }
        } else {
            null
        }
    }
    var grainSeed by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(shaderRecipe?.grain) {
        // Re-seed the grain ~12 times a second so it moves like film rather than sitting on the glass.
        if (shaderRecipe == null || shaderRecipe.grain <= 0f) return@LaunchedEffect
        while (isActive) {
            delay(83)
            grainSeed = (grainSeed + 1.618f) % 1000f
        }
    }

    // In full auto, EV compensation goes through the camera's own auto-exposure.
    LaunchedEffect(bound, meteringEv, resolvedExposure.manualSensor) {
        val cam = bound?.camera ?: return@LaunchedEffect
        if (!resolvedExposure.manualSensor) applyAeCompensation(cam, meteringEv)
    }

    // Shutter/ISO priority: the camera can't auto-adjust just one of them, so meter the preview
    // ourselves a few times a second and nudge the unlocked value.
    LaunchedEffect(bound, resolvedExposure.priorityMode, lens?.key, meteringEv) {
        val caps = lens?.exposure ?: return@LaunchedEffect
        if (bound == null || !resolvedExposure.priorityMode) return@LaunchedEffect
        while (isActive) {
            // Give each change time to reach the preview (several frames at long shutters).
            val frameMs = (exposure.shutterNs ?: 0L) / 1_000_000L
            delay(max(250L, frameMs * 3))
            if (capturing) continue
            val luma = previewView.bitmap?.let { meanLuma(it, recipe?.aspect ?: AspectRatio.NATIVE) } ?: continue
            val next = exposure.resolvedFor(caps).meteredStep(luma, caps, meteringEv)
            if (next != exposure) exposure = next
        }
    }

    // Flash relies on auto-exposure, so it's off while shutter/ISO are manual.
    LaunchedEffect(flash, bound, resolvedExposure.manualSensor) {
        bound?.imageCapture?.flashMode =
            if (resolvedExposure.manualSensor) ImageCapture.FLASH_MODE_OFF else flash.mode
    }

    // Focus ring: pop in, settle, then fade away.
    LaunchedEffect(focusKey) {
        if (focusKey == 0) return@LaunchedEffect
        focusScale.snapTo(1.4f)
        focusScale.animateTo(1f, tween(250))
        delay(900)
        focusPoint = null
    }

    // Bind Preview + ImageCapture to the selected lens in the selected format. Rebinds when
    // either changes and unbinds when this composable leaves.
    DisposableEffect(lifecycleOwner, provider, lens?.key, activeFormat) {
        val p = provider
        if (p == null || lens == null) return@DisposableEffect onDispose { }

        val previewBuilder = Preview.Builder()
            .setResolutionSelector(FourByThree)
            .attachTelemetry(telemetry)
        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setOutputFormat(activeFormat.outputFormat)
            .setFlashMode(flash.mode)
        // RAW is always full sensor resolution; JPEG takes the largest 4:3 size the lens offers.
        if (activeFormat == CaptureFormat.JPEG) captureBuilder.setResolutionSelector(LargestFourByThree)
        previewView.display?.rotation?.let { previewBuilder.setTargetRotation(it) }
        captureBuilder.setTargetRotation(captureRotation)
        lens.physicalId?.let {
            previewBuilder.usePhysicalCamera(it)
            captureBuilder.usePhysicalCamera(it)
        }
        val preview = previewBuilder.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val capture = captureBuilder.build()

        try {
            p.unbindAll()
            telemetry.reset()
            telemetry.cropFactor = lens.cropFactor
            telemetry.physicalId = lens.physicalId
            val camera = p.bindToLifecycle(lifecycleOwner, lens.cameraInfo.cameraSelector, preview, capture)
            bound = BoundCamera(camera, preview, capture, activeFormat)
        } catch (e: Exception) {
            Log.e(TAG, "Binding ${lens.key} as $activeFormat failed", e)
            bound = null
            if (activeFormat != CaptureFormat.JPEG) {
                Toast.makeText(context, "${activeFormat.label} isn't available on this lens", Toast.LENGTH_SHORT).show()
                format = CaptureFormat.JPEG
            } else if (lens.physicalId != null) {
                Toast.makeText(context, "This lens can't be opened directly", Toast.LENGTH_SHORT).show()
                selectedLensKey = lensSet?.defaultFor(lens.facing)?.key
            }
        }

        onDispose {
            p.unbindAll()
            bound = null
        }
    }

    CompositionLocalProvider(LocalIconRotation provides iconRotation) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Portrait: the band between the top icons and the recipe strip, where the viewfinder
        // frame is centred so the recipes sit below it rather than over the picture.
        val vfArea = run {
            val d = LocalDensity.current
            if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) return@run null
            val top = WindowInsets.statusBars.getTop(d) + with(d) { (4.dp + 48.dp + 4.dp).toPx() }
            val bottom = constraints.maxHeight - WindowInsets.navigationBars.getBottom(d) -
                with(d) { (ShutterRowBottomPadding + 80.dp + 40.dp + 4.dp).toPx() }
            if (bottom - top < 100f) null
            else androidx.compose.ui.geometry.Rect(0f, top, constraints.maxWidth.toFloat(), bottom)
        }
        // The preview is sized to the (possibly enlarged) 4:3 image, so with a crop the cropped
        // frame fills the screen and the cropped-away parts run off the edges.
        BoxWithConstraints(Modifier.fillMaxSize()) {
        val previewDensity = LocalDensity.current
        val image = previewImageRect(
            androidx.compose.ui.geometry.Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()),
            recipe?.aspect ?: AspectRatio.NATIVE,
            vfArea
        )
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .offset { IntOffset(image.left.roundToInt(), image.top.roundToInt()) }
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .requiredSize(with(previewDensity) { image.width.toDp() }, with(previewDensity) { image.height.toDp() })
                .graphicsLayer {
                    // Reading grainSeed here only redraws this layer, not the whole screen.
                    renderEffect = when {
                        filmShader != null && shaderRecipe != null && Build.VERSION.SDK_INT >= 33 -> {
                            // Position-based effects follow the recipe's crop of the 4:3 image.
                            val crop = previewCropRect(previewImageRect(size), shaderRecipe.aspect)
                            filmShader.effect(grainSeed, crop.left, crop.top, crop.width, crop.height)
                                .asComposeRenderEffect()
                        }
                        recipe != null && Build.VERSION.SDK_INT >= 31 ->
                            filmColorFilterEffect(recipe).asComposeRenderEffect()
                        else -> null
                    }
                }
        )
        }

        // Aspect crop, border and date stamp as they'll appear on the photo.
        recipe?.let { FilmFrameOverlay(it, Modifier.fillMaxSize(), deviceOrientation, vfArea) }

        // Gesture layer over the preview: tap to focus/meter.
        val swipeRecipes by androidx.compose.runtime.rememberUpdatedState(allRecipes)
        val swipeSelectedId by androidx.compose.runtime.rememberUpdatedState(selectedRecipe?.id)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bound, recipe?.aspect, vfArea) {
                    detectTapGestures { offset ->
                        val cam = bound?.camera ?: return@detectTapGestures
                        // Ignore taps on the black bars and on the recipe's crop matte.
                        val viewSize = androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat())
                        val aspect = recipe?.aspect ?: AspectRatio.NATIVE
                        val image = previewImageRect(viewSize, aspect, vfArea)
                        if (!previewCropRect(image, aspect).contains(offset)) return@detectTapGestures
                        // The preview view starts at the image's corner, which may be off-screen.
                        val point = previewView.meteringPointFactory.createPoint(offset.x - image.left, offset.y - image.top)
                        val action = FocusMeteringAction.Builder(
                            point,
                            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                        ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                        cam.cameraControl.startFocusAndMetering(action)
                        focusPoint = offset
                        focusKey++
                    }
                }
                // Swipe sideways across the viewfinder to change recipe, like changing mode.
                .pointerInput(Unit) {
                    var total = 0f
                    val threshold = 48.dp.toPx()
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = {
                            val i = swipeRecipes.indexOfFirst { it.id == swipeSelectedId }
                            val t = when {
                                total < -threshold -> i + 1
                                total > threshold -> i - 1
                                else -> i
                            }
                            val to = swipeRecipes.getOrNull(t)
                            if (i >= 0 && t != i && to != null) {
                                recipeId = to.id
                                if (settings.hapticsOn) haptics.tick(opening = false)
                            }
                        }
                    ) { _, d -> total += d }
                }
        )

        focusPoint?.let { pt ->
            Box(
                modifier = Modifier
                    .offset {
                        val half = 32.dp.toPx()
                        IntOffset((pt.x - half).roundToInt(), (pt.y - half).roundToInt())
                    }
                    .size(64.dp)
                    .graphicsLayer {
                        scaleX = focusScale.value
                        scaleY = focusScale.value
                    }
                    .border(1.5.dp, HudAmber, CircleShape)
            )
        }

        val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val facingLenses = lens?.let { l -> lensSet?.forFacing(l.facing) }.orEmpty()

        val caps = lens?.exposure
        val shutterOptions = caps?.shutterOptions().orEmpty()
        val isoOptions = caps?.isoOptions().orEmpty()
        val pickable = buildSet {
            if (caps?.manualSensor == true && shutterOptions.isNotEmpty()) add(HudItem.SHUTTER)
            if (caps?.manualSensor == true && isoOptions.isNotEmpty()) add(HudItem.ISO)
            if (caps?.variableAperture == true) add(HudItem.APERTURE)
            // Compensation needs something metering: unavailable in full manual.
            if (!fullManual) add(HudItem.EV)
            add(HudItem.RECIPE)
        }
        val shownPicker = openPicker?.takeIf { it in pickable }

        // PRO button beside the shutter (left of it in portrait, under it in landscape): opens
        // the PRO panel; lit while any exposure setting is manual.
        val proActive = resolvedExposure.shutterLocked || resolvedExposure.isoLocked ||
            resolvedExposure.aperture != null || evComp != 0f
        val proButton: @Composable (Modifier) -> Unit = { m ->
            ProButton(
                active = proActive,
                open = proPanelOpen,
                onClick = {
                    openPicker = null
                    proPanelOpen = !proPanelOpen
                },
                modifier = m
            )
        }
        val recipePickerOpen = draft == null && shownPicker == HudItem.RECIPE
        // PRO panel: ISO, shutter, EV and aperture, each with its choices.
        val apertures = caps?.apertures.orEmpty()
        val proControls = listOf(
            ProControl(
                label = "ISO",
                value = (resolvedExposure.iso ?: hud.iso)?.let { "ISO $it" } ?: "ISO --",
                auto = !resolvedExposure.isoLocked,
                options = listOf("AUTO") + isoOptions.map { it.toString() },
                selected = if (exposure.isoLocked) isoOptions.indexOf(resolvedExposure.iso) + 1 else 0,
                note = if (HudItem.ISO !in pickable) "This camera doesn't allow manual ISO" else null,
                onSelect = { i ->
                    if (caps != null) {
                        exposure = if (i == 0) exposure.autoIso() else exposure.withIso(isoOptions[i - 1], telemetry.latest, caps)
                    }
                }
            ),
            ProControl(
                label = "SHUTTER",
                value = formatShutter(resolvedExposure.shutterNs ?: hud.exposureTimeNs),
                auto = !resolvedExposure.shutterLocked,
                options = listOf("AUTO") + shutterOptions.map { formatShutter(it) },
                selected = if (exposure.shutterLocked) shutterOptions.indexOf(resolvedExposure.shutterNs) + 1 else 0,
                note = if (HudItem.SHUTTER !in pickable) "This camera doesn't allow manual shutter speed" else null,
                onSelect = { i ->
                    if (caps != null) {
                        exposure = if (i == 0) exposure.autoShutter() else exposure.withShutter(shutterOptions[i - 1], telemetry.latest, caps)
                    }
                }
            ),
            ProControl(
                label = "EV",
                value = formatEv(evComp),
                auto = false,
                options = EvOptions.map { formatEv(it) },
                selected = EvOptions.indexOfFirst { kotlin.math.abs(it - evComp) < 0.05f },
                note = if (fullManual) "Not used while both ISO and shutter are manual" else null,
                onSelect = { i -> evComp = EvOptions[i] }
            ),
            ProControl(
                label = "APERTURE",
                value = formatAperture(resolvedExposure.aperture ?: hud.apertureF),
                auto = caps?.variableAperture == true && resolvedExposure.aperture == null,
                options = listOf("AUTO") + apertures.map { formatAperture(it) },
                selected = apertures.indexOf(resolvedExposure.aperture) + 1,
                note = if (caps?.variableAperture != true) "Fixed aperture on this lens" else null,
                onSelect = { i -> exposure = exposure.copy(aperture = if (i == 0) null else apertures[i - 1]) }
            ),
        )
        // PRO opens its panel (tap PRO again to close it); PHOTO returns to full auto.
        val setMode: (Boolean) -> Unit = { pro ->
            openPicker = null
            if (pro) {
                if (proMode) {
                    proPanelOpen = !proPanelOpen
                } else {
                    proMode = true
                    proPanelOpen = true
                }
            } else {
                proMode = false
                proPanelOpen = false
                exposure = ExposureSettings()
            }
        }
        // Recipe carousel (in place of PHOTO · PRO): swipe or tap a neighbour to switch.
        val selectRecipe: (String) -> Unit = { id ->
            if (id != selectedRecipe?.id) {
                recipeId = id
                if (settings.hapticsOn) haptics.tick(opening = false)
            }
        }
        val flashAvailable = !resolvedExposure.manualSensor && bound?.camera?.cameraInfo?.hasFlashUnit() == true
        val formatAvailable = lens != null && ImageCapture.OUTPUT_FORMAT_RAW in lens.supportedFormats
        val cycleFormat: () -> Unit = cycle@{
            val l = lens ?: return@cycle
            var next = activeFormat.nextFor(l)
            // RAW alone can't carry a recipe, so skip it while one is on.
            if (recipe != null && next == CaptureFormat.RAW) next = next.nextFor(l)
            format = next
        }
        val topBar: @Composable (Modifier, Boolean) -> Unit = { m, compact ->
            TopIconBar(
                flash = flash,
                flashAvailable = flashAvailable,
                onFlash = { flash = flash.next() },
                format = activeFormat,
                formatAvailable = formatAvailable,
                onFormat = cycleFormat,
                timerSeconds = timerSec,
                onMenu = { menuOpen = !menuOpen },
                menuOpen = menuOpen,
                modifier = m,
                compact = compact
            )
        }

        // Controls anchored to the image itself: grid, countdown, lens buttons, PRO strip.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val full = previewImageRect(androidx.compose.ui.geometry.Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()), recipe?.aspect ?: AspectRatio.NATIVE, vfArea)
            val frame = previewCropRect(full, recipe?.aspect ?: AspectRatio.NATIVE)
            Box(
                Modifier
                    .offset { IntOffset(frame.left.roundToInt(), frame.top.roundToInt()) }
                    .size(with(density) { frame.width.toDp() }, with(density) { frame.height.toDp() })
            ) {
                if (settings.gridOn) GridOverlay(Modifier.fillMaxSize())
                if (countdown > 0) CountdownOverlay(countdown, Modifier.fillMaxSize())
                if (draft == null) {
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        // Hidden while a panel is open, so they don't show through it.
                        if (facingLenses.size > 1 && !proPanelOpen && !recipePickerOpen) {
                            LensPills(
                                lenses = facingLenses,
                                selectedKey = lens?.key,
                                onSelect = { if (!capturing) selectedLensKey = it.key },
                                modifier = Modifier.padding(bottom = 10.dp)
                            )
                        }
                    }
                }
            }
        }

        if (isLandscape) {
            // Landscape: the icons stay on the edge of the phone they're on in portrait, in the
            // same order, each turned upright. Phone turned left: that edge is the left side,
            // with the menu at the top and the flash at the bottom.
            TopIconBar(
                flash = flash,
                flashAvailable = flashAvailable,
                onFlash = { flash = flash.next() },
                format = activeFormat,
                formatAvailable = formatAvailable,
                onFormat = cycleFormat,
                timerSeconds = timerSec,
                onMenu = { menuOpen = !menuOpen },
                menuOpen = menuOpen,
                vertical = true,
                reversed = displayRotation != Surface.ROTATION_270,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
                    .padding(start = 4.dp, top = 20.dp, bottom = 20.dp)
            )
        } else {
            // Portrait: PHOTO · PRO sits in the black band between the image and the shutter row
            // (centred in it), only overlapping the image on phones with no room below it.
            if (draft == null && !recipePickerOpen && !proPanelOpen) BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                val full = previewImageRect(androidx.compose.ui.geometry.Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()), recipe?.aspect ?: AspectRatio.NATIVE, vfArea)
                val navPx = WindowInsets.navigationBars.getBottom(density)
                val shutterTop = constraints.maxHeight - navPx - with(density) { (ShutterRowBottomPadding + 80.dp).toPx() }
                val toggleHeight = with(density) { 40.dp.toPx() }
                val frameBottom = previewCropRect(full, recipe?.aspect ?: AspectRatio.NATIVE).bottom
                val gap = shutterTop - frameBottom
                val y = if (gap >= toggleHeight) frameBottom + (gap - toggleHeight) / 2f else shutterTop - toggleHeight
                RecipeCarousel(
                    allRecipes, selectedRecipe?.id, selectRecipe,
                    Modifier
                        .align(Alignment.TopCenter)
                        .offset { IntOffset(0, y.roundToInt()) }
                        .fillMaxWidth()
                        .height(40.dp)
                        .padding(horizontal = 12.dp)
                )
            }
            // Portrait: icon bar across the top.
            topBar(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                false
            )
        }

        val capture: () -> Unit = capture@{
                    val b = bound ?: return@capture
                    capturing = true
                    val shotStart = System.currentTimeMillis()
                    // Feel: open click now, close tick after the exposure time; long exposures
                    // also sweep the ring for their duration before the processing spinner.
                    val exposureMs = ((resolvedExposure.shutterNs ?: telemetry.latest.exposureTimeNs ?: 0L) / 1_000_000L)
                    if (settings.hapticsOn) {
                        haptics.tick(opening = true)
                        uiScope.launch {
                            delay(max(exposureMs, ShutterHaptics.MIN_GAP_MS))
                            haptics.tick(opening = false)
                        }
                    }
                    if (exposureMs >= 100L) {
                        uiScope.launch {
                            exposureRunning = true
                            exposureSweep.snapTo(0f)
                            exposureSweep.animateTo(1f, tween(exposureMs.toInt(), easing = LinearEasing))
                            exposureRunning = false
                        }
                    }
                    takePhoto(
                        context = context,
                        imageCapture = b.imageCapture,
                        format = b.format,
                        mirror = lens?.facing == CameraSelector.LENS_FACING_FRONT,
                        telemetry = telemetry.latest,
                        recipe = renderRecipe,
                        ioExecutor = ioExecutor
                    ) {
                        // The camera has the shot; for a long exposure, wait for it to finish.
                        val left = exposureMs - (System.currentTimeMillis() - shotStart)
                        if (left <= 0) capturing = false
                        else uiScope.launch { delay(left); capturing = false }
                    }
        }
        val shutterButton: @Composable (Modifier) -> Unit = { m ->
            ShutterButton(
                // Stays tappable during a self-timer countdown, to cancel it.
                enabled = (!capturing && bound != null) || countdown > 0,
                // Spins while a shot is being taken or any photo is still developing.
                busy = capturing || DevelopQueue.pending.intValue > 0,
                exposureProgress = when {
                    countdown > 0 -> countdownSweep.value
                    exposureRunning -> exposureSweep.value
                    else -> null
                },
                onClick = {
                    val job = countdownJob
                    when {
                        job != null -> {
                            job.cancel()
                            countdownJob = null
                            countdown = 0
                        }
                        timerSec > 0 -> countdownJob = uiScope.launch {
                            launch {
                                countdownSweep.snapTo(0f)
                                countdownSweep.animateTo(1f, tween(timerSec * 1000, easing = LinearEasing))
                            }
                            for (sec in timerSec downTo 1) {
                                countdown = sec
                                if (settings.hapticsOn) haptics.tick(opening = false)
                                delay(1000)
                            }
                            countdown = 0
                            countdownJob = null
                            capture()
                        }
                        else -> capture()
                    }
                },
                modifier = m
            )
        }
        val switchButton: @Composable (Modifier) -> Unit = { m ->
            val set = lensSet
            if (set != null && set.hasFrontAndBack && lens != null) {
                SwitchCameraButton(
                    enabled = !capturing,
                    onClick = {
                        val other = if (lens.facing == CameraSelector.LENS_FACING_BACK) {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }
                        selectedLensKey = set.defaultFor(other)?.key
                        openPicker = null
                    },
                    modifier = m
                )
            } else {
                Spacer(m.size(52.dp))
            }
        }

        val editing = draft
        val panelModifier = if (isLandscape) {
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(0.5f)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Vertical))
        } else {
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.55f)
                .navigationBarsPadding()
        }
        if (recipePickerOpen) {
            // Recipe chooser in the same place and style as the editor.
            RecipePanel(
                recipes = allRecipes,
                selectedId = selectedRecipe?.id,
                favorites = library.favorites,
                customIds = customRecipes.map { it.id }.toSet(),
                onSelect = { recipeId = it },
                onToggleFavorite = { id ->
                    val favs = if (id in library.favorites) library.favorites - id else library.favorites + id
                    updateLibrary(library.copy(favorites = favs))
                },
                onEdit = { r ->
                    draft = r ?: FilmRecipe(id = newRecipeId(), label = "MY RECIPE")
                    openPicker = null
                },
                onNew = {
                    draft = FilmRecipe(id = newRecipeId(), label = "MY RECIPE")
                    openPicker = null
                },
                onImport = { importRecipeLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                onDevelop = {
                    if (selectedRecipe == null) {
                        Toast.makeText(context, "Pick a recipe first, then develop a DNG or JPEG with it", Toast.LENGTH_LONG).show()
                    } else {
                        developLauncher.launch(arrayOf("image/*"))
                    }
                },
                onClose = { openPicker = null },
                modifier = panelModifier
            )
        } else if (proPanelOpen && editing == null) {
            ProPanel(
                controls = proControls,
                onAllAuto = {
                    exposure = ExposureSettings()
                    evComp = 0f
                },
                onClose = { proPanelOpen = false },
                modifier = panelModifier
            )
        } else if (editing != null) {
            // Recipe editor over the lower half (portrait) or right half (landscape); the live
            // preview stays visible above / beside it.
            RecipeEditor(
                draft = editing,
                isBuiltIn = FilmRecipes.any { it.id == editing.id },
                isSaved = customRecipes.any { it.id == editing.id },
                isFavorite = editing.id in library.favorites,
                canMoveUp = customRecipes.indexOfFirst { it.id == editing.id } > 0,
                canMoveDown = customRecipes.indexOfFirst { it.id == editing.id }.let { it >= 0 && it < customRecipes.size - 1 },
                luts = luts,
                lensOptions = lensSet?.forFacing(lens?.facing ?: CameraSelector.LENS_FACING_BACK)
                    ?.mapNotNull { it.equivFocalMm }.orEmpty(),
                onChange = { draft = it },
                onSave = saveDraft,
                onDelete = deleteDraft,
                onClose = { draft = null },
                onToggleFavorite = toggleFavorite,
                onMove = moveDraft,
                onShare = { shareRecipes(context, listOf(editing)) },
                onImportLut = { importLutLauncher.launch(arrayOf("*/*")) },
                onDeleteLut = { name ->
                    CubeLuts.delete(context, name)
                    luts = CubeLuts.list(context)
                    draft = draft?.copy(lutName = null)
                },
                modifier = if (isLandscape) {
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .fillMaxWidth(0.5f)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Vertical))
                } else {
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(0.55f)
                        .navigationBarsPadding()
                }
            )
        } else if (isLandscape) {
            // Recipes keep their portrait position and orientation relative to the phone: the
            // same strip, turned 90 degrees, in the band between the image and the shutter.
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                val full = previewImageRect(androidx.compose.ui.geometry.Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()), recipe?.aspect ?: AspectRatio.NATIVE)
                val endInset = WindowInsets.safeDrawing.getRight(density, LayoutDirection.Ltr)
                val shutterLeft = constraints.maxWidth - endInset - with(density) { (24.dp + 80.dp).toPx() }
                val strip = with(density) { 40.dp.toPx() }
                val frameRight = previewCropRect(full, recipe?.aspect ?: AspectRatio.NATIVE).right
                val gap = shutterLeft - frameRight
                val centerX = if (gap >= strip) frameRight + gap / 2f else shutterLeft - strip / 2f
                val length = constraints.maxHeight.toFloat()
                // Phone turned left (top to the left): the strip reads bottom-to-top; turned
                // right: top-to-bottom. Either way it lies just as it does in portrait.
                val angle = if (displayRotation == Surface.ROTATION_270) 90f else -90f
                RecipeCarousel(
                    allRecipes, selectedRecipe?.id, selectRecipe,
                    Modifier
                        .offset { IntOffset((centerX - length / 2f).roundToInt(), ((constraints.maxHeight - strip) / 2f).roundToInt()) }
                        .requiredSize(with(density) { length.toDp() }, 40.dp)
                        .graphicsLayer { rotationZ = angle }
                        .padding(horizontal = 12.dp)
                )
            }
            // Landscape: controls stacked on the right edge, shutter centred under the thumb.
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
                Column(
                    verticalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    switchButton(Modifier)
                    shutterButton(Modifier)
                    // Same height as the switch button so the shutter stays centred.
                    Box(Modifier.height(52.dp), contentAlignment = Alignment.Center) { proButton(Modifier) }
                }
            }
        } else {
            // Portrait: shutter / switch row along the bottom.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = ShutterRowBottomPadding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    proButton(Modifier.align(Alignment.CenterStart).padding(start = 40.dp))
                    shutterButton(Modifier.align(Alignment.Center))
                    switchButton(Modifier.align(Alignment.CenterEnd).padding(end = 40.dp))
                }
            }
        }

        // ☰ menu: drops from the top over everything else.
        if (menuOpen) {
            Scrim { menuOpen = false }
            MenuSheet(
                formats = listOf(CaptureFormat.JPEG, CaptureFormat.RAW, CaptureFormat.RAW_JPEG)
                    .filter { it.outputFormat in lens?.supportedFormats.orEmpty() },
                format = activeFormat,
                // A recipe needs a JPEG to bake into, so RAW alone is off while one is on.
                disabledFormats = if (recipe != null) setOf(CaptureFormat.RAW) else emptySet(),
                onFormat = { format = it },
                flash = flash,
                flashNote = when {
                    bound?.camera?.cameraInfo?.hasFlashUnit() != true -> "This lens has no flash"
                    resolvedExposure.manualSensor -> "Flash is off while shutter or ISO is manual"
                    else -> null
                },
                onFlash = { flash = it },
                timerSeconds = timerSec,
                onTimer = { timerSec = it },
                ev = evComp,
                evAvailable = !fullManual,
                onEv = { evComp = it },
                tiles = listOf(
                    MenuTile("\u25A4", "Recipes") {
                        menuOpen = false
                        proPanelOpen = false
                        openPicker = HudItem.RECIPE
                    },
                    MenuTile("\u270E", "Recipe editor") {
                        menuOpen = false
                        openPicker = null
                        draft = selectedRecipe ?: FilmRecipe(id = newRecipeId(), label = "MY RECIPE")
                    },
                    MenuTile("\u2B07", "Import recipe") {
                        importRecipeLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    },
                    MenuTile("\u25D1", "Develop photo") {
                        if (selectedRecipe == null) {
                            Toast.makeText(context, "Pick a recipe first, then develop a DNG or JPEG with it", Toast.LENGTH_LONG).show()
                        } else {
                            developLauncher.launch(arrayOf("image/*"))
                        }
                    },
                ),
                settings = settings,
                onClose = { menuOpen = false },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .then(if (isLandscape) Modifier.fillMaxWidth(0.6f) else Modifier.fillMaxWidth())
                    .statusBarsPadding()
            )
        }
    }
    }
}
