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

/** One plausible ball position in a single frame, before any temporal tracking. */
data class BallCandidate(val x: Double, val y: Double, val pixelCount: Int)

/**
 * Classical-CV ball detection: frame differencing, not a trained model.
 *
 * Per docs/ACCURACY.md: the camera is fixed and the background static, which
 * background/frame differencing exploits directly — no training data needed,
 * an order of magnitude cheaper than a network. A TFLite heatmap model is the
 * documented fallback if this proves insufficient, not the starting point.
 *
 * A tennis ball at range is small (ACCURACY.md: "6-15 px") and, moving fast,
 * motion-blurs into a streak rather than staying compact — [maxDimension]
 * exists specifically to still accept an elongated blob, not just a round one.
 */
object BallDetector {

    /**
     * Candidate ball positions in [curr], found by differencing it against
     * [prev]. Returns every plausible blob, not just one — [threshold],
     * [minPixels]/[maxPixels], and [maxDimension] are the only filtering;
     * picking the single most likely candidate per frame is a temporal
     * decision (informed by where a tracker expects the ball to be), made by
     * the caller, not here.
     */
    fun detect(
        prev: GrayscaleFrame,
        curr: GrayscaleFrame,
        threshold: Int = 25,
        minPixels: Int = 3,
        maxPixels: Int = 600,
        maxDimension: Int = 60,
    ): List<BallCandidate> {
        require(prev.width == curr.width && prev.height == curr.height) {
            "Frames must be the same size to difference them"
        }
        val mask = differenceMask(prev, curr, threshold)
        return findBlobs(mask, curr.width, curr.height)
            .filter { it.pixelCount in minPixels..maxPixels && it.width <= maxDimension && it.height <= maxDimension }
            .map { BallCandidate(it.centroidX, it.centroidY, it.pixelCount) }
    }

    private fun differenceMask(prev: GrayscaleFrame, curr: GrayscaleFrame, threshold: Int): BooleanArray {
        val mask = BooleanArray(curr.pixels.size)
        for (i in mask.indices) {
            mask[i] = abs(prev.pixels[i] - curr.pixels[i]) >= threshold
        }
        return mask
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
