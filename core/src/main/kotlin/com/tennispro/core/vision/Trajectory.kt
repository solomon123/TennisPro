package com.tennispro.core.vision

import kotlin.math.hypot

/** A smoothed ball position at one sampled frame, in pixel space. */
data class TrackedPoint(val frameIndex: Int, val timeMs: Long, val x: Double, val y: Double)

/**
 * Finds the two moments a serve-speed measurement needs out of a tracked
 * trajectory: contact (the swing launches the ball) and bounce (it lands).
 */
object Trajectory {

    /**
     * The index into [points] with the sharpest frame-to-frame speed
     * increase — the ball going from near-stationary (the toss, at its
     * apex) to rapid acceleration is unambiguous in a tracked trajectory,
     * which is why this is the primary contact signal rather than trusting
     * pose-estimation timing alone (see docs/ARCHITECTURE.md's Serve speed
     * section for why). Null if there aren't enough points to compare.
     */
    fun findContactIndex(points: List<TrackedPoint>): Int? {
        if (points.size < 3) return null

        val speeds = DoubleArray(points.size - 1)
        for (i in 0 until points.size - 1) {
            val dtMs = (points[i + 1].timeMs - points[i].timeMs).coerceAtLeast(1)
            speeds[i] = hypot(points[i + 1].x - points[i].x, points[i + 1].y - points[i].y) / dtMs
        }

        var bestIndex = -1
        var bestJump = 0.0
        for (i in 0 until speeds.size - 1) {
            val jump = speeds[i + 1] - speeds[i]
            if (jump > bestJump) {
                bestJump = jump
                bestIndex = i + 1 // the point where the faster segment begins
            }
        }
        return bestIndex.takeIf { it >= 0 }
    }

    /**
     * The index of the trajectory vertex from [searchFrom] onward — the
     * frame where image-space vertical motion reverses (y stops increasing
     * and starts decreasing; image y grows downward, so this is where the
     * ball is at its lowest on screen). This is exactly the bounce-finding
     * step docs/ACCURACY.md describes for Phase 4's line calling too, so
     * it's shared groundwork, not a one-off. Null unless the reversal is an
     * actual interior vertex — a monotonically increasing tail (still
     * descending when tracking ends) is not a bounce we've observed yet.
     */
    fun findBounceIndex(points: List<TrackedPoint>, searchFrom: Int = 0): Int? {
        if (searchFrom < 0 || searchFrom >= points.size - 1) return null

        var peakIndex = searchFrom
        for (i in searchFrom + 1 until points.size) {
            if (points[i].y > points[peakIndex].y) peakIndex = i
        }
        val isInterior = peakIndex > searchFrom && peakIndex < points.size - 1
        return peakIndex.takeIf { isInterior }
    }
}
