package com.example.cameraapp

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Shutter "feel": a firm click as the shutter opens and a lighter tick as it closes. The
 * screen spaces the two by the shot's exposure time, so slow shutter speeds feel slow.
 */
class ShutterHaptics(context: Context) {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    /** [opening] = the firmer first click; otherwise the lighter closing tick. */
    fun tick(opening: Boolean) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(
                if (opening) VibrationEffect.EFFECT_HEAVY_CLICK else VibrationEffect.EFFECT_CLICK
            )
        } else {
            VibrationEffect.createOneShot(if (opening) 18L else 10L, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        v.vibrate(effect)
    }

    companion object {
        /** Shorter gaps than this blur into one buzz, so very fast shutters still feel like two ticks. */
        const val MIN_GAP_MS = 45L
    }
}
