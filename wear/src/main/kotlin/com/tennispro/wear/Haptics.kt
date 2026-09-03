package com.tennispro.wear

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.tennispro.core.protocol.AlertKind

/**
 * Wrist haptics.
 *
 * Patterns are chosen to be distinguishable *without looking*, because that is the
 * whole point: mid-point you feel the buzz, you do not read the watch. An out-call
 * is therefore two long, hard pulses — nothing else in the app uses that shape, and
 * it does not read like an incoming notification from another app.
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    fun play(kind: AlertKind) {
        val vibrator = vibrator?.takeIf { it.hasVibrator() } ?: return
        vibrator.cancel()

        val effect = when (kind) {
            // Unmistakable: two long pulses at full amplitude.
            AlertKind.OUT_CALL -> waveform(
                timings = longArrayOf(0, 260, 130, 260),
                amplitudes = intArrayOf(0, 255, 0, 255),
            )

            // Three quick taps, clearly not an out-call.
            AlertKind.LET -> waveform(
                timings = longArrayOf(0, 70, 80, 70, 80, 70),
                amplitudes = intArrayOf(0, 180, 0, 180, 0, 180),
            )

            AlertKind.TEST -> waveform(
                timings = longArrayOf(0, 200),
                amplitudes = intArrayOf(0, 200),
            )

            // Acknowledgements are deliberately faint — they confirm without nagging.
            AlertKind.POINT_LOGGED, AlertKind.BOOKMARK_SAVED -> waveform(
                timings = longArrayOf(0, 45),
                amplitudes = intArrayOf(0, 120),
            )
        }

        vibrator.vibrate(effect)
    }

    /** Local feedback for the user's own tap, so input feels acknowledged instantly. */
    fun tick() {
        val vibrator = vibrator?.takeIf { it.hasVibrator() } ?: return
        vibrator.vibrate(waveform(longArrayOf(0, 30), intArrayOf(0, 100)))
    }

    private fun waveform(timings: LongArray, amplitudes: IntArray): VibrationEffect =
        if (vibrator?.hasAmplitudeControl() == true) {
            VibrationEffect.createWaveform(timings, amplitudes, NO_REPEAT)
        } else {
            // Without amplitude control the array is ignored anyway; fall back to the
            // on/off timing pattern so the *rhythm* still distinguishes the alerts.
            VibrationEffect.createWaveform(timings, NO_REPEAT)
        }

    private companion object {
        const val NO_REPEAT = -1
    }
}
