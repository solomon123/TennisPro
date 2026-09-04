package com.tennispro.core.vision

import com.tennispro.core.court.CourtPoint
import com.tennispro.core.court.Homography
import com.tennispro.core.court.PixelPoint
import kotlin.math.hypot

/** A speed estimate that always ships with its own uncertainty — see docs/ACCURACY.md. */
data class SpeedEstimate(val metersPerSecond: Double, val errorBandPercent: Double) {
    val kmh: Double get() = metersPerSecond * 3.6
}

/**
 * Turns a tracked pixel trajectory into a speed estimate.
 *
 * Deliberately approximate, and honest about exactly where: real-world
 * distance is estimated from the *local scale* of the Phase 2 ground-plane
 * homography (its Jacobian at the relevant image region) rather than a full
 * 3D camera model. The ball is airborne, not on the ground plane the
 * homography is built from — docs/ACCURACY.md calls this out directly
 * ("any out-of-plane motion... leaks straight into the speed estimate"),
 * and [errorBandPercent] is computed, not a flat guess, so it reflects that.
 */
object ServeSpeed {

    /**
     * @param points the smoothed trajectory, in pixel space, covering at least contact through the bounce.
     * @param contactIndex index into [points] of the contact frame (see [Trajectory.findContactIndex]).
     * @param bounceIndex index into [points] of the bounce frame, if known (see [Trajectory.findBounceIndex]) —
     *   caps how far past contact the speed baseline reaches, since the ball is airborne only until then.
     * @param fps the recording's actual frame rate — the dominant term in [errorBandPercent].
     * @param maxBaselineFrames caps the baseline even when the ball is tracked well past it, since a longer
     *   flight segment risks air-resistance deceleration biasing the "instantaneous" launch speed low.
     */
    fun estimate(
        homography: Homography,
        points: List<TrackedPoint>,
        contactIndex: Int,
        bounceIndex: Int?,
        fps: Double,
        maxBaselineFrames: Int = 10,
    ): SpeedEstimate? {
        if (fps <= 0.0 || contactIndex < 0 || contactIndex >= points.size - 1) return null

        val startIndex = contactIndex + 1
        val endLimit = (bounceIndex ?: points.size - 1).coerceAtMost(points.size - 1)
        val endIndex = minOf(startIndex + maxBaselineFrames, endLimit)
        if (endIndex <= startIndex) return null

        val a = points[startIndex]
        val b = points[endIndex]
        val dtSeconds = (b.timeMs - a.timeMs) / 1000.0
        if (dtSeconds <= 0.0) return null

        val frameIntervalsInBaseline = endIndex - startIndex

        val midX = (a.x + b.x) / 2.0
        val midY = (a.y + b.y) / 2.0
        val metersPerPixel = localScale(homography, midX, midY)

        val pixelDistance = hypot(b.x - a.x, b.y - a.y)
        val metersDistance = pixelDistance * metersPerPixel
        val speed = metersDistance / dtSeconds
        if (speed <= 0.0) return null

        // Frame-gap uncertainty is roughly one frame interval's worth of position
        // ambiguity at each end of the baseline (docs/ACCURACY.md's own worked
        // example: distance-per-frame at a given speed and fps) — as a fraction of
        // a baseline spanning `frameIntervalsInBaseline` gaps, that is simply its
        // reciprocal. A longer baseline (more frames, or a higher fps covering the
        // same real time) shrinks this term; it never fully disappears, on top of
        // the calibration/motion-blur/depth-ambiguity floor ACCURACY.md documents
        // as ±8-15% even before frame-gap is counted.
        val frameGapErrorPercent = 100.0 / frameIntervalsInBaseline
        val errorBandPercent = frameGapErrorPercent + 8.0

        return SpeedEstimate(speed, errorBandPercent)
    }

    /**
     * Meters per pixel at image point ([x], [y]), via a finite-difference
     * Jacobian of [Homography.mapToCourt] — not a direct `mapToCourt` of the
     * (airborne, off-plane) ball position itself, which would place it at a
     * nonsensical absolute court coordinate. Averaged over both image axes
     * since the projective scale is not isotropic.
     */
    private fun localScale(homography: Homography, x: Double, y: Double, epsilonPx: Double = 1.0): Double {
        val center = homography.mapToCourt(PixelPoint(x.toFloat(), y.toFloat()))
        val dx = homography.mapToCourt(PixelPoint((x + epsilonPx).toFloat(), y.toFloat()))
        val dy = homography.mapToCourt(PixelPoint(x.toFloat(), (y + epsilonPx).toFloat()))

        val scaleX = courtDistance(center, dx) / epsilonPx
        val scaleY = courtDistance(center, dy) / epsilonPx
        return (scaleX + scaleY) / 2.0
    }

    private fun courtDistance(a: CourtPoint, b: CourtPoint): Double =
        hypot((b.xMeters - a.xMeters).toDouble(), (b.yMeters - a.yMeters).toDouble())
}
