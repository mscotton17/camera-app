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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
    // Default to RAW (DNG); lenses without RAW support fall back to JPEG automatically.
    var format by rememberSaveable { mutableStateOf(CaptureFormat.RAW) }
    var flash by rememberSaveable { mutableStateOf(FlashSetting.AUTO) }
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
    // Selected recipe (null = standard). Kept while the app runs.
    var recipeId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedRecipe = allRecipes.firstOrNull { it.id == recipeId }
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
                bound?.let {
                    it.imageCapture.targetRotation = display.rotation
                    it.preview.targetRotation = display.rotation
                }
            }
        }
        displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { displayManager.unregisterDisplayListener(listener) }
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
        // RAW is always full sensor resolution; only constrain JPEG-only capture.
        if (activeFormat == CaptureFormat.JPEG) captureBuilder.setResolutionSelector(FourByThree)
        previewView.display?.rotation?.let {
            previewBuilder.setTargetRotation(it)
            captureBuilder.setTargetRotation(it)
        }
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

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
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

        // Aspect crop, border and date stamp as they'll appear on the photo.
        recipe?.let { FilmFrameOverlay(it, Modifier.fillMaxSize()) }

        // Gesture layer over the preview: tap to focus/meter.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bound, recipe?.aspect) {
                    detectTapGestures { offset ->
                        val cam = bound?.camera ?: return@detectTapGestures
                        // Ignore taps on the black bars and on the recipe's crop matte.
                        val viewSize = androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat())
                        val crop = previewCropRect(previewImageRect(viewSize), recipe?.aspect ?: AspectRatio.NATIVE)
                        if (!insidePreviewFrame(size.width.toFloat(), size.height.toFloat(), offset) || !crop.contains(offset)) {
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

        val facingLenses = lens?.let { l -> lensSet?.forFacing(l.facing) }.orEmpty()
        val canPickLens = facingLenses.size > 1

        val caps = lens?.exposure
        val shutterOptions = caps?.shutterOptions().orEmpty()
        val isoOptions = caps?.isoOptions().orEmpty()
        val pickable = buildSet {
            if (canPickLens) add(HudItem.FOCAL)
            if (caps?.manualSensor == true && shutterOptions.isNotEmpty()) add(HudItem.SHUTTER)
            if (caps?.manualSensor == true && isoOptions.isNotEmpty()) add(HudItem.ISO)
            if (caps?.variableAperture == true) add(HudItem.APERTURE)
            // Compensation needs something metering: hidden in full manual.
            if (!(resolvedExposure.shutterLocked && resolvedExposure.isoLocked)) add(HudItem.EV)
            add(HudItem.RECIPE)
        }
        val shownPicker = openPicker?.takeIf { it in pickable }

        val hudLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        // The picker for the tapped value: a row under the top bar in portrait, a column beside
        // the side bar in landscape.
        val pickerContent: @Composable (Modifier, Boolean) -> Unit = { pm, vertical ->
            when (shownPicker) {
                HudItem.FOCAL -> Box(
                    Modifier.then(if (vertical) Modifier else Modifier.fillMaxWidth()),
                    contentAlignment = if (vertical) Alignment.TopStart else Alignment.TopEnd
                ) { PickerRow(
                    labels = facingLenses.map { "${it.equivFocalMm ?: "?"} mm" },
                    selectedIndex = facingLenses.indexOfFirst { it.key == lens?.key },
                    onSelect = { i ->
                        if (!capturing) selectedLensKey = facingLenses[i].key
                        openPicker = null
                    },
                    modifier = pm,
                    vertical = vertical
                ) }
                HudItem.SHUTTER -> PickerRow(
                    labels = listOf("AUTO") + shutterOptions.map { formatShutter(it) },
                    selectedIndex = if (exposure.shutterLocked) {
                        shutterOptions.indexOf(resolvedExposure.shutterNs) + 1
                    } else {
                        0
                    },
                    onSelect = { i ->
                        if (caps != null) {
                            exposure = if (i == 0) {
                                exposure.autoShutter()
                            } else {
                                exposure.withShutter(shutterOptions[i - 1], telemetry.latest, caps)
                            }
                        }
                    },
                    modifier = pm,
                    vertical = vertical
                )
                HudItem.ISO -> PickerRow(
                    labels = listOf("AUTO") + isoOptions.map { "ISO $it" },
                    selectedIndex = if (exposure.isoLocked) isoOptions.indexOf(resolvedExposure.iso) + 1 else 0,
                    onSelect = { i ->
                        if (caps != null) {
                            exposure = if (i == 0) {
                                exposure.autoIso()
                            } else {
                                exposure.withIso(isoOptions[i - 1], telemetry.latest, caps)
                            }
                        }
                    },
                    modifier = pm,
                    vertical = vertical
                )
                HudItem.APERTURE -> {
                    val apertures = caps?.apertures.orEmpty()
                    PickerRow(
                        labels = listOf("AUTO") + apertures.map { formatAperture(it) },
                        selectedIndex = apertures.indexOf(resolvedExposure.aperture) + 1,
                        onSelect = { i ->
                            exposure = exposure.copy(aperture = if (i == 0) null else apertures[i - 1])
                        },
                        modifier = pm,
                    vertical = vertical
                    )
                }
                HudItem.EV -> PickerRow(
                    labels = EvOptions.map { formatEv(it) },
                    selectedIndex = EvOptions.indexOfFirst { kotlin.math.abs(it - evComp) < 0.05f },
                    onSelect = { i -> evComp = EvOptions[i] },
                    modifier = pm,
                    vertical = vertical
                )
                HudItem.RECIPE -> PickerRow(
                    // Stays open so looks can be compared live; the last entries are actions.
                    labels = listOf("STD") +
                        allRecipes.map { if (it.id in library.favorites) "\u2605 ${it.label}" else it.label } +
                        listOf("\u270E EDIT", "\u2B07 IMPORT", "\u2699 DEVELOP"),
                    selectedIndex = allRecipes.indexOfFirst { it.id == selectedRecipe?.id } + 1,
                    onSelect = { i ->
                        val action = i - allRecipes.size - 1
                        when {
                            i == 0 -> recipeId = null
                            action < 0 -> recipeId = allRecipes[i - 1].id
                            action == 0 -> {
                                draft = selectedRecipe ?: FilmRecipe(id = newRecipeId(), label = "MY RECIPE")
                                openPicker = null
                            }
                            action == 1 -> importRecipeLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                            else -> {
                                if (selectedRecipe == null) {
                                    Toast.makeText(context, "Pick a recipe first, then DEVELOP a DNG or JPEG with it", Toast.LENGTH_LONG).show()
                                } else {
                                    developLauncher.launch(arrayOf("image/*"))
                                }
                            }
                        }
                    },
                    modifier = pm,
                    vertical = vertical
                )
                null -> Unit
            }
        }
        // Flash, format, EV and recipe buttons.
        val toggleButtons: @Composable () -> Unit = {
                if (!resolvedExposure.manualSensor && bound?.camera?.cameraInfo?.hasFlashUnit() == true) {
                    FlashButton(setting = flash, onClick = { flash = flash.next() })
                }
                if (lens != null && ImageCapture.OUTPUT_FORMAT_RAW in lens.supportedFormats) {
                    FormatButton(
                        format = activeFormat,
                        onClick = {
                            var next = activeFormat.nextFor(lens)
                            // RAW alone can't carry a recipe, so skip it while one is on.
                            if (recipe != null && next == CaptureFormat.RAW) next = next.nextFor(lens)
                            format = next
                        }
                    )
                }
                if (HudItem.EV in pickable) {
                    EvButton(
                        ev = evComp,
                        open = shownPicker == HudItem.EV,
                        onClick = { openPicker = if (shownPicker == HudItem.EV) null else HudItem.EV }
                    )
                }
        }
        // Film recipe button: beside the shutter (left of it in portrait, under it in
        // landscape), with its picker opening next to it.
        val recipeButton: @Composable (Modifier) -> Unit = { m ->
            RecipeButton(
                recipe = recipe,
                open = shownPicker == HudItem.RECIPE,
                onClick = { openPicker = if (shownPicker == HudItem.RECIPE) null else HudItem.RECIPE },
                modifier = m
            )
        }
        val recipePickerOpen = draft == null && shownPicker == HudItem.RECIPE
        val hudBar: @Composable (Modifier, Boolean) -> Unit = { m, vertical ->
            TelemetryHud(
                telemetry = hud,
                settings = resolvedExposure,
                lensFocalMm = lens?.equivFocalMm,
                pickable = pickable,
                openItem = shownPicker,
                onItemClick = { openPicker = if (shownPicker == it) null else it },
                vertical = vertical,
                modifier = m
            )
        }

        if (hudLandscape) {
            // Landscape: exposure values and buttons stacked in the left bar, clear of the image,
            // mirroring the shutter column on the right.
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    hudBar(Modifier, true)
                    if (draft == null) {
                        Spacer(Modifier.height(4.dp))
                        toggleButtons()
                    }
                }
                if (draft == null && shownPicker != null && shownPicker != HudItem.RECIPE) {
                    pickerContent(Modifier.padding(start = 10.dp).heightIn(max = 320.dp), true)
                }
            }
        } else {
            // Portrait: exposure bar across the top, its picker, then the buttons.
            Column(modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
                hudBar(Modifier, false)
                if (draft == null && shownPicker != HudItem.RECIPE) {
                    pickerContent(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp), false)
                }
                if (draft == null) FlowRow(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    toggleButtons()
                }
            }
        }

        val shutterButton: @Composable (Modifier) -> Unit = { m ->
            ShutterButton(
                enabled = !capturing && bound != null,
                onClick = {
                    val b = bound ?: return@ShutterButton
                    capturing = true
                    takePhoto(
                        context = context,
                        imageCapture = b.imageCapture,
                        format = b.format,
                        mirror = lens?.facing == CameraSelector.LENS_FACING_FRONT,
                        telemetry = telemetry.latest,
                        recipe = renderRecipe,
                        ioExecutor = ioExecutor
                    ) { capturing = false }
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

        val isLandscape =
            LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val editing = draft
        if (editing != null) {
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
                if (recipePickerOpen) {
                    pickerContent(Modifier.padding(end = 10.dp).heightIn(max = 320.dp), true)
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    switchButton(Modifier)
                    shutterButton(Modifier)
                    // Same height as the switch button so the shutter stays centred.
                    Box(Modifier.height(52.dp), contentAlignment = Alignment.Center) { recipeButton(Modifier) }
                }
            }
        } else {
            // Portrait: shutter / switch row along the bottom.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (recipePickerOpen) {
                    pickerContent(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 16.dp), false)
                }
                Box(modifier = Modifier.fillMaxWidth()) {
                    recipeButton(Modifier.align(Alignment.CenterStart).padding(start = 20.dp))
                    shutterButton(Modifier.align(Alignment.Center))
                    switchButton(Modifier.align(Alignment.CenterEnd).padding(end = 40.dp))
                }
            }
        }
    }
}
