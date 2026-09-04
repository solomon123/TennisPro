package com.tennispro.core.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val WIDTH = 40
private const val HEIGHT = 30
private const val BACKGROUND = 50
private const val BRIGHT = 220

private fun uniformFrame(value: Int = BACKGROUND): GrayscaleFrame =
    GrayscaleFrame(WIDTH, HEIGHT, IntArray(WIDTH * HEIGHT) { value })

/** Paints a filled square of [value], [size] px per side, centered at ([cx], [cy]), onto a copy of [frame]. */
private fun withSpot(frame: GrayscaleFrame, cx: Int, cy: Int, size: Int = 3, value: Int = BRIGHT): GrayscaleFrame {
    val pixels = frame.pixels.copyOf()
    val half = size / 2
    for (y in (cy - half)..(cy + half)) {
        for (x in (cx - half)..(cx + half)) {
            if (x in 0 until frame.width && y in 0 until frame.height) {
                pixels[y * frame.width + x] = value
            }
        }
    }
    return GrayscaleFrame(frame.width, frame.height, pixels)
}

class BallDetectorTest {

    @Test
    fun `a spot that appears between frames is detected near its true position`() {
        val prev = uniformFrame()
        val curr = withSpot(prev, cx = 20, cy = 15)

        val candidates = BallDetector.detect(prev, curr)

        assertEquals(1, candidates.size)
        assertEquals(20.0, candidates[0].x, 0.6)
        assertEquals(15.0, candidates[0].y, 0.6)
    }

    @Test
    fun `identical frames produce no candidates`() {
        val frame = withSpot(uniformFrame(), cx = 10, cy = 10)
        assertTrue(BallDetector.detect(frame, frame).isEmpty())
    }

    @Test
    fun `a spot moving between frames is detected at both its old and new position`() {
        val prev = withSpot(uniformFrame(), cx = 10, cy = 10)
        val curr = withSpot(uniformFrame(), cx = 18, cy = 10) // old spot vanished, new one appeared

        val candidates = BallDetector.detect(prev, curr)

        assertEquals(2, candidates.size)
        val xs = candidates.map { it.x }.sorted()
        assertEquals(10.0, xs[0], 0.6)
        assertEquals(18.0, xs[1], 0.6)
    }

    @Test
    fun `a blob larger than maxDimension is rejected`() {
        val prev = uniformFrame()
        val curr = withSpot(prev, cx = 20, cy = 15, size = 25) // bigger than the default maxDimension of a real ball

        val candidates = BallDetector.detect(prev, curr, maxDimension = 10)

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `a whole-frame lighting change is rejected as too large`() {
        val prev = uniformFrame(50)
        val curr = uniformFrame(120) // every pixel changed — a lighting shift, not a ball

        val candidates = BallDetector.detect(prev, curr, maxPixels = 600)

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `a diagonal streak stays one blob under 8-connectivity`() {
        val prev = uniformFrame()
        val pixels = prev.pixels.copyOf()
        // A thin diagonal line of touching-only-at-corners pixels — 4-connectivity
        // would split this into several single-pixel blobs; 8-connectivity, which
        // motion-blur streaks need, keeps it as one.
        for (i in 0..5) {
            pixels[(10 + i) * WIDTH + (10 + i)] = BRIGHT
        }
        val curr = GrayscaleFrame(WIDTH, HEIGHT, pixels)

        val candidates = BallDetector.detect(prev, curr, minPixels = 1, maxDimension = 20)

        assertEquals(1, candidates.size)
        assertEquals(6, candidates[0].pixelCount)
    }

    @Test
    fun `threshold controls sensitivity to small changes`() {
        val prev = uniformFrame(50)
        val curr = withSpot(prev, cx = 20, cy = 15, value = 60) // a small, subtle change

        assertTrue("a high threshold should ignore a small change", BallDetector.detect(prev, curr, threshold = 30).isEmpty())
        assertTrue(
            "a low threshold should catch the same change",
            BallDetector.detect(prev, curr, threshold = 5).isNotEmpty(),
        )
    }
}
