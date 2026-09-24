package com.tennispro.core.vision

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

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
 * **Camera shake.** A blob must also contain a few *steady* pixels: ones
 * whose change is more than a shift of [shakePx] pixels could explain — more
 * than the threshold plus [shakePx] times the local gradient. On the
 * 2026-09-23 night recordings the phone jittered by about a pixel every
 * ~0.1 s; every edge in the picture (court lines, fence, light poles, people
 * standing still) then "moved", 1,300-1,600 blobs a frame against ~500
 * otherwise, and the tracker lost the ball among them at the bounce. A shift
 * changes a pixel by at most its gradient times the shift, so edge blobs have
 * no steady pixels; the ball, a patch of new brightness, has plenty in its
 * interior. Blobs are still found and measured on the plain mask, so the
 * ball keeps its full size, which [ServeFlight]'s size checks rely on: on
 * that footage the rule cut ~1,500 blobs a frame to ~30 and kept the ball in
 * every frame, bounce included.
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
        maxPixels: Int = DEFAULT_MAX_PIXELS,
        maxDimension: Int = DEFAULT_MAX_DIMENSION,
        exclude: List<PixelRect> = emptyList(),
        shakePx: Double = DEFAULT_SHAKE_PX,
    ): List<BallCandidate> {
        require(prev.width == curr.width && curr.width == next.width && prev.height == curr.height && curr.height == next.height) {
            "Frames must be the same size to difference them"
        }
        val mask = BooleanArray(curr.pixels.size)
        for (i in mask.indices) {
            val c = curr.pixels[i]
            mask[i] = abs(c - prev.pixels[i]) > threshold && abs(next.pixels[i] - c) > threshold
        }
        val width = curr.width
        val height = curr.height
        fun steady(i: Int): Boolean {
            val x = i % width
            val y = i / width
            val c = curr.pixels
            val gx = if (x in 1 until width - 1) abs(c[i + 1] - c[i - 1]) / 2 else 0
            val gy = if (y in 1 until height - 1) abs(c[i + width] - c[i - width]) / 2 else 0
            val limit = threshold + shakePx * (gx + gy)
            return abs(c[i] - prev.pixels[i]) > limit && abs(next.pixels[i] - c[i]) > limit
        }
        val real = findBlobs(mask, width, height, ::steady)
            .filter { it.steadyCount >= min(MIN_STEADY_PIXELS, ceil(MIN_STEADY_FRACTION * it.pixelCount).toInt()) }
        return mergeTouching(real)
            .filter { it.pixelCount in minPixels..maxPixels && it.width <= maxDimension && it.height <= maxDimension }
            .map { BallCandidate(it.centroidX, it.centroidY, it.pixelCount) }
            .filterNot { candidate -> exclude.any { it.contains(candidate.x, candidate.y) } }
    }

    /** A ball seen from a fence or baseline mount, several metres or more from the camera. */
    const val DEFAULT_MAX_PIXELS = 150
    const val DEFAULT_MAX_DIMENSION = 30

    /** The camera shake, in pixels, a blob must stand out from; see the class doc. */
    const val DEFAULT_SHAKE_PX = 1.0

    /** Steady pixels a blob needs: three, or a fifth of it for a blob of a few pixels, like a distant ball. */
    private const val MIN_STEADY_PIXELS = 3
    private const val MIN_STEADY_FRACTION = 0.2

    /** Bounding boxes this close are one moving thing. */
    private const val MERGE_GAP_PX = 2

    private class Blob(var minX: Int, var minY: Int, var maxX: Int, var maxY: Int, var pixelCount: Int, var sumX: Long, var sumY: Long) {
        var steadyCount = 0
        val width get() = maxX - minX + 1
        val height get() = maxY - minY + 1
        val centroidX get() = sumX.toDouble() / pixelCount
        val centroidY get() = sumY.toDouble() / pixelCount
    }

    /**
     * Joins blobs whose bounding boxes overlap or nearly touch. A fast ball's
     * smear breaks into pieces against a busy background; on 2026-09-23 a
     * 48 px piece inside a 22 px ball's outline sat nearer the tracker's
     * prediction than the 92 px ball and took the track off it. Run after the
     * shake filter, so only real motion is joined — never the ball to the
     * shifted edge of a line beside it.
     */
    private fun mergeTouching(blobs: List<Blob>): List<Blob> {
        val merged = blobs.map { Blob(it.minX, it.minY, it.maxX, it.maxY, it.pixelCount, it.sumX, it.sumY).also { b -> b.steadyCount = it.steadyCount } }.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            outer@ for (i in merged.indices) for (j in i + 1 until merged.size) {
                val a = merged[i]
                val b = merged[j]
                if (a.minX - b.maxX > MERGE_GAP_PX || b.minX - a.maxX > MERGE_GAP_PX) continue
                if (a.minY - b.maxY > MERGE_GAP_PX || b.minY - a.maxY > MERGE_GAP_PX) continue
                a.minX = minOf(a.minX, b.minX)
                a.minY = minOf(a.minY, b.minY)
                a.maxX = maxOf(a.maxX, b.maxX)
                a.maxY = maxOf(a.maxY, b.maxY)
                a.pixelCount += b.pixelCount
                a.sumX += b.sumX
                a.sumY += b.sumY
                a.steadyCount += b.steadyCount
                merged.removeAt(j)
                changed = true
                break@outer
            }
        }
        return merged
    }

    /** 8-connected component labeling — 8, not 4, so a diagonal motion-blur streak stays one blob. */
    private fun findBlobs(mask: BooleanArray, width: Int, height: Int, steady: (Int) -> Boolean): List<Blob> {
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
                if (steady(idx)) blob.steadyCount++
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
