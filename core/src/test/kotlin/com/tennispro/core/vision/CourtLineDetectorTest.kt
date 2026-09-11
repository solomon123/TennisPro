package com.tennispro.core.vision

import com.tennispro.core.court.CourtDimensions
import com.tennispro.core.court.CourtFormat
import com.tennispro.core.court.PixelPoint
import com.tennispro.core.court.solveHomography4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.imageio.ImageIO
import kotlin.math.hypot
import kotlin.random.Random

private val ALLEY = (CourtDimensions.DOUBLES_WIDTH_M - CourtDimensions.SINGLES_WIDTH_M) / 2
private val LENGTH = CourtDimensions.LENGTH_M
private val DOUBLES_W = CourtDimensions.DOUBLES_WIDTH_M

private fun assertNear(label: String, expected: PixelPoint, actual: PixelPoint, tolerancePx: Double) {
    val distance = hypot((expected.x - actual.x).toDouble(), (expected.y - actual.y).toDouble())
    assertTrue("$label: expected $expected, got $actual ($distance px off)", distance <= tolerancePx)
}

class CourtLineDetectorTest {

    /**
     * Singles corners in the same place the real baseline-mount recordings put
     * them: far corners high and narrow, the near-left corner cut off by the
     * left frame edge, so it has to be extrapolated from its two lines.
     */
    private val truthSingles = listOf(
        PixelPoint(-280f, 1088f), // near-left, outside the frame
        PixelPoint(1672f, 992f),
        PixelPoint(676f, 631f),
        PixelPoint(1066f, 615f),
    )

    private val courtToPixel: DoubleArray = solveHomography4(
        doubleArrayOf(ALLEY, 0.0, DOUBLES_W - ALLEY, 0.0, ALLEY, LENGTH, DOUBLES_W - ALLEY, LENGTH),
        truthSingles.flatMap { listOf(it.x.toDouble(), it.y.toDouble()) }.toDoubleArray(),
    )!!

    private fun project(x: Double, y: Double): Pair<Double, Double> {
        val m = courtToPixel
        val den = m[6] * x + m[7] * y + m[8]
        return (m[0] * x + m[1] * y + m[2]) / den to (m[3] * x + m[4] * y + m[5]) / den
    }

    /** A noisy blue court on green, painted 5 cm lines, plus a fence rail and a neighbouring court's sideline as distractors. */
    private fun syntheticFrame(): GrayscaleFrame {
        val width = 1920
        val height = 1080
        val random = Random(42)
        val pixels = IntArray(width * height) { i ->
            val y = i / width
            val base = if (y < 560) 150 else 115
            base + random.nextInt(-12, 13)
        }

        fun paint(x0: Double, y0: Double, x1: Double, y1: Double, halfWidthM: Double) {
            val length = hypot(x1 - x0, y1 - y0)
            val steps = (length / 0.004).toInt()
            val nx = -(y1 - y0) / length
            val ny = (x1 - x0) / length
            for (s in 0..steps) {
                val t = s.toDouble() / steps
                var o = -halfWidthM
                while (o <= halfWidthM) {
                    val (u, v) = project(x0 + (x1 - x0) * t + nx * o, y0 + (y1 - y0) * t + ny * o)
                    val iu = u.toInt()
                    val iv = v.toInt()
                    if (iu in 0 until width && iv in 0 until height) pixels[iv * width + iu] = 225 + random.nextInt(-10, 11)
                    o += 0.008
                }
            }
        }

        val half = 0.025
        val nearService = LENGTH / 2 - CourtDimensions.SERVICE_LINE_FROM_NET_M
        val farService = LENGTH / 2 + CourtDimensions.SERVICE_LINE_FROM_NET_M
        paint(0.0, 0.0, DOUBLES_W, 0.0, half)
        paint(0.0, LENGTH, DOUBLES_W, LENGTH, half)
        paint(0.0, 0.0, 0.0, LENGTH, half)
        paint(DOUBLES_W, 0.0, DOUBLES_W, LENGTH, half)
        paint(ALLEY, 0.0, ALLEY, LENGTH, half)
        paint(DOUBLES_W - ALLEY, 0.0, DOUBLES_W - ALLEY, LENGTH, half)
        paint(ALLEY, nearService, DOUBLES_W - ALLEY, nearService, half)
        paint(ALLEY, farService, DOUBLES_W - ALLEY, farService, half)
        paint(DOUBLES_W / 2, nearService, DOUBLES_W / 2, farService, half)
        // Distractors: the next court's near sideline, and a fence rail across the frame.
        paint(DOUBLES_W + 4.0, 0.0, DOUBLES_W + 4.0, LENGTH, half)
        for (x in 0 until width) for (dy in 0..2) pixels[(470 + dy) * width + x] = 210

        return GrayscaleFrame(width, height, pixels)
    }

    @Test
    fun `finds the singles corners of a synthetic court, including one outside the frame`() {
        val detection = CourtLineDetector.detect(syntheticFrame())
        assertNotNull("court not found", detection)
        val singles = detection!!.singles

        assertNear("far-left", truthSingles[2], singles.farLeft, 2.0)
        assertNear("far-right", truthSingles[3], singles.farRight, 2.0)
        assertNear("near-right", truthSingles[1], singles.nearRight, 3.0)
        // Extrapolated well past the frame edge, so a small angular error grows.
        assertNear("near-left", truthSingles[0], singles.nearLeft, 6.0)
        assertTrue("coverage ${detection.lineCoverage}", detection.lineCoverage > 0.8f)
    }

    @Test
    fun `doubles corners come out of the same fit`() {
        val detection = CourtLineDetector.detect(syntheticFrame())!!
        val (u, v) = project(DOUBLES_W, LENGTH)
        assertNear("doubles far-right", PixelPoint(u.toFloat(), v.toFloat()), detection.doubles.farRight, 2.0)

        val calibration = detection.toCalibration(CourtFormat.DOUBLES, 1920, 1080)
        assertEquals(detection.doubles.nearRight, calibration.nearRight)
        assertEquals(CourtFormat.DOUBLES, calibration.format)
    }

    @Test
    fun `returns null when there is no court in the frame`() {
        val random = Random(7)
        val noise = GrayscaleFrame(960, 540, IntArray(960 * 540) { 100 + random.nextInt(-20, 21) })
        assertNull(CourtLineDetector.detect(noise))
    }

    /**
     * A real frame from the first on-court test (Galaxy S25 Ultra, back camera,
     * 2026-09-08), cropped to 16:9 from the calibration reference frame. The
     * expected corners were read off zoomed crops of this image by hand, not
     * taken from the detector's own output.
     *
     * Only the near-right corner is held tight. From this mount's height the
     * net's top tape sits almost exactly on the far baseline, so the far
     * corners can't be read to better than several pixels even by hand — a
     * column-by-column brightness-peak measurement jumped between the tape and
     * the baseline. The synthetic court above is what pins down precision.
     */
    @Test
    fun `finds the court in a real on-court frame`() {
        val image = javaClass.getResourceAsStream("/court/real_court_2026-09-08.jpg")!!.use { ImageIO.read(it) }
        val rgb = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        val frame = GrayscaleFrame(image.width, image.height, IntArray(rgb.size) { i ->
            val p = rgb[i]
            (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        })

        val detection = CourtLineDetector.detect(frame)
        assertNotNull("court not found", detection)
        val singles = detection!!.singles
        assertNear("near-right", PixelPoint(REAL_NEAR_RIGHT_X, REAL_NEAR_RIGHT_Y), singles.nearRight, 3.0)
        assertNear("far-right", PixelPoint(REAL_FAR_RIGHT_X, REAL_FAR_RIGHT_Y), singles.farRight, 8.0)
        assertNear("far-left", PixelPoint(REAL_FAR_LEFT_X, REAL_FAR_LEFT_Y), singles.farLeft, 8.0)
    }

    /** Line-centre crossings read off 6x zoomed crops of the fixture. */
    private companion object {
        const val REAL_FAR_RIGHT_X = 892f
        const val REAL_FAR_RIGHT_Y = 455f
        const val REAL_NEAR_RIGHT_X = 1371f
        const val REAL_NEAR_RIGHT_Y = 759f
        const val REAL_FAR_LEFT_X = 595f
        const val REAL_FAR_LEFT_Y = 465f
    }
}
