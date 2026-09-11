package com.tennispro.core.court

import kotlin.math.abs

/**
 * Mean absolute difference between two equal-length grayscale sample grids
 * (each value 0..255), as a stand-in for "has the camera moved since it was
 * calibrated."
 *
 * This is deliberately not real line re-detection — that is classical-CV work
 * that belongs alongside Phase 3/4's ball-detection pipeline (see
 * docs/ACCURACY.md), which this project has no dependency for yet. A raw
 * pixel-difference check is a first cut, the same way `wear/Haptics.kt`'s
 * waveforms are a first cut: it will false-positive on a change of light and
 * miss a small nudge, but it catches the case that actually matters — the
 * mount got bumped and the frame looks nothing like the calibration reference.
 *
 * Callers downsample both a stored reference frame and a fresh capture to the
 * same small fixed grid (grayscale, not full resolution) before calling this.
 */
fun frameDifference(reference: IntArray, current: IntArray): Int {
    require(reference.size == current.size) {
        "Sample grids must be the same size (${reference.size} vs ${current.size})"
    }
    if (reference.isEmpty()) return 0

    var sum = 0L
    for (i in reference.indices) sum += abs(reference[i] - current[i])
    return (sum / reference.size).toInt()
}

/**
 * The real drift check, once a court can be re-detected: the largest distance,
 * in pixels, between each saved calibration corner and the same corner found
 * again in a fresh frame of the same size. Only corners inside the frame count
 * — an off-frame corner is extrapolated, and moves a lot with tiny line-fit
 * differences. Null if no corner is inside the frame.
 */
fun cornerShift(saved: List<PixelPoint>, detected: List<PixelPoint>, frameWidth: Int, frameHeight: Int): Float? {
    require(saved.size == detected.size) { "Corner lists must match (${saved.size} vs ${detected.size})" }
    var largest: Float? = null
    for (i in saved.indices) {
        val s = saved[i]
        if (s.x < 0 || s.y < 0 || s.x > frameWidth || s.y > frameHeight) continue
        val d = kotlin.math.hypot(s.x - detected[i].x, s.y - detected[i].y)
        largest = maxOf(largest ?: 0f, d)
    }
    return largest
}
