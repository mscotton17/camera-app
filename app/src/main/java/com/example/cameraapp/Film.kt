package com.example.cameraapp

import android.hardware.camera2.CaptureRequest

/** Per-channel values (0..1 units for tints; plain RGB for colours). */
data class Tint(val r: Float = 0f, val g: Float = 0f, val b: Float = 0f)

/** The eight hue bands of the per-colour HSL controls, with their centre hue in degrees. */
enum class ColorBand(val label: String, val hueDeg: Float, val swatch: Long) {
    RED("RED", 0f, 0xFFE5484D),
    ORANGE("ORANGE", 30f, 0xFFF76B15),
    YELLOW("YELLOW", 60f, 0xFFFFC53D),
    GREEN("GREEN", 120f, 0xFF46A758),
    AQUA("AQUA", 180f, 0xFF12A594),
    BLUE("BLUE", 240f, 0xFF3E63DD),
    PURPLE("PURPLE", 270f, 0xFF8E4EC6),
    MAGENTA("MAGENTA", 300f, 0xFFD6409F),
}

/** Adjustment for one hue band: hue shift in degrees (±30), saturation (±1), luminance (±0.5). */
data class HslAdjust(val hue: Float = 0f, val sat: Float = 0f, val lum: Float = 0f) {
    val isNeutral: Boolean get() = hue == 0f && sat == 0f && lum == 0f
}

/** Custom curves: nine points at input 0, 1/8 … 1, each an offset from the straight line. */
enum class CurveChannel(val label: String) { MASTER("RGB"), RED("RED"), GREEN("GREEN"), BLUE("BLUE") }

const val CURVE_POINTS = 9
val NeutralCurve: List<Float> = List(CURVE_POINTS) { 0f }

/** 3x3 channel mixer: each output channel as a mix of the input R, G, B. Identity by default. */
data class Mixer(
    val rr: Float = 1f, val rg: Float = 0f, val rb: Float = 0f,
    val gr: Float = 0f, val gg: Float = 1f, val gb: Float = 0f,
    val br: Float = 0f, val bg: Float = 0f, val bb: Float = 1f,
) {
    fun rows(): Array<FloatArray> = arrayOf(
        floatArrayOf(rr, rg, rb), floatArrayOf(gr, gg, gb), floatArrayOf(br, bg, bb)
    )
}

/** Anything chosen from a fixed list stores a stable [key] in saved recipes. */
interface Keyed { val key: String; val label: String }

enum class AspectRatio(override val key: String, override val label: String, val ratio: Float) : Keyed {
    NATIVE("native", "4:3", 0f),
    SQUARE("1:1", "1:1", 1f),
    FIVE_FOUR("5:4", "5:4", 1.25f),
    SIX_SEVEN("6x7", "6x7", 7f / 6f),
    THREE_TWO("3:2", "3:2", 1.5f),
    SIXTEEN_NINE("16:9", "16:9", 16f / 9f),
    CINEMA("2.39:1", "2.39:1", 2.39f),
    PANORAMA("65:24", "65:24", 65f / 24f),
}

enum class BorderStyle(override val key: String, override val label: String) : Keyed {
    NONE("none", "NONE"),
    THIN("thin", "THIN"),
    INSTANT("instant", "INSTANT"),
    FILED("filed", "FILED"),
    REBATE("rebate", "35MM"),
}

enum class BorderColor(override val key: String, override val label: String, val argb: Int) : Keyed {
    BLACK("black", "BLACK", 0xFF0C0C0C.toInt()),
    WHITE("white", "WHITE", 0xFFF3F1EA.toInt()),
    GREY("grey", "GREY", 0xFF6E6E6A.toInt()),
}

enum class DateFormat(override val key: String, override val label: String, val pattern: String) : Keyed {
    YMD("ymd", "'YY MM DD", "''yy MM dd"),
    MDY("mdy", "MM DD 'YY", "MM dd ''yy"),
    DMY("dmy", "DD MM 'YY", "dd MM ''yy"),
    FULL("full", "YYYY.MM.DD", "yyyy.MM.dd"),
}

enum class StampCorner(override val key: String, override val label: String) : Keyed {
    BOTTOM_RIGHT("br", "BOTTOM R"),
    BOTTOM_LEFT("bl", "BOTTOM L"),
    TOP_RIGHT("tr", "TOP R"),
    TOP_LEFT("tl", "TOP L"),
}

enum class StampColor(override val key: String, override val label: String, val argb: Int) : Keyed {
    ORANGE("orange", "ORANGE", 0xFFFF8A1F.toInt()),
    RED("red", "RED", 0xFFFF3B2F.toInt()),
    YELLOW("yellow", "YELLOW", 0xFFFFD23F.toInt()),
    WHITE("white", "WHITE", 0xFFF5F5F5.toInt()),
}

/** Camera white-balance mode; anything but AUTO behaves like film balanced for that light. */
enum class WhiteBalance(override val key: String, override val label: String, val awbMode: Int) : Keyed {
    AUTO("auto", "AUTO", CaptureRequest.CONTROL_AWB_MODE_AUTO),
    DAYLIGHT("daylight", "DAYLIGHT", CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT),
    CLOUDY("cloudy", "CLOUDY", CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    SHADE("shade", "SHADE", CaptureRequest.CONTROL_AWB_MODE_SHADE),
    TWILIGHT("twilight", "TWILIGHT", CaptureRequest.CONTROL_AWB_MODE_TWILIGHT),
    TUNGSTEN("tungsten", "TUNGSTEN", CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT),
    FLUORESCENT("fluorescent", "FLUOR.", CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT),
    WARM_FLUORESCENT("warm_fluorescent", "WARM FLUOR.", CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT),
}

/** DR200/DR400 underexpose by 1/2 stops to protect highlights, then lift the image back. */
enum class DynamicRange(override val key: String, override val label: String, val stops: Int) : Keyed {
    DR100("100", "DR100", 0),
    DR200("200", "DR200", 1),
    DR400("400", "DR400", 2),
}

enum class DefaultFormat(override val key: String, override val label: String, val format: CaptureFormat?) : Keyed {
    KEEP("keep", "KEEP", null),
    DNG_FILM("dng_film", "DNG+FILM", CaptureFormat.DNG_FILM),
    JPG("jpg", "JPG", CaptureFormat.JPEG),
    RAW("raw", "RAW", CaptureFormat.RAW),
    RAW_JPG("raw_jpg", "RAW+JPG", CaptureFormat.RAW_JPEG),
}

/**
 * A film-style look. Every setting has a neutral default, so a recipe only lists what it
 * changes. Processing order (identical in the preview shader and the capture pass):
 *
 *  Lens:   sharpen, corner softness, chromatic aberration
 *  1.  Colour matrix: temperature, red/blue gain, tint, channel mixer, hue shift, saturation / mono
 *  2.  Colour chrome
 *  3.  Colour stage: vibrance, highlight/shadow saturation, blue chrome, HSL bands, skin protection
 *  4.  Local tone: highlight / shadow recovery
 *  5.  Halation and bloom
 *  6.  Tone: dynamic range, exposure, toe/shoulder, curve, split toning, contrast, custom curves
 *  7.  Bleach bypass
 *  8.  3D LUT (.cube)
 *  9.  Clarity
 *  10. Light leak
 *  11. Vignette
 *  12. Dust and scratches
 *  13. Grain
 *  Output: aspect crop, date stamp, border, JPEG quality, keep original.
 */
data class FilmRecipe(
    val id: String,
    val label: String,

    // Colour balance
    val temperature: Float = 6500f,
    val redGain: Float = 1f,
    val blueGain: Float = 1f,
    val tint: Float = 0f,
    val mixer: Mixer = Mixer(),
    val hueShift: Float = 0f,

    // Saturation
    val satR: Float = 1f,
    val satG: Float = 1f,
    val satB: Float = 1f,
    val vibrance: Float = 0f,
    val highlightSat: Float = 0f,
    val shadowSat: Float = 0f,
    val colorChrome: Float = 0f,
    val colorChromeBlue: Float = 0f,
    val bleachBypass: Float = 0f,
    val skinProtect: Float = 0f,
    /** Channel weights for monochrome, or null for colour. */
    val mono: Triple<Float, Float, Float>? = null,
    val hsl: Map<ColorBand, HslAdjust> = emptyMap(),

    // Tone
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val blackPoint: Float = 0f,
    val shadows: Float = 0f,
    val mids: Float = 0f,
    val highlights: Float = 0f,
    val whitePoint: Float = 1f,
    val toe: Float = 0f,
    val shoulder: Float = 0f,
    val highlightRecovery: Float = 0f,
    val shadowRecovery: Float = 0f,
    val dynamicRange: DynamicRange = DynamicRange.DR100,
    val curves: Map<CurveChannel, List<Float>> = emptyMap(),
    val shadowTint: Tint = Tint(),
    val midTint: Tint = Tint(),
    val highlightTint: Tint = Tint(),

    // 3D LUT
    val lutName: String? = null,
    val lutStrength: Float = 1f,

    // Lens & optics
    val sharpen: Float = 0f,
    val cornerSoftness: Float = 0f,
    val chromaticAberration: Float = 0f,
    val clarity: Float = 0f,
    val clarityRadius: Float = 1f,
    val halation: Float = 0f,
    val halationThreshold: Float = 0.7f,
    val halationRadius: Float = 1f,
    val halationColor: Tint = Tint(1f, 0.32f, 0.08f),
    val bloom: Float = 0f,
    val bloomThreshold: Float = 0.75f,
    val bloomRadius: Float = 1f,

    // Vignette
    val vignette: Float = 0f,
    val vignetteFeather: Float = 0.7f,
    val vignetteRoundness: Float = 1f,
    val vignetteColor: Tint = Tint(),

    // Light leak
    val lightLeak: Float = 0f,
    val lightLeakAngle: Float = 45f,
    val lightLeakSize: Float = 0.5f,
    val lightLeakColor: Tint = Tint(1f, 0.45f, 0.15f),

    // Dust & scratches
    val dust: Float = 0f,
    val scratches: Float = 0f,

    // Grain
    val grain: Float = 0f,
    val grainSize: Float = 1f,
    val grainColor: Float = 0f,
    /**
     * How much colour grain follows the recipe's shadow / mid tints (default: cool magenta)
     * instead of random RGB, like dye clouds in colour negative film. 0 = fully random.
     */
    val grainColorBias: Float = 0.6f,
    val grainRoughness: Float = 0f,
    val grainShadows: Float = 0f,
    val grainMids: Float = 1f,
    val grainHighlights: Float = 0f,

    // Frame & output
    val aspect: AspectRatio = AspectRatio.NATIVE,
    val border: BorderStyle = BorderStyle.NONE,
    val borderColor: BorderColor = BorderColor.BLACK,
    val borderWidth: Float = 0.04f,
    val borderText: Boolean = true,
    val dateStamp: Boolean = false,
    val dateFormat: DateFormat = DateFormat.YMD,
    val dateCorner: StampCorner = StampCorner.BOTTOM_RIGHT,
    val dateColor: StampColor = StampColor.ORANGE,
    val dateSize: Float = 1f,
    val keepOriginal: Boolean = false,
    val jpegQuality: Int = 95,

    // Camera behaviour
    val whiteBalance: WhiteBalance = WhiteBalance.AUTO,
    val defaultEvOn: Boolean = false,
    val defaultEv: Float = 0f,
    /** 35mm-equivalent focal length to switch to when the recipe is picked; 0 = keep. */
    val defaultLensMm: Int = 0,
    val defaultFormat: DefaultFormat = DefaultFormat.KEEP,
    /**
     * Not saved: the lift actually applied for [dynamicRange], set by the camera screen to
     * match the underexposure the camera really achieved (0 in full manual). -1 = use the mode.
     */
    val drLiftStops: Float = -1f,
) {
    /** Stops the tone curve lifts to undo the DR underexposure. */
    internal val effectiveDrLift: Float
        get() = if (drLiftStops >= 0f) drLiftStops else dynamicRange.stops.toFloat()

    /** Linear part as an Android ColorMatrix. */
    val colorMatrix: android.graphics.ColorMatrix by lazy { buildColorMatrix(this) }

    /** Tone curves as 256-entry tables: [0..255] red, [256..511] green, [512..767] blue. */
    val lut: IntArray by lazy { buildLut(this) }

    internal val hslHue: FloatArray by lazy { FloatArray(8) { (hsl[ColorBand.entries[it]]?.hue ?: 0f) / 360f } }
    internal val hslSat: FloatArray by lazy { FloatArray(8) { hsl[ColorBand.entries[it]]?.sat ?: 0f } }
    internal val hslLum: FloatArray by lazy { FloatArray(8) { hsl[ColorBand.entries[it]]?.lum ?: 0f } }
    internal val hasHsl: Boolean get() = hsl.values.any { !it.isNeutral }

    /** Anything in the per-pixel colour stage (step 3) is active. */
    internal val hasColorStage: Boolean
        get() = hasHsl || vibrance != 0f || highlightSat != 0f || shadowSat != 0f ||
            colorChromeBlue > 0f || skinProtect > 0f

    internal val hasOptics: Boolean get() = sharpen != 0f || cornerSoftness > 0f || chromaticAberration > 0f
    internal val hasLocalTone: Boolean get() = highlightRecovery > 0f || shadowRecovery > 0f

    fun curve(channel: CurveChannel): List<Float> = curves[channel] ?: NeutralCurve
}

/** Built-in recipes (all 3:2). There's no "no recipe" option: the first one is the default. */
val FilmRecipes: List<FilmRecipe> = listOf(
    // Reggie's HP5: punchy black and white through a red filter — deep contrast, dark skies,
    // fine organic grain that sits mostly in the shadows.
    FilmRecipe(
        id = "reggies_hp5", label = "REGGIE'S HP5",
        mono = Triple(0.60f, 0.35f, 0.05f),
        contrast = 0.15f, blackPoint = 0.005f, shadows = -0.055f, mids = 0.010f, highlights = 0.050f, whitePoint = 0.99f,
        toe = 0.12f, shoulder = 0.35f, dynamicRange = DynamicRange.DR200,
        sharpen = -0.15f,
        clarity = 0.10f, clarityRadius = 0.9f,
        vignette = 0.15f, vignetteFeather = 0.75f, vignetteRoundness = 1.0f,
        grain = 0.025f, grainSize = 1.0f, grainColor = 0f, grainRoughness = 0.25f,
        grainShadows = 1.1f, grainMids = 1.0f, grainHighlights = 0.2f,
        aspect = AspectRatio.THREE_TWO,
        whiteBalance = WhiteBalance.AUTO, defaultEvOn = true, defaultEv = 0f,
    ),
    // Pushed HP5: crisp contrast, punchy micro-texture, gentle sky separation through a
    // yellow-ish filter mix, fine organic grain; 3:2 frame.
    FilmRecipe(
        id = "pushed_hp5", label = "PUSHED HP5",
        mono = Triple(0.38f, 0.47f, 0.15f),
        contrast = 0.12f, blackPoint = 0f, shadows = -0.055f, mids = -0.020f, highlights = -0.040f, whitePoint = 0.970f,
        toe = 0.06f, shoulder = 0.45f, dynamicRange = DynamicRange.DR100,
        sharpen = 0.15f,
        clarity = 0.12f, clarityRadius = 0.9f,
        vignette = 0.12f, vignetteFeather = 0.80f, vignetteRoundness = 1.0f,
        grain = 0.025f, grainSize = 1.0f, grainColor = 0f, grainRoughness = 0.35f,
        grainShadows = 1.2f, grainMids = 1.0f, grainHighlights = 0.1f,
        aspect = AspectRatio.THREE_TWO,
        whiteBalance = WhiteBalance.AUTO, defaultEvOn = true, defaultEv = 0f,
    ),
    // Reggie's Portra: warm golden-peach midtones, gentle highlight roll-off, soft shadows and
    // subtle warm dye grain on a muted documentary base.
    FilmRecipe(
        id = "reggies_portra", label = "REGGIE'S PORTRA",
        temperature = 6600f, redGain = 1.05f, blueGain = 0.93f, tint = 0.008f, hueShift = 0f,
        satR = 1.08f, satG = 0.95f, satB = 0.92f, vibrance = 0.20f, highlightSat = -0.15f, shadowSat = 0.05f,
        colorChrome = 0.70f, colorChromeBlue = 0.35f, bleachBypass = 0f, skinProtect = 0.50f,
        hsl = mapOf(
            ColorBand.ORANGE to HslAdjust(sat = 0.08f),
            ColorBand.YELLOW to HslAdjust(hue = -3f),
            ColorBand.GREEN to HslAdjust(hue = -8f, sat = -0.12f),
            ColorBand.AQUA to HslAdjust(hue = 4f),
            ColorBand.BLUE to HslAdjust(sat = -0.08f, lum = -0.05f),
        ),
        exposure = 0f, contrast = -0.05f, blackPoint = 0.020f, shadows = 0.030f, mids = 0.015f,
        highlights = -0.035f, whitePoint = 0.97f,
        toe = 0.20f, shoulder = 0.40f, highlightRecovery = 0.10f, shadowRecovery = 0.05f,
        dynamicRange = DynamicRange.DR200,
        shadowTint = Tint(r = -0.008f, g = 0.004f, b = 0.010f),
        midTint = Tint(r = 0.016f, g = 0.006f, b = -0.014f),
        highlightTint = Tint(r = 0.006f, g = 0.002f, b = -0.004f),
        sharpen = -0.20f, cornerSoftness = 0.08f, chromaticAberration = 0f,
        clarity = -0.05f, clarityRadius = 1.0f,
        bloom = 0.15f, bloomThreshold = 0.75f, bloomRadius = 1.0f,
        vignette = 0.18f, vignetteFeather = 0.80f, vignetteRoundness = 1.0f,
        grain = 0.025f, grainSize = 1.0f, grainColor = 0.20f, grainColorBias = 0.65f, grainRoughness = 0.20f,
        grainShadows = 1.1f, grainMids = 1.0f, grainHighlights = 0.2f,
        aspect = AspectRatio.THREE_TWO,
        whiteBalance = WhiteBalance.AUTO, defaultEvOn = true, defaultEv = 1f / 3f,
    ),
    // Tri-X Noir: classic pushed Kodak Tri-X 400 street look — true inky blacks, bright punchy
    // whites, gritty midtone micro-contrast and no lifted matte wash.
    FilmRecipe(
        id = "tri_x_noir", label = "TRI-X NOIR",
        mono = Triple(0.55f, 0.40f, 0.05f),
        contrast = 0.28f, blackPoint = 0f, shadows = -0.075f, mids = 0.015f, highlights = 0.050f, whitePoint = 1f,
        toe = 0.05f, shoulder = 0.35f, highlightRecovery = 0f, shadowRecovery = 0f, dynamicRange = DynamicRange.DR100,
        sharpen = 0.20f, cornerSoftness = 0f, chromaticAberration = 0f,
        clarity = 0.35f, clarityRadius = 0.8f,
        vignette = 0.30f, vignetteFeather = 0.70f, vignetteRoundness = 1.0f,
        grain = 0.040f, grainSize = 1.3f, grainColor = 0f, grainRoughness = 0.50f,
        grainShadows = 0.8f, grainMids = 1.2f, grainHighlights = 0.1f,
        aspect = AspectRatio.THREE_TWO,
        whiteBalance = WhiteBalance.AUTO, defaultEvOn = true, defaultEv = -1f / 3f,
    ),
)
