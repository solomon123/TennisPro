package com.tennispro.phone.calibration

import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.tennispro.core.court.frameDifference

private const val GRID_COLS = 32
private const val GRID_ROWS = 24

/**
 * Picked conservatively, not measured — see [frameDifference]'s own "first
 * cut" framing. Tune once this has actually been watched fire (or fail to
 * fire) on court a few times.
 */
private const val DRIFT_THRESHOLD = 40

sealed interface DriftStatus {
    data object NoCalibration : DriftStatus
    data object Stable : DriftStatus
    data class PossibleDrift(val difference: Int) : DriftStatus
}

/** Bitmap-handling wrapper around the tested pure [frameDifference] function. */
class DriftDetector(private val storage: CalibrationStorage) {

    fun check(currentFrame: Bitmap): DriftStatus {
        val reference = storage.loadReferenceFrame() ?: return DriftStatus.NoCalibration
        val diff = frameDifference(sampleGrid(reference), sampleGrid(currentFrame))
        return if (diff > DRIFT_THRESHOLD) DriftStatus.PossibleDrift(diff) else DriftStatus.Stable
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
