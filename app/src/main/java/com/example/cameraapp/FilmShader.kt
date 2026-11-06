package com.example.cameraapp

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi
import kotlin.math.cos
import kotlin.math.sin

private const val FILM_AGSL = """
uniform shader content;
uniform shader lut;
uniform shader cube;
uniform float4 frame;           // image rect inside the view: left, top, width, height (px)
uniform float seed;

uniform float lensOn;
uniform float3 lensParams;      // sharpen, corner softness (fraction of width), CA shift (fraction)

uniform float3x3 cm;
uniform float colorChrome;
uniform float4 chromeParams;    // low, high, desat, deepen

uniform float colorOn;
uniform float vibrance;
uniform float2 tonalSat;        // highlight, shadow
uniform float chromeBlue;
uniform float hslOn;
uniform float4 hslCenterA;
uniform float4 hslCenterB;
uniform float4 hslRightA;
uniform float4 hslRightB;
uniform float4 hslLeftA;
uniform float4 hslLeftB;
uniform float4 hslHueA;
uniform float4 hslHueB;
uniform float4 hslSatA;
uniform float4 hslSatB;
uniform float4 hslLumA;
uniform float4 hslLumB;
uniform float hslSatFade;
uniform float3 skin;            // hue, width, amount

uniform float2 localTone;       // highlight recovery * gain, shadow recovery * gain
uniform float localRadius;
uniform float halation;
uniform float4 halationParams;  // r, g, b, threshold
uniform float halationRadius;
uniform float bloom;            // amount * gain
uniform float3 bloomParams;     // threshold, radius, raw amount

uniform float bleach;
uniform float cubeOn;
uniform float2 cubeParams;      // size, strength

uniform float clarity;
uniform float clarityRadius;

uniform float leak;
uniform float4 leakParams;      // cos(angle), sin(angle), size, unused
uniform float3 leakColor;
uniform float vignette;
uniform float2 vignetteParams;  // start, roundness
uniform float3 vignetteColor;
uniform float2 dustParams;      // dust, scratches

uniform float grainAmount;
uniform float grainCellFraction;
uniform float grainColor;
uniform float grainBoost;
uniform float grainRough;
uniform float grainColorBias;
uniform float3 grainDirS;       // colour-grain tint direction in shadows (zero-sum, max 1)
uniform float3 grainDirM;       // ... and in the mids
uniform float3 grainTone;       // shadows, mids, highlights
uniform float4 grainShape;      // min cell px, fine scale, clump scale, norm
uniform float3 hlDesat;         // start, end (white point), amount
uniform float densityGain;

float luma(float3 c) { return dot(c, float3(0.2126, 0.7152, 0.0722)); }

float hashS(float2 p, float s) {
    return fract(sin(dot(p, float2(12.9898, 78.233)) + s) * 43758.5453);
}

float3 srcAt(float2 q) {
    return float3(content.eval(clamp(q, frame.xy, frame.xy + frame.zw - 1.0)).rgb);
}

float lumaAt(float2 q) { return luma(srcAt(q)); }

float3 lensSample(float2 q, float blurR) {
    float3 c = srcAt(q);
    if (blurR < 0.5) return c;
    for (int i = 0; i < 8; i++) {
        float a = float(i) * 0.7853982;
        c += srcAt(q + float2(cos(a), sin(a)) * blurR);
    }
    return c / 9.0;
}

// Smooth value noise in [-1, 1], contrast restored like the CPU's.
float vnoise(float2 q, float2 o, float s) {
    float2 i = floor(q);
    float2 f = fract(q);
    f = f * f * (3.0 - 2.0 * f);
    float a = hashS(i + o, s);
    float b = hashS(i + float2(1.0, 0.0) + o, s);
    float c = hashS(i + float2(0.0, 1.0) + o, s);
    float d = hashS(i + float2(1.0, 1.0) + o, s);
    float v = mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
    return clamp((v * 2.0 - 1.0) * grainBoost, -1.0, 1.0);
}

// Clumped grain in [0, 1]: grain + finer sub-grains shaped by a larger clump envelope.
float noise01(float2 p, float2 o) {
    float cell = max(grainShape.x, frame.z * grainCellFraction);
    float2 q = p / cell;
    float coarse = vnoise(q, o, seed);
    float fine = vnoise(q / grainShape.y + float2(17.3, 5.1), o, seed + 7.0);
    float clump = vnoise(q / grainShape.z + float2(3.7, 9.2), o, seed + 13.0);
    float env = 0.35 + 0.65 * (clump * 0.5 + 0.5);
    float n = (0.6 * coarse + 0.4 * fine) * env * grainShape.w;
    float px = hashS(floor(p) + o, seed) * 2.0 - 1.0;
    n += (px * env - n) * grainRough * 0.5;
    return clamp(n, -1.0, 1.0) * 0.5 + 0.5;
}

float bump(float l, float c, float w) { float t = (l - c) / w; return max(0.0, 1.0 - t * t); }

float3 rgb2hsv(float3 c) {
    float mx = max(c.r, max(c.g, c.b));
    float mn = min(c.r, min(c.g, c.b));
    float d = mx - mn;
    float h = 0.0;
    if (d > 0.0) {
        if (mx == c.r) h = fract(((c.g - c.b) / d) / 6.0);
        else if (mx == c.g) h = ((c.b - c.r) / d + 2.0) / 6.0;
        else h = ((c.r - c.g) / d + 4.0) / 6.0;
    }
    return float3(h, mx > 0.0 ? d / mx : 0.0, mx);
}

float3 hsv2rgb(float3 c) {
    float3 p = abs(fract(c.x + float3(1.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    return c.z * (1.0 + c.y * (clamp(p - 1.0, 0.0, 1.0) - 1.0));
}

float4 bandWeights(float h, float4 center, float4 right, float4 left) {
    float4 d = fract(h - center + 0.5) - 0.5;
    return max(float4(0.0), 1.0 - max(d / right, -d / left));
}

float bleachChannel(float v, float l) {
    float o = v < 0.5 ? 2.0 * v * l : 1.0 - 2.0 * (1.0 - v) * (1.0 - l);
    float m = mix(v, o, bleach);
    return mix(m, l, bleach * 0.35);
}

float3 cubeAt(float3 c) {
    float n = cubeParams.x;
    float n1 = n - 1.0;
    float3 f = clamp(c, 0.0, 1.0) * n1;
    float b0 = min(floor(f.b), n1 - 1.0);
    float tb = f.b - b0;
    // Bilinear within a blue slice comes from the texture's filtering; blend two slices for blue.
    float2 uv0 = float2(f.r + 0.5 + b0 * n, f.g + 0.5);
    float2 uv1 = float2(f.r + 0.5 + (b0 + 1.0) * n, f.g + 0.5);
    return mix(float3(cube.eval(uv0).rgb), float3(cube.eval(uv1).rgb), tb);
}

half4 main(float2 p) {
    half4 c = content.eval(p);
    // Leave the black letterbox bars alone.
    if (max(c.r, max(c.g, c.b)) < 0.004) return c;

    float2 center = frame.xy + frame.zw * 0.5;
    float2 d = p - center;
    float dist = length(d);
    float halfDiag = 0.5 * length(frame.zw);
    float rn = dist / halfDiag;

    // Lens: corner softness, chromatic aberration, sharpening.
    float3 src = float3(c.rgb);
    if (lensOn > 0.0) {
        float r2 = rn * rn;
        float blurR = lensParams.y * frame.z * r2;
        src = lensSample(p, blurR);
        if (lensParams.z > 0.0 && dist > 0.0) {
            float2 u = d / dist * lensParams.z * frame.z * r2;
            src.r = lensSample(p + u, blurR).r;
            src.b = lensSample(p - u, blurR).b;
        }
        if (lensParams.x != 0.0) {
            float3 avg = (srcAt(p + float2(1.0, 0.0)) + srcAt(p - float2(1.0, 0.0)) +
                          srcAt(p + float2(0.0, 1.0)) + srcAt(p - float2(0.0, 1.0))) * 0.25;
            src += lensParams.x * 1.2 * (srcAt(p) - avg);
        }
    }
    float srcLuma = luma(src);

    // 1. Colour matrix.
    float3 rgb = cm * src;

    // 2. Colour chrome.
    float chromeW = 0.0;
    if (colorChrome > 0.0) {
        float chroma = max(rgb.r, max(rgb.g, rgb.b)) - min(rgb.r, min(rgb.g, rgb.b));
        float w = smoothstep(chromeParams.x, chromeParams.y, chroma) * colorChrome;
        chromeW = w;
        float l = luma(rgb);
        rgb = (l + (rgb - l) * (1.0 - chromeParams.z * w)) * (1.0 - chromeParams.w * w);
    }
    rgb = clamp(rgb, 0.0, 1.0);

    // 3. Colour stage.
    if (colorOn > 0.0) {
        float3 hsv = rgb2hsv(rgb);
        if (vibrance != 0.0) hsv.y = clamp(hsv.y * (1.0 + vibrance * (1.0 - hsv.y)), 0.0, 1.0);
        float k = 1.0 + tonalSat.x * smoothstep(0.55, 1.0, hsv.z) + tonalSat.y * (1.0 - smoothstep(0.0, 0.45, hsv.z));
        hsv.y = clamp(hsv.y * k, 0.0, 1.0);
        if (chromeBlue > 0.0) {
            // Eased toward cyan, and only what colour chrome hasn't already done.
            float4 wB0 = bandWeights(hsv.x, hslCenterB, hslRightB, hslLeftB);
            float w = smoothstep(0.25, 1.0, wB0.y) * smoothstep(0.35, 0.8, hsv.y) * chromeBlue * (1.0 - chromeW);
            hsv.y *= 1.0 - 0.2 * w;
            hsv.z *= 1.0 - 0.22 * w;
        }
        if (hslOn > 0.0) {
            float4 wA = bandWeights(hsv.x, hslCenterA, hslRightA, hslLeftA);
            float4 wB = bandWeights(hsv.x, hslCenterB, hslRightB, hslLeftB);
            float hueShift = dot(wA, hslHueA) + dot(wB, hslHueB);
            float satAdj = dot(wA, hslSatA) + dot(wB, hslSatB);
            float lumAdj = dot(wA, hslLumA) + dot(wB, hslLumB);
            float fade = smoothstep(0.0, hslSatFade, hsv.y);
            hsv.x = fract(hsv.x + hueShift * fade);
            hsv.y = clamp(hsv.y * (1.0 + satAdj), 0.0, 1.0);
            hsv.z = hsv.z * (1.0 + lumAdj * fade);
        }
        if (colorChrome > 0.0) {
            // Colour density: saturated colours (reds / oranges most) darken with saturation.
            float4 wD = bandWeights(hsv.x, hslCenterA, hslRightA, hslLeftA);
            float hueBias = 0.4 + 0.6 * max(wD.x, wD.y);
            hsv.z *= 1.0 - densityGain * colorChrome * hsv.y * smoothstep(0.2, 0.9, hsv.y) * hueBias;
        }
        rgb = hsv2rgb(hsv);
        if (skin.z > 0.0) {
            float3 o = rgb2hsv(clamp(src, 0.0, 1.0));
            float dh = abs(fract(o.x - skin.x + 0.5) - 0.5);
            float w = max(0.0, 1.0 - dh / skin.y) * smoothstep(0.1, 0.2, o.y) *
                (1.0 - smoothstep(0.65, 0.85, o.y)) * smoothstep(0.15, 0.3, o.z) * skin.z * 0.85;
            rgb = mix(rgb, clamp(src, 0.0, 1.0), w);
        }
    }

    // 4-5. Local tone, halation and bloom from ring blurs of the original image.
    if (localTone.x > 0.0 || localTone.y > 0.0) {
        float r = frame.z * localRadius;
        float bl = 0.0;
        for (int i = 0; i < 6; i++) {
            float a = float(i) * 1.0471976;
            bl += lumaAt(p + float2(cos(a), sin(a)) * r);
            bl += lumaAt(p + float2(cos(a + 0.5235988), sin(a + 0.5235988)) * r * 0.5);
        }
        bl /= 12.0;
        rgb *= (1.0 + localTone.y * (1.0 - smoothstep(0.05, 0.45, bl))) *
               (1.0 - localTone.x * smoothstep(0.55, 0.95, bl));
    }
    if (halation > 0.0) {
        float r = frame.z * halationRadius;
        float glow = 0.0;
        for (int i = 0; i < 6; i++) {
            float a = float(i) * 1.0471976;
            glow += smoothstep(halationParams.w, 1.0, lumaAt(p + float2(cos(a), sin(a)) * r));
            glow += smoothstep(halationParams.w, 1.0,
                lumaAt(p + float2(cos(a + 0.5235988), sin(a + 0.5235988)) * r * 0.5));
        }
        rgb += halationParams.rgb * (glow / 12.0) * halation;
    }
    if (bloom > 0.0) {
        float r = frame.z * bloomParams.y;
        float glow = 0.0;
        for (int i = 0; i < 6; i++) {
            float a = float(i) * 1.0471976;
            glow += smoothstep(bloomParams.x, 1.0, lumaAt(p + float2(cos(a), sin(a)) * r));
            glow += smoothstep(bloomParams.x, 1.0,
                lumaAt(p + float2(cos(a + 0.5235988), sin(a + 0.5235988)) * r * 0.5));
        }
        float g = clamp(glow / 12.0 * bloom, 0.0, 1.0);
        float3 base = clamp(rgb, 0.0, 1.0);
        rgb = 1.0 - (1.0 - base) * (1.0 - g) + bloomParams.z * 0.03 * (1.0 - rgb);
    }
    rgb = clamp(rgb, 0.0, 1.0);

    // 6. Tone curves.
    rgb = float3(
        lut.eval(float2(rgb.r * 255.0 + 0.5, 0.5)).r,
        lut.eval(float2(rgb.g * 255.0 + 0.5, 0.5)).g,
        lut.eval(float2(rgb.b * 255.0 + 0.5, 0.5)).b);

    // 6b. Highlight saturation roll-off toward neutral white.
    if (hlDesat.z > 0.0) {
        float l = luma(rgb);
        rgb = mix(rgb, float3(l), smoothstep(hlDesat.x, hlDesat.y, l) * hlDesat.z);
    }

    // 7. Bleach bypass.
    if (bleach > 0.0) {
        float l = luma(rgb);
        rgb = float3(bleachChannel(rgb.r, l), bleachChannel(rgb.g, l), bleachChannel(rgb.b, l));
    }

    // 8. 3D LUT.
    if (cubeOn > 0.0) rgb = mix(rgb, cubeAt(rgb), cubeParams.y);

    // 9. Clarity.
    if (clarity != 0.0) {
        float r = frame.z * clarityRadius;
        float blur = 0.0;
        for (int i = 0; i < 6; i++) {
            float a = float(i) * 1.0471976;
            blur += lumaAt(p + float2(cos(a), sin(a)) * r);
            blur += lumaAt(p + float2(cos(a + 0.5235988), sin(a + 0.5235988)) * r * 0.5);
        }
        float detail = srcLuma - blur / 12.0;
        float l = clamp(luma(rgb), 0.0, 1.0);
        rgb += clarity * detail * 4.0 * l * (1.0 - l);
    }

    // 10. Light leak.
    if (leak > 0.0) {
        float2 lc = float2(leakParams.x * (frame.z * 0.5 / halfDiag), leakParams.y * (frame.w * 0.5 / halfDiag)) * 1.15;
        float2 u = d / halfDiag - lc;
        float k = leak * exp(-dot(u, u) / (leakParams.z * leakParams.z));
        rgb = 1.0 - (1.0 - clamp(rgb, 0.0, 1.0)) * (1.0 - leakColor * k);
    }

    // 11. Vignette.
    if (vignette > 0.0) {
        float2 e = d / max(float2(1.0), frame.zw * 0.5);
        float ellipse = length(e) / 1.4142135;
        float shape = mix(ellipse, rn, vignetteParams.y);
        float m = vignette * smoothstep(vignetteParams.x, 1.0, shape);
        rgb = mix(rgb, rgb * vignetteColor, m);
    }

    // 12. Dust and scratches (fixed pattern; the photo gets its own random one).
    float2 lp = p - frame.xy;
    if (dustParams.x > 0.0) {
        float cell = max(8.0, frame.z / 40.0);
        float2 ic = floor(lp / cell);
        if (hashS(ic, 11.0) < dustParams.x * 0.12) {
            float2 sp = (ic + float2(hashS(ic, 12.0), hashS(ic, 13.0))) * cell;
            float rad = (0.4 + 1.6 * hashS(ic, 14.0)) * frame.z / 900.0;
            float m = 1.0 - smoothstep(rad * 0.4, rad, length(lp - sp));
            rgb *= 1.0 - 0.75 * m;
        }
    }
    if (dustParams.y > 0.0) {
        float cell = max(8.0, frame.z / 60.0);
        float col = floor(lp.x / cell);
        if (hashS(float2(col, 0.0), 21.0) < dustParams.y * 0.08) {
            float lx = (col + hashS(float2(col, 1.0), 22.0)) * cell;
            float sw = max(1.0, frame.z / 2500.0);
            float m = 1.0 - smoothstep(sw * 0.5, sw, abs(lp.x - lx));
            float along = 0.5 + 0.5 * sin(lp.y / frame.w * 6.2831855 * (1.0 + hashS(float2(col, 2.0), 23.0)) + col);
            rgb += (1.0 - rgb) * 0.35 * m * along;
        }
    }

    // 13. Grain.
    if (grainAmount > 0.0) {
        float l = clamp(luma(rgb), 0.0, 1.0);
        float amp = grainAmount * (grainTone.x * bump(l, 0.15, 0.35) + grainTone.y * bump(l, 0.5, 0.5) + grainTone.z * bump(l, 0.85, 0.35));
        float n = noise01(p, float2(0.0, 0.0)) * 2.0 - 1.0;
        float3 g = float3(n);
        if (grainColor > 0.0) {
            // Random per-channel noise blended with noise along the tint direction (dye
            // clouds); colour grain fades out of the highlights.
            float3 cn = float3(
                noise01(p, float2(17.0, 3.0)),
                noise01(p, float2(5.0, 29.0)),
                noise01(p, float2(41.0, 11.0))) * 2.0 - 1.0;
            float ws = 1.0 - smoothstep(0.2, 0.6, l);
            float3 dir = mix(grainDirM, grainDirS, ws);
            float tinted = noise01(p, float2(23.0, 37.0)) * 2.0 - 1.0;
            float k = grainColor * (1.0 - 0.8 * smoothstep(0.55, 0.9, l));
            g += ((cn - n) * (1.0 - grainColorBias) + tinted * dir * grainColorBias) * k;
        }
        rgb += g * amp;
    }
    return half4(half3(clamp(rgb, 0.0, 1.0)), c.a);
}
"""

/**
 * Live-preview shader. One instance is compiled per screen; [update] swaps the recipe without
 * recompiling (fast enough for dragging editor sliders), [effect] produces each frame's effect.
 */
@RequiresApi(33)
class FilmPreviewShader {
    private val shader = RuntimeShader(FILM_AGSL)
    private val emptyCube = BitmapShader(
        Bitmap.createBitmap(intArrayOf(0xFF000000.toInt()), 1, 1, Bitmap.Config.ARGB_8888),
        Shader.TileMode.CLAMP, Shader.TileMode.CLAMP
    )

    init {
        // Constants that never change between recipes.
        shader.setFloatUniform("chromeParams", CHROME_LOW, CHROME_HIGH, CHROME_DESAT, CHROME_DEEPEN)
        shader.setFloatUniform("hslCenterA", BandCenters[0], BandCenters[1], BandCenters[2], BandCenters[3])
        shader.setFloatUniform("hslCenterB", BandCenters[4], BandCenters[5], BandCenters[6], BandCenters[7])
        shader.setFloatUniform("hslRightA", BandRight[0], BandRight[1], BandRight[2], BandRight[3])
        shader.setFloatUniform("hslRightB", BandRight[4], BandRight[5], BandRight[6], BandRight[7])
        shader.setFloatUniform("hslLeftA", BandLeft[0], BandLeft[1], BandLeft[2], BandLeft[3])
        shader.setFloatUniform("hslLeftB", BandLeft[4], BandLeft[5], BandLeft[6], BandLeft[7])
        shader.setFloatUniform("hslSatFade", HSL_SAT_FADE)
        shader.setFloatUniform("localRadius", LOCAL_RADIUS)
        shader.setFloatUniform("grainBoost", GRAIN_SMOOTH_BOOST)
        shader.setFloatUniform("grainShape", GRAIN_MIN_CELL, GRAIN_FINE_SCALE, GRAIN_CLUMP_SCALE, GRAIN_CLUMP_NORM)
        shader.setFloatUniform("densityGain", DENSITY_GAIN)
        shader.setFloatUniform("seed", 0f)
        shader.setFloatUniform("frame", 0f, 0f, 1f, 1f)
        update(FilmRecipe(id = "neutral", label = ""), null)
    }

    /** Loads [recipe] (and its 3D LUT [cube], if any) into the shader. */
    fun update(recipe: FilmRecipe, cube: CubeLut?) {
        val r = recipe
        shader.setFloatUniform("lensOn", if (r.hasOptics) 1f else 0f)
        shader.setFloatUniform("lensParams", r.sharpen, r.cornerSoftness * CORNER_BLUR, r.chromaticAberration * CA_SHIFT)

        val a = r.colorMatrix.array
        // AGSL matrices are column-major.
        shader.setFloatUniform("cm", floatArrayOf(a[0], a[5], a[10], a[1], a[6], a[11], a[2], a[7], a[12]))
        shader.setFloatUniform("colorChrome", r.colorChrome)

        shader.setFloatUniform("colorOn", if (r.hasColorStage) 1f else 0f)
        shader.setFloatUniform("vibrance", r.vibrance)
        shader.setFloatUniform("tonalSat", r.highlightSat, r.shadowSat)
        shader.setFloatUniform("chromeBlue", r.colorChromeBlue)
        shader.setFloatUniform("hslOn", if (r.hasHsl) 1f else 0f)
        val hue = r.hslHue
        val sat = r.hslSat
        val lum = r.hslLum
        shader.setFloatUniform("hslHueA", hue[0], hue[1], hue[2], hue[3])
        shader.setFloatUniform("hslHueB", hue[4], hue[5], hue[6], hue[7])
        shader.setFloatUniform("hslSatA", sat[0], sat[1], sat[2], sat[3])
        shader.setFloatUniform("hslSatB", sat[4], sat[5], sat[6], sat[7])
        shader.setFloatUniform("hslLumA", lum[0], lum[1], lum[2], lum[3])
        shader.setFloatUniform("hslLumB", lum[4], lum[5], lum[6], lum[7])
        shader.setFloatUniform("skin", SKIN_HUE, SKIN_WIDTH, r.skinProtect)

        shader.setFloatUniform("localTone", r.highlightRecovery * HIGHLIGHT_RECOVERY_GAIN, r.shadowRecovery * SHADOW_RECOVERY_GAIN)
        shader.setFloatUniform("halation", r.halation * HALATION_GAIN)
        shader.setFloatUniform("halationParams", r.halationColor.r, r.halationColor.g, r.halationColor.b, r.halationThreshold)
        shader.setFloatUniform("halationRadius", HALATION_RADIUS * r.halationRadius)
        shader.setFloatUniform("bloom", r.bloom * BLOOM_GAIN)
        shader.setFloatUniform("bloomParams", r.bloomThreshold, BLOOM_RADIUS * r.bloomRadius, r.bloom)

        val lut = r.lut
        val pixels = IntArray(256) { i ->
            (0xFF shl 24) or (lut[i] shl 16) or (lut[256 + i] shl 8) or lut[512 + i]
        }
        val lutBitmap = Bitmap.createBitmap(pixels, 256, 1, Bitmap.Config.ARGB_8888)
        shader.setInputShader("lut", BitmapShader(lutBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))

        shader.setFloatUniform("bleach", r.bleachBypass)
        if (cube != null && r.lutStrength > 0f) {
            val cubeShader = BitmapShader(cube.toBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            cubeShader.filterMode = BitmapShader.FILTER_MODE_LINEAR
            shader.setInputShader("cube", cubeShader)
            shader.setFloatUniform("cubeOn", 1f)
            shader.setFloatUniform("cubeParams", cube.size.toFloat(), r.lutStrength)
        } else {
            shader.setInputShader("cube", emptyCube)
            shader.setFloatUniform("cubeOn", 0f)
            shader.setFloatUniform("cubeParams", 2f, 0f)
        }

        shader.setFloatUniform("clarity", r.clarity * CLARITY_GAIN)
        shader.setFloatUniform("clarityRadius", CLARITY_RADIUS * r.clarityRadius)

        val angle = Math.toRadians(r.lightLeakAngle.toDouble())
        shader.setFloatUniform("leak", r.lightLeak)
        shader.setFloatUniform("leakParams", cos(angle).toFloat(), sin(angle).toFloat(), r.lightLeakSize.coerceIn(0.05f, 1.5f), 0f)
        shader.setFloatUniform("leakColor", r.lightLeakColor.r, r.lightLeakColor.g, r.lightLeakColor.b)
        shader.setFloatUniform("vignette", r.vignette * VIGNETTE_DEPTH)
        shader.setFloatUniform("vignetteParams", 1f - r.vignetteFeather.coerceIn(0.05f, 1f), r.vignetteRoundness)
        shader.setFloatUniform("vignetteColor", r.vignetteColor.r, r.vignetteColor.g, r.vignetteColor.b)
        shader.setFloatUniform("dustParams", r.dust, r.scratches)

        shader.setFloatUniform("grainAmount", r.grain)
        shader.setFloatUniform("grainCellFraction", GRAIN_CELL * r.grainSize)
        shader.setFloatUniform("grainColor", r.grainColor)
        shader.setFloatUniform("grainRough", r.grainRoughness)
        shader.setFloatUniform("grainColorBias", r.grainColorBias)
        val ds = grainShadowDirection(r)
        val dm = grainMidDirection(r)
        shader.setFloatUniform("grainDirS", ds[0], ds[1], ds[2])
        shader.setFloatUniform("grainDirM", dm[0], dm[1], dm[2])
        shader.setFloatUniform("grainTone", r.grainShadows, r.grainMids, r.grainHighlights)
        shader.setFloatUniform(
            "hlDesat", r.highlightDesatStart, maxOf(r.highlightDesatStart + 0.02f, r.whitePoint), r.highlightDesatAmount.coerceIn(0f, 1f)
        )
    }

    /**
     * Effect for the current frame. [frameLeft]..[frameHeight] locate the (cropped) camera image
     * inside the preview view, so position-based effects follow the image.
     */
    fun effect(seed: Float, frameLeft: Float, frameTop: Float, frameWidth: Float, frameHeight: Float): RenderEffect {
        shader.setFloatUniform("seed", seed)
        shader.setFloatUniform("frame", frameLeft, frameTop, frameWidth, frameHeight)
        return RenderEffect.createRuntimeShaderEffect(shader, "content")
    }
}

/** Android 12 fallback for the preview: colour matrix only. */
@RequiresApi(31)
fun filmColorFilterEffect(recipe: FilmRecipe): RenderEffect =
    RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(recipe.colorMatrix))
