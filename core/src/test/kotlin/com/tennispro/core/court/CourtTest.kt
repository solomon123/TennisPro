package com.tennispro.core.court

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

private const val TOLERANCE = 0.01f

private fun assertPointEquals(expected: CourtPoint, actual: CourtPoint, tolerance: Float = TOLERANCE) {
    assertEquals("x", expected.xMeters, actual.xMeters, tolerance)
    assertEquals("y", expected.yMeters, actual.yMeters, tolerance)
}

private fun assertPointEquals(expected: PixelPoint, actual: PixelPoint, tolerance: Float = 0.5f) {
    assertEquals("x", expected.x, actual.x, tolerance)
    assertEquals("y", expected.y, actual.y, tolerance)
}

class CourtTest {

    /** A plausible baseline-mount quadrilateral: far corners higher and narrower than near ones. */
    private val singles = CalibrationPoints(
        format = CourtFormat.SINGLES,
        frameWidth = 1920,
        frameHeight = 1080,
        nearLeft = PixelPoint(200f, 950f),
        nearRight = PixelPoint(1700f, 950f),
        farLeft = PixelPoint(700f, 400f),
        farRight = PixelPoint(1200f, 400f),
    )

    @Test
    fun `the four tapped corners map back to the exact court corners`() {
        val h = Homography.fromCalibration(singles)!!
        val width = CourtDimensions.SINGLES_WIDTH_M.toFloat()
        val length = CourtDimensions.LENGTH_M.toFloat()

        assertPointEquals(CourtPoint(0f, 0f), h.mapToCourt(singles.nearLeft))
        assertPointEquals(CourtPoint(width, 0f), h.mapToCourt(singles.nearRight))
        assertPointEquals(CourtPoint(0f, length), h.mapToCourt(singles.farLeft))
        assertPointEquals(CourtPoint(width, length), h.mapToCourt(singles.farRight))
    }

    @Test
    fun `doubles uses the wider court but the same length`() {
        val doubles = singles.copy(format = CourtFormat.DOUBLES)
        val h = Homography.fromCalibration(doubles)!!
        val width = CourtDimensions.DOUBLES_WIDTH_M.toFloat()

        assertPointEquals(CourtPoint(width, 0f), h.mapToCourt(doubles.nearRight))
    }

    @Test
    fun `mapToPixel is the inverse of mapToCourt for an interior point`() {
        val h = Homography.fromCalibration(singles)!!
        val interiorPixel = PixelPoint(900f, 700f)

        val court = h.mapToCourt(interiorPixel)
        val roundTripped = h.mapToPixel(court)

        assertPointEquals(interiorPixel, roundTripped)
    }

    @Test
    fun `a service line distance maps to a plausible pixel inside the frame`() {
        val h = Homography.fromCalibration(singles)!!
        val onServiceLine = CourtPoint(
            xMeters = (CourtDimensions.SINGLES_WIDTH_M / 2).toFloat(),
            yMeters = CourtDimensions.SERVICE_LINE_FROM_NET_M.toFloat(),
        )
        val pixel = h.mapToPixel(onServiceLine)

        assertEquals(true, pixel.x in 0f..singles.frameWidth.toFloat())
        assertEquals(true, pixel.y in 0f..singles.frameHeight.toFloat())
    }

    @Test
    fun `duplicate correspondences are degenerate and return null`() {
        val degenerate = singles.copy(nearRight = singles.nearLeft)
        assertNull(Homography.fromCalibration(degenerate))
    }

    @Test
    fun `four well-separated corners are not degenerate`() {
        assertNotNull(Homography.fromCalibration(singles))
    }

    @Test
    fun `widthFor returns the right dimension per format`() {
        assertEquals(CourtDimensions.SINGLES_WIDTH_M, CourtDimensions.widthFor(CourtFormat.SINGLES), 0.0)
        assertEquals(CourtDimensions.DOUBLES_WIDTH_M, CourtDimensions.widthFor(CourtFormat.DOUBLES), 0.0)
    }

    @Test
    fun `scaledTo rescales corners onto a same-shaped frame`() {
        val half = singles.scaledTo(960, 540)!!

        assertEquals(960, half.frameWidth)
        assertEquals(540, half.frameHeight)
        assertPointEquals(PixelPoint(100f, 475f), half.nearLeft)
        assertPointEquals(PixelPoint(600f, 200f), half.farRight)
    }

    @Test
    fun `scaledTo refuses a frame of a different shape`() {
        // The first field test's bug: corners saved on a letterboxed 2340x1080
        // preview snapshot, applied to 1920x1080 video frames.
        val fromPreviewSnapshot = singles.copy(frameWidth = 2340, frameHeight = 1080)
        assertNull(fromPreviewSnapshot.scaledTo(1920, 1080))
    }

    @Test
    fun `least-squares homography reproduces the exact one from consistent points`() {
        val exact = Homography.fromCalibration(singles)!!
        val pixels = ArrayList<Double>()
        val courts = ArrayList<Double>()
        for (px in listOf(300f, 800f, 1300f, 1650f)) {
            for (py in listOf(450f, 650f, 900f)) {
                val court = exact.mapToCourt(PixelPoint(px, py))
                pixels += px.toDouble(); pixels += py.toDouble()
                courts += court.xMeters.toDouble(); courts += court.yMeters.toDouble()
            }
        }

        val fitted = solveHomographyLeastSquares(pixels.toDoubleArray(), courts.toDoubleArray())!!
        val probe = PixelPoint(1000f, 700f)
        val (x, y) = applyHomogeneous(fitted, probe.x.toDouble(), probe.y.toDouble())
        assertPointEquals(exact.mapToCourt(probe), CourtPoint(x.toFloat(), y.toFloat()), tolerance = 0.001f)
    }

    @Test
    fun `calibration points round trip through the codec`() {
        val encoded = CalibrationCodec.encode(singles)
        assertEquals(singles, CalibrationCodec.decode(encoded))
    }

    @Test
    fun `codec returns null on garbage rather than throwing`() {
        assertNull(CalibrationCodec.decode("not json"))
    }

    @Test
    fun `frame difference is zero for identical grids`() {
        val grid = intArrayOf(10, 200, 128, 0, 255)
        assertEquals(0, frameDifference(grid, grid))
    }

    @Test
    fun `frame difference is the mean absolute difference`() {
        val reference = intArrayOf(0, 0, 0, 0)
        val current = intArrayOf(10, 20, 30, 40)
        assertEquals(25, frameDifference(reference, current)) // (10+20+30+40)/4
    }

    @Test
    fun `frame difference is symmetric`() {
        val a = intArrayOf(50, 60, 70)
        val b = intArrayOf(10, 200, 40)
        assertEquals(frameDifference(a, b), frameDifference(b, a))
    }

    @Test
    fun `frame difference on an empty grid is zero`() {
        assertEquals(0, frameDifference(intArrayOf(), intArrayOf()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `frame difference rejects mismatched grid sizes`() {
        frameDifference(intArrayOf(1, 2, 3), intArrayOf(1, 2))
    }
}
