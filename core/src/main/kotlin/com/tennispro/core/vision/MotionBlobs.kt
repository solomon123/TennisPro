package com.tennispro.core.vision

import kotlin.math.abs

/** A single grayscale video frame, 0..255 per pixel, row-major. */
data class GrayscaleFrame(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(pixels.size == width * height) {
            "pixels.size (${pixels.size}) must equal width*height (${width * height})"
        }
    }

    // IntArray in a data class needs a manual equals/hashCode — the default only compares
    // array references — but nothing here relies on GrayscaleFrame equality, so this is
    // purely to satisfy `data class` correctness, not a functional requirement.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GrayscaleFrame) return false
        return width == other.width && height == other.height && pixels.contentEquals(other.pixels)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + pixels.contentHashCode()
        return result
    }
}

/** One plausible ball position in a single frame, before any tracking across frames. */
data class BallCandidate(val x: Double, val y: Double, val pixelCount: Int)

/** An axis-aligned pixel rectangle, edges inclusive. */
data class PixelRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    fun contains(x: Double, y: Double): Boolean = x in left..right && y in top..bottom
}

/**
 * Classical-CV ball candidates: three-frame differencing, not a trained model
 * (see docs/ACCURACY.md's "classical CV first" plan).
 *
 * A pixel counts as moving only if it differs from *both* the previous and the
 * next frame. The first cut (Phase 3's two-frame `BallDetector`) differenced
 * consecutive frames only, which marks the ball twice — where it was and where
 * it is — and on the first real-court footage (2026-09-08) produced 100-480
 * blobs a frame, mostly the server's own body. Requiring both differences
 * leaves just the ball's current position, and [exclude] lets the caller mask
 * the server's body out entirely, since pose already knows where it is.
 *
 * Every plausible blob is returned; which one is the ball is decided across
 * frames by [ServeFlight], not here.
 */
object MotionBlobs {

    fun find(
        prev: GrayscaleFrame,
        curr: GrayscaleFrame,
        next: GrayscaleFrame,
        threshold: Int = 20,
        minPixels: Int = 3,
        maxPixels: Int = 150,
        maxDimension: Int = 30,
        exclude: List<PixelRect> = emptyList(),
    ): List<BallCandidate> {
        require(prev.width == curr.width && curr.width == next.width && prev.height == curr.height && curr.height == next.height) {
            "Frames must be the same size to difference them"
        }
        val mask = BooleanArray(curr.pixels.size)
        for (i in mask.indices) {
            val c = curr.pixels[i]
            mask[i] = abs(c - prev.pixels[i]) > threshold && abs(next.pixels[i] - c) > threshold
        }
        return findBlobs(mask, curr.width, curr.height)
            .filter { it.pixelCount in minPixels..maxPixels && it.width <= maxDimension && it.height <= maxDimension }
            .map { BallCandidate(it.centroidX, it.centroidY, it.pixelCount) }
            .filterNot { candidate -> exclude.any { it.contains(candidate.x, candidate.y) } }
    }

    private class Blob(var minX: Int, var minY: Int, var maxX: Int, var maxY: Int, var pixelCount: Int, var sumX: Long, var sumY: Long) {
        val width get() = maxX - minX + 1
        val height get() = maxY - minY + 1
        val centroidX get() = sumX.toDouble() / pixelCount
        val centroidY get() = sumY.toDouble() / pixelCount
    }

    /** 8-connected component labeling — 8, not 4, so a diagonal motion-blur streak stays one blob. */
    private fun findBlobs(mask: BooleanArray, width: Int, height: Int): List<Blob> {
        val visited = BooleanArray(mask.size)
        val blobs = mutableListOf<Blob>()
        val stack = ArrayDeque<Int>()

        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue

            visited[start] = true
            stack.addLast(start)
            val startX = start % width
            val startY = start / width
            val blob = Blob(minX = startX, minY = startY, maxX = startX, maxY = startY, pixelCount = 0, sumX = 0L, sumY = 0L)

            while (stack.isNotEmpty()) {
                val idx = stack.removeLast()
                val x = idx % width
                val y = idx / width

                blob.pixelCount++
                blob.sumX += x
                blob.sumY += y
                if (x < blob.minX) blob.minX = x
                if (x > blob.maxX) blob.maxX = x
                if (y < blob.minY) blob.minY = y
                if (y > blob.maxY) blob.maxY = y

                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue
                        val nIdx = ny * width + nx
                        if (mask[nIdx] && !visited[nIdx]) {
                            visited[nIdx] = true
                            stack.addLast(nIdx)
                        }
                    }
                }
            }
            blobs.add(blob)
        }
        return blobs
    }
}
