package com.tennispro.phone.calibration

import android.graphics.Bitmap
import android.util.Log
import androidx.core.graphics.scale
import com.tennispro.core.court.cornerShift
import com.tennispro.core.court.frameDifference
import com.tennispro.core.vision.CourtLineDetector
import com.tennispro.phone.vision.toGrayscaleFrame
import kotlin.math.roundToInt

private const val GRID_COLS = 32
private const val GRID_ROWS = 24

/**
 * Picked conservatively, not measured — see [frameDifference]'s own "first
 * cut" framing. Only used when the court can't be re-detected.
 */
private const val DRIFT_THRESHOLD = 40

/**
 * A saved corner more than this far, as a fraction of frame width, from where
 * the court is found now: ~15 px on 1920-wide video. Well above detection's
 * own frame-to-frame jitter (a few px on the 2026-09-08 footage), well below
 * the 20-40 px the phone moved when bumped mid-recording.
 */
private const val CORNER_SHIFT_FRACTION = 0.008f

sealed interface DriftStatus {
    data object NoCalibration : DriftStatus
    data object Stable : DriftStatus

    /** [difference] is a corner shift in pixels, or a mean pixel difference when the court couldn't be found. */
    data class PossibleDrift(val difference: Int) : DriftStatus
}

/**
 * "Has the camera moved since calibrating?" Re-detects the court in the live
 * frame and compares corners with the saved calibration — a real answer,
 * robust to lighting. Falls back to the original rough pixel-difference check
 * against the reference frame when the court can't be found (nobody on court
 * yet is fine; a dark evening or a covered lens isn't).
 *
 * Takes ~a second on the phone: call off the main thread.
 */
class DriftDetector(private val storage: CalibrationStorage) {

    fun check(currentFrame: Bitmap): DriftStatus {
        val saved = storage.load() ?: return DriftStatus.NoCalibration

        val scaled = saved.scaledTo(currentFrame.width, currentFrame.height)
        val detection = scaled?.let {
            runCatching { CourtLineDetector.detect(currentFrame.toGrayscaleFrame()) }
                .onFailure { e -> Log.w(TAG, "Court re-detection failed", e) }
                .getOrNull()
        }
        if (scaled != null && detection != null) {
            val corners = detection.cornersFor(scaled.format)
            val shift = cornerShift(
                saved = listOf(scaled.nearLeft, scaled.nearRight, scaled.farLeft, scaled.farRight),
                detected = listOf(corners.nearLeft, corners.nearRight, corners.farLeft, corners.farRight),
                frameWidth = currentFrame.width,
                frameHeight = currentFrame.height,
            )
            if (shift != null) {
                Log.i(TAG, "Corner shift since calibration: %.1f px".format(shift))
                return if (shift > CORNER_SHIFT_FRACTION * currentFrame.width) DriftStatus.PossibleDrift(shift.roundToInt()) else DriftStatus.Stable
            }
        }

        val reference = storage.loadReferenceFrame() ?: return DriftStatus.NoCalibration
        val diff = frameDifference(sampleGrid(reference), sampleGrid(currentFrame))
        return if (diff > DRIFT_THRESHOLD) DriftStatus.PossibleDrift(diff) else DriftStatus.Stable
    }

    private companion object {
        const val TAG = "DriftDetector"
    }
}

/** Downscales to a small fixed grid and converts to grayscale luma, 0..255 per sample. */
private fun sampleGrid(bitmap: Bitmap): IntArray {
    val scaled = bitmap.scale(GRID_COLS, GRID_ROWS)
    val pixels = IntArray(GRID_COLS * GRID_ROWS)
    scaled.getPixels(pixels, 0, GRID_COLS, 0, 0, GRID_COLS, GRID_ROWS)
    if (scaled !== bitmap) scaled.recycle()

    return IntArray(pixels.size) { i ->
        val p = pixels[i]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        (r * 299 + g * 587 + b * 114) / 1000 // standard luma weighting
    }
}
