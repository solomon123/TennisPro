package com.tennispro.core.vision

import com.tennispro.core.court.CalibrationPoints
import com.tennispro.core.court.CourtDimensions
import com.tennispro.core.court.CourtFormat
import com.tennispro.core.court.Homography
import com.tennispro.core.court.PixelPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val FPS = 60.0
private const val FRAME_MS = 1000.0 / FPS

// Tolerances below are looser than a "1 px = 1 cm" fixture might suggest is
// necessary: an exact axis-aligned rectangle barely constrains the DLT's
// perspective terms (h7/h8 — see Homography.kt), so solving them lands on
// small nonzero floating-point noise instead of the true zero. That noise
// makes the local scale (and so the computed speed) vary slightly by image
// location, which these tests deliberately compare across — a fixture
// artifact (CourtTest's realistic, non-degenerate quadrilateral has no such
// issue), not a bug in ServeSpeed's own logic, which is what's under test
// here.

/** 1 pixel = 1 cm, no rotation, no perspective — an exact, easy-to-predict scale for [ServeSpeed] tests. */
private val exactCentimeterHomography: Homography = run {
    val widthPx = (CourtDimensions.SINGLES_WIDTH_M * 100).toFloat()
    val lengthPx = (CourtDimensions.LENGTH_M * 100).toFloat()
    val points = CalibrationPoints(
        format = CourtFormat.SINGLES,
        frameWidth = widthPx.toInt(),
        frameHeight = lengthPx.toInt(),
        nearLeft = PixelPoint(0f, 0f),
        nearRight = PixelPoint(widthPx, 0f),
        farLeft = PixelPoint(0f, lengthPx),
        farRight = PixelPoint(widthPx, lengthPx),
    )
    Homography.fromCalibration(points)!!
}

/** A straight-line trajectory at a constant pixel velocity, starting at contact (index 0). */
private fun uniformTrajectory(pixelsPerFrame: Double, count: Int): List<TrackedPoint> =
    (0 until count).map { i -> TrackedPoint(i, (i * FRAME_MS).toLong(), i * pixelsPerFrame, 1000.0) }

class ServeSpeedTest {

    @Test
    fun `recovers the exact speed of a uniform-velocity trajectory`() {
        // 100 px/frame at 60 fps, 1 px = 1 cm -> 100 * 0.01 m * 60 = 60 m/s.
        val points = uniformTrajectory(pixelsPerFrame = 100.0, count = 6)

        val estimate = ServeSpeed.estimate(exactCentimeterHomography, points, contactIndex = 0, bounceIndex = null, fps = FPS)

        assertEquals(60.0, estimate!!.metersPerSecond, 3.0)
        assertEquals(216.0, estimate.kmh, 10.0) // 60 m/s
    }

    @Test
    fun `a bounce close to contact shortens the baseline and widens the error band`() {
        val points = uniformTrajectory(pixelsPerFrame = 100.0, count = 6)

        val fullBaseline = ServeSpeed.estimate(exactCentimeterHomography, points, contactIndex = 0, bounceIndex = null, fps = FPS)
        val shortBaseline = ServeSpeed.estimate(exactCentimeterHomography, points, contactIndex = 0, bounceIndex = 3, fps = FPS)

        // Same true speed either way (the trajectory is uniform-velocity)...
        assertEquals(fullBaseline!!.metersPerSecond, shortBaseline!!.metersPerSecond, 5.0)
        // ...but a shorter baseline is a less certain measurement.
        assertTrue(shortBaseline.errorBandPercent > fullBaseline.errorBandPercent)
    }

    @Test
    fun `maxBaselineFrames caps the baseline even when more points are tracked`() {
        val points = uniformTrajectory(pixelsPerFrame = 100.0, count = 20)

        val capped = ServeSpeed.estimate(
            exactCentimeterHomography, points, contactIndex = 0, bounceIndex = null, fps = FPS, maxBaselineFrames = 3,
        )
        val uncapped = ServeSpeed.estimate(
            exactCentimeterHomography, points, contactIndex = 0, bounceIndex = null, fps = FPS, maxBaselineFrames = 15,
        )

        // Same true speed, but the capped baseline is shorter, so less certain.
        assertEquals(uncapped!!.metersPerSecond, capped!!.metersPerSecond, 5.0)
        assertTrue(capped.errorBandPercent > uncapped.errorBandPercent)
    }

    @Test
    fun `error band never claims better than the calibration floor`() {
        // An enormous baseline should still respect the +8 floor from calibration/motion-blur/depth-ambiguity.
        val points = uniformTrajectory(pixelsPerFrame = 100.0, count = 200)
        val estimate = ServeSpeed.estimate(
            exactCentimeterHomography, points, contactIndex = 0, bounceIndex = null, fps = FPS, maxBaselineFrames = 1000,
        )
        assertTrue(estimate!!.errorBandPercent >= 8.0)
    }

    @Test
    fun `returns null when there is no room for a baseline after contact`() {
        val points = uniformTrajectory(pixelsPerFrame = 100.0, count = 3)
        assertNull(ServeSpeed.estimate(exactCentimeterHomography, points, contactIndex = 2, bounceIndex = null, fps = FPS))
    }

    @Test
    fun `returns null for a non-positive fps`() {
        val points = uniformTrajectory(pixelsPerFrame = 100.0, count = 6)
        assertNull(ServeSpeed.estimate(exactCentimeterHomography, points, contactIndex = 0, bounceIndex = null, fps = 0.0))
    }

    @Test
    fun `returns null when bounceIndex leaves no room for a baseline`() {
        val points = uniformTrajectory(pixelsPerFrame = 100.0, count = 6)
        assertNull(ServeSpeed.estimate(exactCentimeterHomography, points, contactIndex = 0, bounceIndex = 1, fps = FPS))
    }
}
