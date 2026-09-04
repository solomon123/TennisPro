package com.tennispro.core.court

import kotlinx.serialization.Serializable
import kotlin.math.abs

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
)

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
private fun applyHomogeneous(m: DoubleArray, x: Double, y: Double): Pair<Double, Double> {
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

    val a = Array(8) { DoubleArray(8) }
    val b = DoubleArray(8)

    correspondences.forEachIndexed { i, (pixel, court) ->
        val x = pixel.x.toDouble()
        val y = pixel.y.toDouble()
        val worldX = court.xMeters.toDouble()
        val worldY = court.yMeters.toDouble()

        val rowX = 2 * i
        a[rowX][0] = x; a[rowX][1] = y; a[rowX][2] = 1.0
        a[rowX][6] = -worldX * x; a[rowX][7] = -worldX * y
        b[rowX] = worldX

        val rowY = rowX + 1
        a[rowY][3] = x; a[rowY][4] = y; a[rowY][5] = 1.0
        a[rowY][6] = -worldY * x; a[rowY][7] = -worldY * y
        b[rowY] = worldY
    }

    val h = gaussianSolve(a, b) ?: return null
    return doubleArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0)
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
private fun invert3x3(m: DoubleArray): DoubleArray? {
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
