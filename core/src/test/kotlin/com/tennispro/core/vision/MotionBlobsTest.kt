package com.tennispro.core.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionBlobsTest {

    /** A dark frame with bright square spots of half-size [radius] centred at each point. */
    private fun frame(vararg spots: Pair<Int, Int>, radius: Int = 2, width: Int = 80, height: Int = 60): GrayscaleFrame {
        val pixels = IntArray(width * height) { 50 }
        for ((cx, cy) in spots) {
            for (y in cy - radius..cy + radius) for (x in cx - radius..cx + radius) {
                if (x in 0 until width && y in 0 until height) pixels[y * width + x] = 220
            }
        }
        return GrayscaleFrame(width, height, pixels)
    }

    @Test
    fun `a moving spot is found only where it is in the middle frame, not where it was or will be`() {
        val found = MotionBlobs.find(frame(10 to 20), frame(30 to 20), frame(50 to 20))

        assertEquals(1, found.size)
        assertEquals(30.0, found[0].x, 0.01)
        assertEquals(20.0, found[0].y, 0.01)
    }

    @Test
    fun `a static scene produces no candidates`() {
        val still = frame(40 to 30)
        assertTrue(MotionBlobs.find(still, still, still).isEmpty())
    }

    @Test
    fun `a spot that stays put for two of the three frames is not moving`() {
        // Differs from the previous frame but not the next: a change, not motion through this frame.
        val found = MotionBlobs.find(frame(10 to 20), frame(30 to 20), frame(30 to 20))
        assertTrue(found.isEmpty())
    }

    @Test
    fun `candidates inside the exclusion rectangle are dropped`() {
        val prev = frame()
        val curr = frame(20 to 20, 60 to 20)
        val next = frame()
        val found = MotionBlobs.find(prev, curr, next, exclude = PixelRect(50.0, 0.0, 79.0, 59.0))

        assertEquals(1, found.size)
        assertEquals(20.0, found[0].x, 0.01)
    }

    @Test
    fun `a blob wider than maxDimension is rejected`() {
        val found = MotionBlobs.find(frame(), frame(40 to 30, radius = 12), frame(), maxDimension = 20, maxPixels = 10_000)
        assertTrue(found.isEmpty())
    }

    @Test
    fun `a diagonal streak stays one blob under 8-connectivity`() {
        val width = 80
        val height = 60
        val streak = IntArray(width * height) { 50 }
        for (i in 0 until 10) streak[(10 + i) * width + (10 + i)] = 220
        val found = MotionBlobs.find(frame(), GrayscaleFrame(width, height, streak), frame())

        assertEquals(1, found.size)
        assertEquals(10, found[0].pixelCount)
    }
}
