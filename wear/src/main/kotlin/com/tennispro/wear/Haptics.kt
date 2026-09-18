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

            // One stronger pulse than a point, so a game win is felt as a step up
            // rather than mistaken for another ordinary point.
            AlertKind.GAME_WON -> waveform(
                timings = longArrayOf(0, 120, 60, 120),
                amplitudes = intArrayOf(0, 200, 0, 200),
            )

            // Three pulses, rising amplitude — bigger than a game, still short of
            // the two-hard-pulse OUT_CALL shape so the two are never confused.
            AlertKind.SET_WON -> waveform(
                timings = longArrayOf(0, 100, 70, 100, 70, 140),
                amplitudes = intArrayOf(0, 180, 0, 210, 0, 255),
            )

            // The one celebratory pattern in the app: four pulses, ramping to full.
            AlertKind.MATCH_WON -> waveform(
                timings = longArrayOf(0, 90, 60, 90, 60, 120, 60, 200),
                amplitudes = intArrayOf(0, 150, 0, 190, 0, 220, 0, 255),
            )

            // A single, deliberately un-tick-like buzz — long enough to read as a
            // correction rather than another point being logged.
            AlertKind.UNDO -> waveform(
                timings = longArrayOf(0, 90),
                amplitudes = intArrayOf(0, 160),
            )

            // The phone is hung out of reach, so this buzz is the only proof it
            // started: one long, firm pulse, unlike any scoring pattern.
            AlertKind.RECORDING_STARTED -> waveform(
                timings = longArrayOf(0, 350),
                amplitudes = intArrayOf(0, 220),
            )

            // Two firm pulses, spaced wider than a game win's.
            AlertKind.RECORDING_STOPPED -> waveform(
                timings = longArrayOf(0, 150, 150, 150),
                amplitudes = intArrayOf(0, 220, 0, 220),
            )

            // Long and soft: something needs doing on the phone.
            AlertKind.RECORDING_REFUSED, AlertKind.MATCH_REFUSED -> waveform(
                timings = longArrayOf(0, 500),
                amplitudes = intArrayOf(0, 110),
            )

            // A rising pair: the match is under way. Deliberately unlike
            // RECORDING_STARTED's single long pulse, since both can be pressed
            // within seconds of each other and must not feel the same.
            AlertKind.MATCH_STARTED -> waveform(
                timings = longArrayOf(0, 90, 70, 200),
                amplitudes = intArrayOf(0, 150, 0, 230),
            )

            // The same shape falling, so starting and ending are mirror images.
            AlertKind.MATCH_ENDED -> waveform(
                timings = longArrayOf(0, 200, 70, 90),
                amplitudes = intArrayOf(0, 230, 0, 150),
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
