package com.example.cameraapp

import kotlin.math.roundToInt

/**
 * Every editable recipe setting, described once. The editor builds its sliders and choice
 * chips from these, and saved / shared recipes are written and read through them.
 */
sealed interface RecipeParam {
    val key: String
    val label: String
}

class FloatParam(
    override val key: String,
    override val label: String,
    val default: Float,
    val min: Float,
    val max: Float,
    val decimals: Int = 2,
    val get: (FilmRecipe) -> Float,
    val set: (FilmRecipe, Float) -> FilmRecipe,
) : RecipeParam

class ChoiceParam(
    override val key: String,
    override val label: String,
    val options: List<Keyed>,
    val get: (FilmRecipe) -> Keyed,
    val set: (FilmRecipe, Keyed) -> FilmRecipe,
) : RecipeParam

class ToggleParam(
    override val key: String,
    override val label: String,
    val get: (FilmRecipe) -> Boolean,
    val set: (FilmRecipe, Boolean) -> FilmRecipe,
) : RecipeParam

/** Sections whose controls can't be described as plain sliders / chips. */
enum class SpecialEditor { MONO, BANDS, CURVES, LUT, DEFAULT_LENS }

class ParamSection(
    val title: String,
    val params: List<RecipeParam> = emptyList(),
    val special: SpecialEditor? = null,
    /** Shown under the title. */
    val note: String? = null,
)

private fun f(
    key: String, label: String, default: Float, min: Float, max: Float, decimals: Int = 2,
    get: (FilmRecipe) -> Float, set: (FilmRecipe, Float) -> FilmRecipe,
) = FloatParam(key, label, default, min, max, decimals, get, set)

@Suppress("UNCHECKED_CAST")
private fun <T : Keyed> choice(
    key: String, label: String, options: List<T>, get: (FilmRecipe) -> T, set: (FilmRecipe, T) -> FilmRecipe,
) = ChoiceParam(key, label, options, get) { r, v -> set(r, v as T) }

/** Three sliders for an RGB value stored as a [Tint]. */
private fun tintParams(
    key: String, label: String, default: Tint, min: Float, max: Float, decimals: Int,
    get: (FilmRecipe) -> Tint, set: (FilmRecipe, Tint) -> FilmRecipe,
): List<FloatParam> = listOf(
    f("$key.r", "$label red", default.r, min, max, decimals, { get(it).r }) { r, v -> set(r, get(r).copy(r = v)) },
    f("$key.g", "$label green", default.g, min, max, decimals, { get(it).g }) { r, v -> set(r, get(r).copy(g = v)) },
    f("$key.b", "$label blue", default.b, min, max, decimals, { get(it).b }) { r, v -> set(r, get(r).copy(b = v)) },
)

private fun mixerParam(key: String, label: String, default: Float, get: (Mixer) -> Float, set: (Mixer, Float) -> Mixer) =
    f("mixer.$key", label, default, -1f, 2f, 2, { get(it.mixer) }) { r, v -> r.copy(mixer = set(r.mixer, v)) }

val RecipeSections: List<ParamSection> = listOf(
    ParamSection(
        "COLOR BALANCE", listOf(
            f("temperature", "Temperature (K)", 6500f, 2500f, 12000f, 0, { it.temperature }) { r, v -> r.copy(temperature = v) },
            f("redGain", "Red gain", 1f, 0.85f, 1.15f, 2, { it.redGain }) { r, v -> r.copy(redGain = v) },
            f("blueGain", "Blue gain", 1f, 0.85f, 1.15f, 2, { it.blueGain }) { r, v -> r.copy(blueGain = v) },
            f("tint", "Tint (green ↔ magenta)", 0f, -0.1f, 0.1f, 3, { it.tint }) { r, v -> r.copy(tint = v) },
            f("hueShift", "Global hue shift (°)", 0f, -30f, 30f, 0, { it.hueShift }) { r, v -> r.copy(hueShift = v) },
        ),
        note = "Temperature works like a raw editor: higher is warmer. 6500 is neutral."
    ),
    ParamSection(
        "CHANNEL MIXER", listOf(
            mixerParam("rr", "Red from red", 1f, { it.rr }) { m, v -> m.copy(rr = v) },
            mixerParam("rg", "Red from green", 0f, { it.rg }) { m, v -> m.copy(rg = v) },
            mixerParam("rb", "Red from blue", 0f, { it.rb }) { m, v -> m.copy(rb = v) },
            mixerParam("gr", "Green from red", 0f, { it.gr }) { m, v -> m.copy(gr = v) },
            mixerParam("gg", "Green from green", 1f, { it.gg }) { m, v -> m.copy(gg = v) },
            mixerParam("gb", "Green from blue", 0f, { it.gb }) { m, v -> m.copy(gb = v) },
            mixerParam("br", "Blue from red", 0f, { it.br }) { m, v -> m.copy(br = v) },
            mixerParam("bg", "Blue from green", 0f, { it.bg }) { m, v -> m.copy(bg = v) },
            mixerParam("bb", "Blue from blue", 1f, { it.bb }) { m, v -> m.copy(bb = v) },
        ),
        note = "Colour crosstalk, like reds bleeding into greens on old slide film."
    ),
    ParamSection(
        "SATURATION", listOf(
            f("satR", "Red saturation", 1f, 0f, 1.6f, 2, { it.satR }) { r, v -> r.copy(satR = v) },
            f("satG", "Green saturation", 1f, 0f, 1.6f, 2, { it.satG }) { r, v -> r.copy(satG = v) },
            f("satB", "Blue saturation", 1f, 0f, 1.6f, 2, { it.satB }) { r, v -> r.copy(satB = v) },
            f("vibrance", "Vibrance", 0f, -1f, 1f, 2, { it.vibrance }) { r, v -> r.copy(vibrance = v) },
            f("highlightSat", "Highlight saturation", 0f, -1f, 1f, 2, { it.highlightSat }) { r, v -> r.copy(highlightSat = v) },
            f("shadowSat", "Shadow saturation", 0f, -1f, 1f, 2, { it.shadowSat }) { r, v -> r.copy(shadowSat = v) },
            f("colorChrome", "Color chrome", 0f, 0f, 1f, 2, { it.colorChrome }) { r, v -> r.copy(colorChrome = v) },
            f("colorChromeBlue", "Color chrome blue", 0f, 0f, 1f, 2, { it.colorChromeBlue }) { r, v -> r.copy(colorChromeBlue = v) },
            f("bleachBypass", "Bleach bypass", 0f, 0f, 1f, 2, { it.bleachBypass }) { r, v -> r.copy(bleachBypass = v) },
            f("skinProtect", "Skin-tone protection", 0f, 0f, 1f, 2, { it.skinProtect }) { r, v -> r.copy(skinProtect = v) },
        )
    ),
    ParamSection("MONOCHROME", special = SpecialEditor.MONO),
    ParamSection("COLOR BANDS (HSL)", special = SpecialEditor.BANDS),
    ParamSection(
        "TONE", listOf(
            f("exposure", "Exposure (EV)", 0f, -2f, 2f, 2, { it.exposure }) { r, v -> r.copy(exposure = v) },
            f("contrast", "Contrast", 0f, -1f, 1f, 2, { it.contrast }) { r, v -> r.copy(contrast = v) },
            f("blackPoint", "Black point (fade)", 0f, 0f, 0.1f, 3, { it.blackPoint }) { r, v -> r.copy(blackPoint = v) },
            f("shadows", "Shadows", 0f, -0.1f, 0.08f, 3, { it.shadows }) { r, v -> r.copy(shadows = v) },
            f("mids", "Midtones", 0f, -0.08f, 0.08f, 3, { it.mids }) { r, v -> r.copy(mids = v) },
            f("highlights", "Highlights", 0f, -0.08f, 0.08f, 3, { it.highlights }) { r, v -> r.copy(highlights = v) },
            f("whitePoint", "White point", 1f, 0.88f, 1f, 3, { it.whitePoint }) { r, v -> r.copy(whitePoint = v) },
            f("toe", "Toe (shadow roll-in)", 0f, 0f, 1f, 2, { it.toe }) { r, v -> r.copy(toe = v) },
            f("shoulder", "Shoulder (highlight roll-off)", 0f, 0f, 1f, 2, { it.shoulder }) { r, v -> r.copy(shoulder = v) },
            f("highlightRecovery", "Highlight recovery (local)", 0f, 0f, 1f, 2, { it.highlightRecovery }) { r, v -> r.copy(highlightRecovery = v) },
            f("shadowRecovery", "Shadow recovery (local)", 0f, 0f, 1f, 2, { it.shadowRecovery }) { r, v -> r.copy(shadowRecovery = v) },
            choice("dynamicRange", "Dynamic range", DynamicRange.entries, { it.dynamicRange }) { r, v -> r.copy(dynamicRange = v) },
        ),
        note = "DR200 / DR400 underexpose by 1 / 2 stops and lift the image back, protecting highlights. In full manual exposure, set the underexposure yourself."
    ),
    ParamSection("CURVES", special = SpecialEditor.CURVES),
    ParamSection(
        "SPLIT TONING",
        tintParams("shadowTint", "Shadows", Tint(), -0.05f, 0.05f, 3, { it.shadowTint }) { r, v -> r.copy(shadowTint = v) } +
            tintParams("midTint", "Mids", Tint(), -0.05f, 0.05f, 3, { it.midTint }) { r, v -> r.copy(midTint = v) } +
            tintParams("highlightTint", "Highlights", Tint(), -0.05f, 0.05f, 3, { it.highlightTint }) { r, v -> r.copy(highlightTint = v) }
    ),
    ParamSection("3D LUT (.CUBE)", special = SpecialEditor.LUT),
    ParamSection(
        "LENS", listOf(
            f("sharpen", "Sharpening", 0f, -1f, 1f, 2, { it.sharpen }) { r, v -> r.copy(sharpen = v) },
            f("cornerSoftness", "Corner softness", 0f, 0f, 1f, 2, { it.cornerSoftness }) { r, v -> r.copy(cornerSoftness = v) },
            f("chromaticAberration", "Chromatic aberration", 0f, 0f, 1f, 2, { it.chromaticAberration }) { r, v -> r.copy(chromaticAberration = v) },
            f("clarity", "Clarity", 0f, -0.5f, 0.5f, 2, { it.clarity }) { r, v -> r.copy(clarity = v) },
            f("clarityRadius", "Clarity radius", 1f, 0.3f, 3f, 2, { it.clarityRadius }) { r, v -> r.copy(clarityRadius = v) },
        )
    ),
    ParamSection(
        "HALATION & BLOOM", listOf(
            f("halation", "Halation", 0f, 0f, 1f, 2, { it.halation }) { r, v -> r.copy(halation = v) },
            f("halationThreshold", "Halation threshold", 0.7f, 0.4f, 0.95f, 2, { it.halationThreshold }) { r, v -> r.copy(halationThreshold = v) },
            f("halationRadius", "Halation radius", 1f, 0.3f, 3f, 2, { it.halationRadius }) { r, v -> r.copy(halationRadius = v) },
        ) + tintParams("halationColor", "Halation", Tint(1f, 0.32f, 0.08f), 0f, 1f, 2, { it.halationColor }) { r, v -> r.copy(halationColor = v) } +
            listOf(
                f("bloom", "Bloom / diffusion", 0f, 0f, 1f, 2, { it.bloom }) { r, v -> r.copy(bloom = v) },
                f("bloomThreshold", "Bloom threshold", 0.75f, 0.3f, 0.95f, 2, { it.bloomThreshold }) { r, v -> r.copy(bloomThreshold = v) },
                f("bloomRadius", "Bloom radius", 1f, 0.3f, 3f, 2, { it.bloomRadius }) { r, v -> r.copy(bloomRadius = v) },
            )
    ),
    ParamSection(
        "VIGNETTE", listOf(
            f("vignette", "Amount", 0f, 0f, 1f, 2, { it.vignette }) { r, v -> r.copy(vignette = v) },
            f("vignetteFeather", "Feather", 0.7f, 0.05f, 1f, 2, { it.vignetteFeather }) { r, v -> r.copy(vignetteFeather = v) },
            f("vignetteRoundness", "Roundness (0 = frame shape)", 1f, 0f, 1f, 2, { it.vignetteRoundness }) { r, v -> r.copy(vignetteRoundness = v) },
        ) + tintParams("vignetteColor", "Edge color", Tint(), 0f, 1f, 2, { it.vignetteColor }) { r, v -> r.copy(vignetteColor = v) },
        note = "Edge color 0/0/0 darkens to black; a warm color tints the corners."
    ),
    ParamSection(
        "LIGHT LEAK", listOf(
            f("lightLeak", "Amount", 0f, 0f, 1f, 2, { it.lightLeak }) { r, v -> r.copy(lightLeak = v) },
            f("lightLeakAngle", "Direction (°)", 45f, 0f, 360f, 0, { it.lightLeakAngle }) { r, v -> r.copy(lightLeakAngle = v) },
            f("lightLeakSize", "Size", 0.5f, 0.1f, 1.5f, 2, { it.lightLeakSize }) { r, v -> r.copy(lightLeakSize = v) },
        ) + tintParams("lightLeakColor", "Leak", Tint(1f, 0.45f, 0.15f), 0f, 1f, 2, { it.lightLeakColor }) { r, v -> r.copy(lightLeakColor = v) },
        note = "Direction 0° = right edge, 90° = bottom, 180° = left, 270° = top."
    ),
    ParamSection(
        "DUST & SCRATCHES", listOf(
            f("dust", "Dust", 0f, 0f, 1f, 2, { it.dust }) { r, v -> r.copy(dust = v) },
            f("scratches", "Scratches", 0f, 0f, 1f, 2, { it.scratches }) { r, v -> r.copy(scratches = v) },
        ),
        note = "Each photo gets its own random pattern."
    ),
    ParamSection(
        "GRAIN", listOf(
            f("grain", "Amount", 0f, 0f, 0.1f, 3, { it.grain }) { r, v -> r.copy(grain = v) },
            f("grainSize", "Size", 1f, 1f, 3f, 2, { it.grainSize }) { r, v -> r.copy(grainSize = v) },
            f("grainColor", "Color", 0f, 0f, 1f, 2, { it.grainColor }) { r, v -> r.copy(grainColor = v) },
            f("grainRoughness", "Roughness", 0f, 0f, 1f, 2, { it.grainRoughness }) { r, v -> r.copy(grainRoughness = v) },
            f("grainShadows", "In shadows", 0f, 0f, 2f, 2, { it.grainShadows }) { r, v -> r.copy(grainShadows = v) },
            f("grainMids", "In midtones", 1f, 0f, 2f, 2, { it.grainMids }) { r, v -> r.copy(grainMids = v) },
            f("grainHighlights", "In highlights", 0f, 0f, 2f, 2, { it.grainHighlights }) { r, v -> r.copy(grainHighlights = v) },
        )
    ),
    ParamSection(
        "FRAME", listOf(
            choice("aspect", "Aspect ratio", AspectRatio.entries, { it.aspect }) { r, v -> r.copy(aspect = v) },
            choice("border", "Border", BorderStyle.entries, { it.border }) { r, v -> r.copy(border = v) },
            choice("borderColor", "Border color", BorderColor.entries, { it.borderColor }) { r, v -> r.copy(borderColor = v) },
            f("borderWidth", "Border width", 0.04f, 0.01f, 0.12f, 3, { it.borderWidth }) { r, v -> r.copy(borderWidth = v) },
            ToggleParam("borderText", "Edge text", { it.borderText }) { r, v -> r.copy(borderText = v) },
        ),
        note = "Ratios follow the photo's orientation. The 35mm border is always black."
    ),
    ParamSection(
        "DATE STAMP", listOf(
            ToggleParam("dateStamp", "Date stamp", { it.dateStamp }) { r, v -> r.copy(dateStamp = v) },
            choice("dateFormat", "Format", DateFormat.entries, { it.dateFormat }) { r, v -> r.copy(dateFormat = v) },
            choice("dateCorner", "Corner", StampCorner.entries, { it.dateCorner }) { r, v -> r.copy(dateCorner = v) },
            choice("dateColor", "Color", StampColor.entries, { it.dateColor }) { r, v -> r.copy(dateColor = v) },
            f("dateSize", "Size", 1f, 0.5f, 2.5f, 2, { it.dateSize }) { r, v -> r.copy(dateSize = v) },
        )
    ),
    ParamSection(
        "OUTPUT", listOf(
            ToggleParam("keepOriginal", "Also save unedited JPEG", { it.keepOriginal }) { r, v -> r.copy(keepOriginal = v) },
            f("jpegQuality", "JPEG quality", 95f, 60f, 100f, 0, { it.jpegQuality.toFloat() }) { r, v -> r.copy(jpegQuality = v.roundToInt()) },
        )
    ),
    ParamSection(
        "CAMERA", listOf(
            choice("whiteBalance", "White balance", WhiteBalance.entries, { it.whiteBalance }) { r, v -> r.copy(whiteBalance = v) },
            ToggleParam("defaultEvOn", "Set EV when picked", { it.defaultEvOn }) { r, v -> r.copy(defaultEvOn = v) },
            f("defaultEv", "EV when picked", 0f, -3f, 3f, 1, { it.defaultEv }) { r, v -> r.copy(defaultEv = v) },
            choice("defaultFormat", "Format when picked", DefaultFormat.entries, { it.defaultFormat }) { r, v -> r.copy(defaultFormat = v) },
        ),
        special = SpecialEditor.DEFAULT_LENS,
        note = "Fixed white balance behaves like film balanced for that light: daylight film turns orange under bulbs."
    ),
)

/** All plain parameters, for saving and loading. */
val AllRecipeParams: List<RecipeParam> = RecipeSections.flatMap { it.params }
