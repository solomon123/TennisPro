package com.tennispro.core.court

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/** ITF court format. Doubles widens the court; the baseline-to-baseline length is unchanged. */
@Serializable
enum class CourtFormat { SINGLES, DOUBLES }

/** Real-world ITF court dimensions, in meters. */
object CourtDimensions {
    const val LENGTH_M = 23.77
    const val SINGLES_WIDTH_M = 8.23
    const val DOUBLES_WIDTH_M = 10.97
    const val SERVICE_LINE_FROM_NET_M = 6.40

    fun widthFor(format: CourtFormat): Double =
        if (format == CourtFormat.SINGLES) SINGLES_WIDTH_M else DOUBLES_WIDTH_M
}

/** A pixel location in a video frame's own coordinate space. */
@Serializable
data class PixelPoint(val x: Float, val y: Float)

/** A location on the court, in meters from the near-left baseline corner (see [CalibrationPoints]). */
@Serializable
data class CourtPoint(val xMeters: Float, val yMeters: Float)

/**
 * The four tapped court corners a calibration is built from.
 *
 * Fixed tap order — near-left, near-right, far-left, far-right — where "near"
 * is the baseline closest to the camera. This project is configured for a
 * baseline mount (see the README's Setup section), so all four corners are
 * visible in one frame. Court coordinates are anchored at the near-left
 * corner: +x runs along the baseline toward near-right, +y runs the length of
 * the court toward the far end.
 *
 * This — not the derived [Homography] — is what gets persisted and sent
 * between layers. The homography is always recomputed from these four points
 * on demand via [Homography.fromCalibration], the same "store the log, derive
 * the projection" split used for [com.tennispro.core.scoring.MatchState].
 */
@Serializable
data class CalibrationPoints(
    val format: CourtFormat,
    val frameWidth: Int,
    val frameHeight: Int,
    val nearLeft: PixelPoint,
    val nearRight: PixelPoint,
    val farLeft: PixelPoint,
    val farRight: PixelPoint,
) {
    /**
     * These same corners expressed in a [width]x[height] frame showing the
     * same view — e.g. a calibration made on a 1920x1080 frame, applied to
     * frames a decoder hands back at another size. Null when the aspect ratio
     * differs: two frames of different shape can't be the same view (one is a
     * crop of the other), so a plain rescale would put every corner in the
     * wrong place rather than fail loudly.
     */
    fun scaledTo(width: Int, height: Int): CalibrationPoints? {
        if (width == frameWidth && height == frameHeight) return this
        val sourceAspect = frameWidth.toDouble() / frameHeight
        val targetAspect = width.toDouble() / height
        if (abs(sourceAspect - targetAspect) / targetAspect > ASPECT_RATIO_TOLERANCE) return null

        val sx = width.toFloat() / frameWidth
        val sy = height.toFloat() / frameHeight
        fun PixelPoint.scaled() = PixelPoint(x * sx, y * sy)
        return copy(
            frameWidth = width,
            frameHeight = height,
            nearLeft = nearLeft.scaled(),
            nearRight = nearRight.scaled(),
            farLeft = farLeft.scaled(),
            farRight = farRight.scaled(),
        )
    }
}

/** Covers encoder rounding (e.g. 1920x1088 macroblock-aligned buffers), not a genuinely different framing. */
private const val ASPECT_RATIO_TOLERANCE = 0.01

/**
 * A 3x3 projective transform between a video frame's pixel space and the
 * court's real-world plane, in meters.
 *
 * Built from exactly four point correspondences via a standard Direct Linear
 * Transform, solved as an 8-equation/8-unknown linear system with the
 * homogeneous scale fixed at `h9 = 1`. That fix is safe here specifically
 * because a phone camera photographing a physical, finite court plane can
 * never send a real point to infinity — the one case where `h9 = 1` would be
 * wrong. It would not be a safe assumption for an arbitrary point set, which
 * is why this class only ever takes exactly four correspondences rather than
 * a general least-squares fit over more.
 *
 * At the ground plane — where a served ball bounces — this mapping is exact
 * (see docs/ACCURACY.md); everywhere else in the image it is not, because a
 * single homography only holds for one plane.
 */
class Homography private constructor(
    private val forward: DoubleArray, // pixel -> court, row-major 3x3
    private val backward: DoubleArray, // court -> pixel, row-major 3x3
) {
    fun mapToCourt(p: PixelPoint): CourtPoint {
        val (x, y) = applyHomogeneous(forward, p.x.toDouble(), p.y.toDouble())
        return CourtPoint(x.toFloat(), y.toFloat())
    }

    fun mapToPixel(p: CourtPoint): PixelPoint {
        val (x, y) = applyHomogeneous(backward, p.xMeters.toDouble(), p.yMeters.toDouble())
        return PixelPoint(x.toFloat(), y.toFloat())
    }

    companion object {
        /**
         * Returns null on a degenerate tap configuration — points too close
         * together or nearly collinear — rather than producing a homography
         * that would silently distort every measurement downstream.
         */
        fun fromCalibration(points: CalibrationPoints): Homography? {
            val width = CourtDimensions.widthFor(points.format)
            val length = CourtDimensions.LENGTH_M
            val correspondences = listOf(
                points.nearLeft to CourtPoint(0f, 0f),
                points.nearRight to CourtPoint(width.toFloat(), 0f),
                points.farLeft to CourtPoint(0f, length.toFloat()),
                points.farRight to CourtPoint(width.toFloat(), length.toFloat()),
            )
            val forward = solveDlt(correspondences) ?: return null
            val backward = invert3x3(forward) ?: return null
            return Homography(forward, backward)
        }
    }
}

/** Applies a row-major 3x3 homogeneous transform to (x, y) and de-homogenizes. */
internal fun applyHomogeneous(m: DoubleArray, x: Double, y: Double): Pair<Double, Double> {
    val denom = m[6] * x + m[7] * y + m[8]
    val outX = (m[0] * x + m[1] * y + m[2]) / denom
    val outY = (m[3] * x + m[4] * y + m[5]) / denom
    return outX to outY
}

/**
 * Solves the 8x8 DLT system for `h1..h8` (with `h9` fixed at 1) built from
 * four pixel -> court correspondences, returning the full row-major 3x3
 * matrix `[h1..h9]`, or null if the system is singular.
 */
private fun solveDlt(correspondences: List<Pair<PixelPoint, CourtPoint>>): DoubleArray? {
    require(correspondences.size == 4) { "Homography needs exactly 4 correspondences, got ${correspondences.size}" }

    val src = DoubleArray(8)
    val dst = DoubleArray(8)
    correspondences.forEachIndexed { i, (pixel, court) ->
        src[2 * i] = pixel.x.toDouble(); src[2 * i + 1] = pixel.y.toDouble()
        dst[2 * i] = court.xMeters.toDouble(); dst[2 * i + 1] = court.yMeters.toDouble()
    }
    return solveHomography4(src, dst)
}

/**
 * The exact 4-point DLT behind [solveDlt], generalized to any direction:
 * maps `src` (x0, y0, ... x3, y3) onto `dst` with `h9` fixed at 1. The same
 * safety argument as [Homography] applies — callers only ever pass point
 * sets from a real camera view of a real plane, where the origin of `src`
 * never maps to infinity.
 */
internal fun solveHomography4(src: DoubleArray, dst: DoubleArray): DoubleArray? {
    require(src.size == 8 && dst.size == 8) { "solveHomography4 needs exactly 4 point pairs" }

    val a = Array(8) { DoubleArray(8) }
    val b = DoubleArray(8)

    for (i in 0 until 4) {
        val x = src[2 * i]
        val y = src[2 * i + 1]
        val u = dst[2 * i]
        val v = dst[2 * i + 1]

        val rowX = 2 * i
        a[rowX][0] = x; a[rowX][1] = y; a[rowX][2] = 1.0
        a[rowX][6] = -u * x; a[rowX][7] = -u * y
        b[rowX] = u

        val rowY = rowX + 1
        a[rowY][3] = x; a[rowY][4] = y; a[rowY][5] = 1.0
        a[rowY][6] = -v * x; a[rowY][7] = -v * y
        b[rowY] = v
    }

    val h = gaussianSolve(a, b) ?: return null
    return doubleArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0)
}

/**
 * Least-squares homography over any number (>= 4) of `src` -> `dst` point
 * pairs, flattened as (x0, y0, x1, y1, ...). Both point sets are
 * Hartley-normalized first (centroid at the origin, mean distance sqrt 2):
 * without that, pixel coordinates in the thousands next to court coordinates
 * in the tens make the normal equations too ill-conditioned to trust. Null if
 * the system is singular (fewer than 4 non-degenerate points).
 */
internal fun solveHomographyLeastSquares(src: DoubleArray, dst: DoubleArray): DoubleArray? {
    require(src.size == dst.size && src.size % 2 == 0 && src.size >= 8) {
        "solveHomographyLeastSquares needs >= 4 matching point pairs"
    }
    val n = src.size / 2
    val srcNorm = normalizationFor(src) ?: return null
    val dstNorm = normalizationFor(dst) ?: return null

    val ata = Array(8) { DoubleArray(8) }
    val atb = DoubleArray(8)
    val row = DoubleArray(8)
    for (i in 0 until n) {
        val x = srcNorm.scale * (src[2 * i] - srcNorm.cx)
        val y = srcNorm.scale * (src[2 * i + 1] - srcNorm.cy)
        val u = dstNorm.scale * (dst[2 * i] - dstNorm.cx)
        val v = dstNorm.scale * (dst[2 * i + 1] - dstNorm.cy)

        for ((target, isU) in listOf(u to true, v to false)) {
            row.fill(0.0)
            if (isU) {
                row[0] = x; row[1] = y; row[2] = 1.0
            } else {
                row[3] = x; row[4] = y; row[5] = 1.0
            }
            row[6] = -target * x; row[7] = -target * y
            for (r in 0 until 8) {
                if (row[r] == 0.0) continue
                for (c in 0 until 8) ata[r][c] += row[r] * row[c]
                atb[r] += row[r] * target
            }
        }
    }

    val h = gaussianSolve(ata, atb) ?: return null
    val normalized = doubleArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0)

    // H = inverse(T_dst) * H_normalized * T_src
    val tSrc = doubleArrayOf(
        srcNorm.scale, 0.0, -srcNorm.scale * srcNorm.cx,
        0.0, srcNorm.scale, -srcNorm.scale * srcNorm.cy,
        0.0, 0.0, 1.0,
    )
    val tDstInverse = doubleArrayOf(
        1.0 / dstNorm.scale, 0.0, dstNorm.cx,
        0.0, 1.0 / dstNorm.scale, dstNorm.cy,
        0.0, 0.0, 1.0,
    )
    val result = multiply3x3(tDstInverse, multiply3x3(normalized, tSrc))
    if (abs(result[8]) < 1e-12) return null
    return DoubleArray(9) { result[it] / result[8] }
}

private class Normalization(val cx: Double, val cy: Double, val scale: Double)

private fun normalizationFor(points: DoubleArray): Normalization? {
    val n = points.size / 2
    var cx = 0.0
    var cy = 0.0
    for (i in 0 until n) {
        cx += points[2 * i]; cy += points[2 * i + 1]
    }
    cx /= n; cy /= n
    var meanDistance = 0.0
    for (i in 0 until n) meanDistance += hypot(points[2 * i] - cx, points[2 * i + 1] - cy)
    meanDistance /= n
    if (meanDistance < 1e-9) return null
    return Normalization(cx, cy, sqrt(2.0) / meanDistance)
}

/** Row-major 3x3 matrix product `a * b`. */
internal fun multiply3x3(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9) { i ->
    val r = i / 3
    val c = i % 3
    a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
}

/** Solves `a*x = b` via Gaussian elimination with partial pivoting. Null if `a` is singular. */
private fun gaussianSolve(aIn: Array<DoubleArray>, bIn: DoubleArray): DoubleArray? {
    val n = bIn.size
    val a = Array(n) { aIn[it].copyOf() }
    val b = bIn.copyOf()

    for (col in 0 until n) {
        var pivotRow = col
        var maxAbs = abs(a[col][col])
        for (row in col + 1 until n) {
            val v = abs(a[row][col])
            if (v > maxAbs) {
                maxAbs = v
                pivotRow = row
            }
        }
        if (maxAbs < 1e-9) return null

        if (pivotRow != col) {
            val tmpRow = a[col]; a[col] = a[pivotRow]; a[pivotRow] = tmpRow
            val tmpB = b[col]; b[col] = b[pivotRow]; b[pivotRow] = tmpB
        }

        val pivot = a[col][col]
        for (row in col + 1 until n) {
            val factor = a[row][col] / pivot
            if (factor == 0.0) continue
            for (c in col until n) a[row][c] -= factor * a[col][c]
            b[row] -= factor * b[col]
        }
    }

    val x = DoubleArray(n)
    for (row in n - 1 downTo 0) {
        var sum = b[row]
        for (c in row + 1 until n) sum -= a[row][c] * x[c]
        x[row] = sum / a[row][row]
    }
    return x
}

/** Inverts a row-major 3x3 matrix via the closed-form adjugate. Null if singular. */
internal fun invert3x3(m: DoubleArray): DoubleArray? {
    val a = m[0]; val b = m[1]; val c = m[2]
    val d = m[3]; val e = m[4]; val f = m[5]
    val g = m[6]; val h = m[7]; val i = m[8]

    val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
    if (abs(det) < 1e-12) return null

    val invDet = 1.0 / det
    return doubleArrayOf(
        (e * i - f * h) * invDet, (c * h - b * i) * invDet, (b * f - c * e) * invDet,
        (f * g - d * i) * invDet, (a * i - c * g) * invDet, (c * d - a * f) * invDet,
        (d * h - e * g) * invDet, (b * g - a * h) * invDet, (a * e - b * d) * invDet,
    )
}
