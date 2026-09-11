package com.tennispro.core.vision

import com.tennispro.core.court.CalibrationPoints
import com.tennispro.core.court.CourtDimensions
import com.tennispro.core.court.CourtFormat
import com.tennispro.core.court.PixelPoint
import com.tennispro.core.court.invert3x3
import com.tennispro.core.court.multiply3x3
import com.tennispro.core.court.solveHomography4
import com.tennispro.core.court.solveHomographyLeastSquares
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** One court format's four corners in a frame's pixel space — same naming as [CalibrationPoints]. */
data class CourtCorners(
    val nearLeft: PixelPoint,
    val nearRight: PixelPoint,
    val farLeft: PixelPoint,
    val farRight: PixelPoint,
)

/**
 * A court found by [CourtLineDetector]. Both formats' corners come out of the
 * same fit — a painted court carries both sets of sidelines — so switching
 * Singles/Doubles after detecting doesn't need a second run.
 *
 * Corners can lie outside the frame: they are intersections of fitted lines,
 * so a baseline corner cut off by the frame edge is still extrapolated from
 * the visible parts of its two lines.
 *
 * [lineCoverage] is the fraction of the court model's line samples, among
 * those inside the frame, that landed on a white-line pixel — 1.0 would be
 * every visible court line exactly where the fit says it is.
 */
data class CourtDetection(
    val singles: CourtCorners,
    val doubles: CourtCorners,
    val lineCoverage: Float,
) {
    fun cornersFor(format: CourtFormat): CourtCorners = if (format == CourtFormat.SINGLES) singles else doubles

    fun toCalibration(format: CourtFormat, frameWidth: Int, frameHeight: Int): CalibrationPoints {
        val corners = cornersFor(format)
        return CalibrationPoints(
            format = format,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            nearLeft = corners.nearLeft,
            nearRight = corners.nearRight,
            farLeft = corners.farLeft,
            farRight = corners.farRight,
        )
    }
}

/**
 * Finds the court in a single still frame from its painted white lines —
 * classical CV, no model, in the same spirit as [BallDetector]. Follows the
 * standard court-model-fitting approach (Farin et al., "Robust Camera
 * Calibration for Sport Videos using Court Models"):
 *
 * 1. **Line pixels.** A pixel is a candidate if it is brighter than the
 *    pixels a fixed distance away on *both* sides, horizontally or
 *    vertically. That keeps thin bright lines and rejects big bright areas —
 *    sky, sunlit concrete — that a plain brightness threshold would not.
 * 2. **Lines.** A Hough transform over those pixels, taking the strongest
 *    line, removing its pixels' votes, and repeating, so one thick painted
 *    line doesn't come back as several near-duplicates.
 * 3. **Model fit.** Every pairing of two near-horizontal image lines with two
 *    sideline-ish image lines, against every assignment to the real court's
 *    baselines/service lines and singles/doubles sidelines, gives a candidate
 *    homography. Each is scored by projecting *all* the court's lines and
 *    counting how many land on line pixels (minus a penalty for those that
 *    don't). Neighbouring courts, the net tape, and fence posts produce lines
 *    too; this scoring against the whole court model is what rejects them.
 * 4. **Refinement.** Starting from the best candidate, each court line is
 *    re-found at full resolution by searching perpendicular to where it is
 *    predicted for the brightness ridge, fitted as a straight line, and the
 *    homography is re-solved by least squares over all line intersections.
 *
 * The result is a starting point for the calibration screen, not a
 * replacement for the user's check — the grid overlay and draggable corners
 * are still there to confirm or correct it.
 */
object CourtLineDetector {

    fun detect(frame: GrayscaleFrame): CourtDetection? = detect(frame, trace = null)

    internal fun detect(frame: GrayscaleFrame, trace: DetectorTrace?): CourtDetection? {
        val factor = max(1, (frame.width / WORKING_WIDTH.toDouble()).roundToInt())
        val work = downscale(frame, factor)
        val mask = lineMask(work)
        val lines = houghLines(work.width, work.height, mask)
        val hitMask = dilate(mask, work.width, work.height)
        trace?.let {
            it.factor = factor
            it.working = work
            it.mask = mask
            it.lines = lines
        }

        val hypotheses = topHypotheses(lines, work.width, work.height, hitMask, trace)
        if (hypotheses.isEmpty()) return null

        val fullToWork = invert3x3(workToFull(factor)) ?: return null
        fun coverageOf(m: DoubleArray) = coverage(multiply3x3(fullToWork, m), work.width, work.height, hitMask)
        val stamp = IntArray(work.width * work.height)
        var stampId = 0

        // The best few hypotheses are refined and the winner picked afterwards,
        // not before: on a real frame, a fit built from the service line and
        // far baseline out-scored the right one (near baseline and service
        // line) by 421 to 419 — its extrapolated near baseline was 45 px off,
        // too far for refinement to pull back, but the right hypothesis
        // refines to a clearly better score.
        var courtToPixel: DoubleArray? = null
        var coverage = 0f
        var bestRefinedScore = Double.NEGATIVE_INFINITY
        for (hypothesis in hypotheses) {
            var m = multiply3x3(workToFull(factor), hypothesis)
            var mCoverage = coverageOf(m)
            for (radius in REFINE_RADII) {
                val candidate = refine(frame, m, radius * factor) ?: continue
                val candidateCoverage = coverageOf(candidate)
                // Refinement should sharpen a fit, not move it: a real coverage drop
                // means some line latched onto a shadow edge or the net instead.
                if (candidateCoverage + REFINE_COVERAGE_SLACK >= mCoverage) {
                    m = candidate
                    mCoverage = candidateCoverage
                }
            }
            // Refined fits are compared mainly on how much of the detected line
            // structure they account for. The pixel score alone was fooled on two
            // real frames (2026-09-08) with a player standing on the near baseline:
            // a fit that pushed that baseline off the bottom of the frame dodged the
            // misses the player's body caused, and won — while leaving unexplained
            // the single strongest painted line in the image.
            val workM = multiply3x3(fullToWork, m)
            val refinedScore = explainedSupport(workM, lines, work.width, work.height) +
                score(workM, work.width, work.height, hitMask, stamp, ++stampId)
            if (refinedScore > bestRefinedScore) {
                bestRefinedScore = refinedScore
                courtToPixel = m
                coverage = mCoverage
                trace?.hypothesis = hypothesis
            }
        }
        if (courtToPixel == null) return null
        // Coverage alone can't tell a court from clutter: in a frame full of
        // line-like texture, any court-shaped guess lands on "line" pixels by
        // chance. So it must also clear the chance rate — the fraction of the
        // frame that is line pixels at all — by a wide margin.
        var linePixels = 0
        for (hit in hitMask) if (hit) linePixels++
        val chanceRate = linePixels.toFloat() / hitMask.size
        trace?.let {
            it.refined = courtToPixel
            it.coverage = coverage
            it.chanceRate = chanceRate
        }
        if (coverage < MIN_COVERAGE || coverage < MIN_COVERAGE_OVER_CHANCE * chanceRate) return null

        // Corners from where each baseline and sideline actually cross, not
        // from the least-squares homography: that fit spreads a little lens
        // distortion across the whole court, which was enough to put a near
        // corner ~5 px off its painted corner on a real frame. The user checks
        // the result by eye against the paint, so the paint is what to match.
        // The homography's projection stays as the fallback for a line that
        // couldn't be re-found (occluded, or out of frame).
        val finalRadius = REFINE_RADII.last() * factor
        val lineFits = HashMap<Int, ImageLine?>()
        fun lineFor(segment: Int) = lineFits.getOrPut(segment) {
            fitSegment(frame, courtToPixel, SEGMENTS[segment], finalRadius)
        }
        fun corner(baseline: Int, sideline: Int): PixelPoint {
            val a = lineFor(baseline)
            val b = lineFor(sideline)
            val crossing = if (a != null && b != null) intersect(a, b) else null
            val s = SEGMENTS[sideline]
            val p = crossing ?: project(courtToPixel, s.x0, SEGMENTS[baseline].y0)
                ?: return PixelPoint(Float.NaN, Float.NaN)
            return PixelPoint(p.first.toFloat(), p.second.toFloat())
        }
        val singles = CourtCorners(
            nearLeft = corner(NEAR_BASELINE, SINGLES_LEFT),
            nearRight = corner(NEAR_BASELINE, SINGLES_RIGHT),
            farLeft = corner(FAR_BASELINE, SINGLES_LEFT),
            farRight = corner(FAR_BASELINE, SINGLES_RIGHT),
        )
        val doubles = CourtCorners(
            nearLeft = corner(NEAR_BASELINE, DOUBLES_LEFT),
            nearRight = corner(NEAR_BASELINE, DOUBLES_RIGHT),
            farLeft = corner(FAR_BASELINE, DOUBLES_LEFT),
            farRight = corner(FAR_BASELINE, DOUBLES_RIGHT),
        )
        val all = listOf(singles, doubles).flatMap { listOf(it.nearLeft, it.nearRight, it.farLeft, it.farRight) }
        if (all.any { it.x.isNaN() || it.y.isNaN() }) return null
        return CourtDetection(singles, doubles, coverage)
    }

    // ------------------------------------------------------------ court model

    private const val WORKING_WIDTH = 960
    private val LENGTH = CourtDimensions.LENGTH_M
    private val DOUBLES_W = CourtDimensions.DOUBLES_WIDTH_M
    private val ALLEY_M = (CourtDimensions.DOUBLES_WIDTH_M - CourtDimensions.SINGLES_WIDTH_M) / 2
    private val NEAR_SERVICE_Y = LENGTH / 2 - CourtDimensions.SERVICE_LINE_FROM_NET_M
    private val FAR_SERVICE_Y = LENGTH / 2 + CourtDimensions.SERVICE_LINE_FROM_NET_M

    /**
     * Court coordinates here are doubles-anchored: x = 0 is the near-left
     * *doubles* corner, unlike [CalibrationPoints]' format-dependent origin.
     * Only this file sees that; outputs are pixel corners.
     */
    internal class Segment(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
        val isHorizontal get() = y0 == y1
    }

    internal val SEGMENTS: List<Segment> = listOf(
        Segment(0.0, 0.0, DOUBLES_W, 0.0), // near baseline
        Segment(0.0, LENGTH, DOUBLES_W, LENGTH), // far baseline
        Segment(ALLEY_M, NEAR_SERVICE_Y, DOUBLES_W - ALLEY_M, NEAR_SERVICE_Y),
        Segment(ALLEY_M, FAR_SERVICE_Y, DOUBLES_W - ALLEY_M, FAR_SERVICE_Y),
        Segment(0.0, 0.0, 0.0, LENGTH), // doubles sidelines
        Segment(DOUBLES_W, 0.0, DOUBLES_W, LENGTH),
        Segment(ALLEY_M, 0.0, ALLEY_M, LENGTH), // singles sidelines
        Segment(DOUBLES_W - ALLEY_M, 0.0, DOUBLES_W - ALLEY_M, LENGTH),
        Segment(DOUBLES_W / 2, NEAR_SERVICE_Y, DOUBLES_W / 2, FAR_SERVICE_Y), // centre service line
    )

    /** Indices into [SEGMENTS] for the lines corners are built from. */
    private const val NEAR_BASELINE = 0
    private const val FAR_BASELINE = 1
    private const val DOUBLES_LEFT = 4
    private const val DOUBLES_RIGHT = 5
    private const val SINGLES_LEFT = 6
    private const val SINGLES_RIGHT = 7

    /** Model lines a detected near-horizontal / sideline-ish image line may correspond to. */
    private val MODEL_YS = doubleArrayOf(0.0, NEAR_SERVICE_Y, FAR_SERVICE_Y, LENGTH)
    private val MODEL_XS = doubleArrayOf(0.0, ALLEY_M, DOUBLES_W - ALLEY_M, DOUBLES_W)

    /** Scoring samples along every court line, in court meters. */
    private const val SAMPLES_PER_METER = 4
    private val sampleXs: DoubleArray
    private val sampleYs: DoubleArray

    init {
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        for (s in SEGMENTS) {
            val length = hypot(s.x1 - s.x0, s.y1 - s.y0)
            val count = max(2, (length * SAMPLES_PER_METER).roundToInt())
            for (k in 0 until count) {
                val t = (k + 0.5) / count
                xs += s.x0 + (s.x1 - s.x0) * t
                ys += s.y0 + (s.y1 - s.y0) * t
            }
        }
        sampleXs = xs.toDoubleArray()
        sampleYs = ys.toDoubleArray()
    }

    // ------------------------------------------------------------ line pixels

    /** Distance, in working pixels, to the "both sides darker" comparison pixels. Must exceed the widest line's width. */
    private const val LINE_TEST_OFFSET = 6
    private const val LINE_MIN_LUMA = 110
    private const val LINE_MIN_CONTRAST = 20

    private fun downscale(frame: GrayscaleFrame, factor: Int): GrayscaleFrame {
        if (factor == 1) return frame
        val w = frame.width / factor
        val h = frame.height / factor
        val area = factor * factor
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0
                for (dy in 0 until factor) {
                    val row = (y * factor + dy) * frame.width + x * factor
                    for (dx in 0 until factor) sum += frame.pixels[row + dx]
                }
                out[y * w + x] = sum / area
            }
        }
        return GrayscaleFrame(w, h, out)
    }

    internal fun lineMask(img: GrayscaleFrame): BooleanArray {
        val w = img.width
        val h = img.height
        val p = img.pixels
        val t = LINE_TEST_OFFSET
        val mask = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val v = p[i]
                if (v < LINE_MIN_LUMA) continue
                val horizontal = x >= t && x < w - t &&
                    v - p[i - t] > LINE_MIN_CONTRAST && v - p[i + t] > LINE_MIN_CONTRAST
                val vertical = y >= t && y < h - t &&
                    v - p[i - t * w] > LINE_MIN_CONTRAST && v - p[i + t * w] > LINE_MIN_CONTRAST
                mask[i] = horizontal || vertical
            }
        }
        return mask
    }

    private fun dilate(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(mask.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!mask[y * w + x]) continue
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx in 0 until w) out[yy * w + xx] = true
                    }
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------ Hough

    /** A line `nx*x + ny*y = c` with a unit normal, in working-image pixels. */
    internal class ImageLine(val nx: Double, val ny: Double, val c: Double, val support: Int)

    private const val THETA_STEPS = 360 // 0.5 degree
    private const val MAX_LINES = 30
    private const val MIN_LINE_PIXELS = 40
    private const val INLIER_DISTANCE = 2.5
    private const val MAX_RUN_GAP = 6.0

    internal fun houghLines(w: Int, h: Int, mask: BooleanArray): List<ImageLine> {
        var count = 0
        for (m in mask) if (m) count++
        val xs = IntArray(count)
        val ys = IntArray(count)
        var k = 0
        for (i in mask.indices) {
            if (mask[i]) {
                xs[k] = i % w; ys[k] = i / w; k++
            }
        }
        val active = BooleanArray(count) { true }

        val cosT = DoubleArray(THETA_STEPS) { cos(it * PI / THETA_STEPS) }
        val sinT = DoubleArray(THETA_STEPS) { sin(it * PI / THETA_STEPS) }
        val rhoMax = hypot(w.toDouble(), h.toDouble()).toInt() + 1
        val nRho = 2 * rhoMax + 1
        val acc = IntArray(THETA_STEPS * nRho)

        fun vote(i: Int, delta: Int) {
            val x = xs[i].toDouble()
            val y = ys[i].toDouble()
            for (t in 0 until THETA_STEPS) {
                val r = (x * cosT[t] + y * sinT[t]).roundToInt() + rhoMax
                acc[t * nRho + r] += delta
            }
        }
        for (i in 0 until count) vote(i, 1)

        val lines = ArrayList<ImageLine>()
        val inliers = ArrayList<Int>()
        repeat(MAX_LINES) {
            var bestIndex = 0
            for (i in acc.indices) if (acc[i] > acc[bestIndex]) bestIndex = i
            if (acc[bestIndex] < MIN_LINE_PIXELS) return lines

            val t = bestIndex / nRho
            var nx = cosT[t]
            var ny = sinT[t]
            var c = (bestIndex % nRho - rhoMax).toDouble()

            // Two passes: collect around the Hough estimate, then around the least-squares refit of that.
            repeat(2) { pass ->
                inliers.clear()
                for (i in 0 until count) {
                    if (active[i] && abs(nx * xs[i] + ny * ys[i] - c) <= INLIER_DISTANCE) inliers += i
                }
                if (pass == 0 && inliers.size >= 2) {
                    val fit = fitLine(inliers.size, { xs[inliers[it]].toDouble() }, { ys[inliers[it]].toDouble() })
                    if (fit != null) {
                        nx = fit[0]; ny = fit[1]; c = fit[2]
                    }
                }
            }
            if (inliers.isEmpty()) {
                // The refit drifted off every pixel; drop this accumulator peak so the loop moves on.
                acc[bestIndex] = 0
                return@repeat
            }

            for (i in inliers) {
                active[i] = false
                vote(i, -1)
            }
            val support = longestRun(inliers, xs, ys, nx, ny)
            if (support >= MIN_LINE_PIXELS) lines += ImageLine(nx, ny, c, support)
        }
        return lines
    }

    /** Pixel count of the longest stretch along the line without a gap wider than [MAX_RUN_GAP]. */
    private fun longestRun(inliers: List<Int>, xs: IntArray, ys: IntArray, nx: Double, ny: Double): Int {
        val along = DoubleArray(inliers.size) { -ny * xs[inliers[it]] + nx * ys[inliers[it]] }
        along.sort()
        var best = 0
        var runStart = 0
        for (i in along.indices) {
            if (i > 0 && along[i] - along[i - 1] > MAX_RUN_GAP) runStart = i
            best = max(best, i - runStart + 1)
        }
        return best
    }

    // ------------------------------------------------------------- model fit

    private val MAX_HORIZONTAL_NORMAL = cos(10.0 * PI / 180) // |ny| above this: within 10 deg of horizontal
    private val MIN_SIDELINE_NORMAL = cos(14.0 * PI / 180) // |ny| below this: at least 14 deg off horizontal
    private const val MAX_HORIZONTAL_CANDIDATES = 8
    private const val MAX_SIDELINE_CANDIDATES = 10
    private const val MISS_WEIGHT = 0.5
    private const val REFINED_HYPOTHESES = 8
    private val MAX_EXPLAIN_SIN = sin(2.0 * PI / 180)
    private const val MAX_EXPLAIN_DISTANCE = 4.0

    /**
     * Minimum size of a hypothesis's quadrilateral, as fractions of the
     * frame. The calibration screen asks for the whole court in view, so a
     * court much smaller than this is a wrong hypothesis, not a far-away court.
     */
    private const val MIN_NEAR_WIDTH = 0.15
    private const val MIN_FAR_WIDTH = 0.02
    private const val MIN_QUAD_HEIGHT = 0.03

    /** The [REFINED_HYPOTHESES] best-scoring court hypotheses, best first, as court -> working-pixel homographies. */
    private fun topHypotheses(
        lines: List<ImageLine>,
        w: Int,
        h: Int,
        hitMask: BooleanArray,
        trace: DetectorTrace?,
    ): List<DoubleArray> {
        val horizontals = lines.filter { abs(it.ny) >= MAX_HORIZONTAL_NORMAL }
            .sortedByDescending { it.support }.take(MAX_HORIZONTAL_CANDIDATES)
        val sidelines = lines.filter { abs(it.ny) <= MIN_SIDELINE_NORMAL }
            .sortedByDescending { it.support }.take(MAX_SIDELINE_CANDIDATES)
        if (horizontals.size < 2 || sidelines.size < 2) return emptyList()

        val centerX = w / 2.0
        var bestScore = Double.NEGATIVE_INFINITY
        // Kept sorted by score, best first.
        val top = ArrayList<Pair<Double, DoubleArray>>(REFINED_HYPOTHESES + 1)
        val court = DoubleArray(8)
        val pixel = DoubleArray(8)
        val stamp = IntArray(w * h)
        var tried = 0

        for (i in horizontals.indices) for (j in i + 1 until horizontals.size) {
            val a = horizontals[i]
            val b = horizontals[j]
            val (near, far) = if (yAt(a, centerX) > yAt(b, centerX)) a to b else b to a

            for (p in sidelines.indices) for (q in p + 1 until sidelines.size) {
                val ip = intersect(sidelines[p], near) ?: continue
                val iq = intersect(sidelines[q], near) ?: continue
                val (left, right) = if (ip.first < iq.first) sidelines[p] to sidelines[q] else sidelines[q] to sidelines[p]
                val nl = if (ip.first < iq.first) ip else iq
                val nr = if (ip.first < iq.first) iq else ip
                val fl = intersect(left, far) ?: continue
                val fr = intersect(right, far) ?: continue
                if (!plausibleQuad(nl, nr, fl, fr, w, h)) continue

                pixel[0] = nl.first; pixel[1] = nl.second
                pixel[2] = nr.first; pixel[3] = nr.second
                pixel[4] = fl.first; pixel[5] = fl.second
                pixel[6] = fr.first; pixel[7] = fr.second

                for (ya in 0 until MODEL_YS.size) for (yb in ya + 1 until MODEL_YS.size) {
                    for (xa in 0 until MODEL_XS.size) for (xb in xa + 1 until MODEL_XS.size) {
                        court[0] = MODEL_XS[xa]; court[1] = MODEL_YS[ya]
                        court[2] = MODEL_XS[xb]; court[3] = MODEL_YS[ya]
                        court[4] = MODEL_XS[xa]; court[5] = MODEL_YS[yb]
                        court[6] = MODEL_XS[xb]; court[7] = MODEL_YS[yb]
                        val homography = solveHomography4(court, pixel) ?: continue
                        tried++
                        val score = score(homography, w, h, hitMask, stamp, tried)
                        if (top.size < REFINED_HYPOTHESES || score > top.last().first) {
                            val at = top.indexOfFirst { it.first < score }.let { if (it < 0) top.size else it }
                            top.add(at, score to homography)
                            if (top.size > REFINED_HYPOTHESES) top.removeAt(top.lastIndex)
                        }
                        if (score > bestScore) {
                            bestScore = score
                            trace?.bestSoFar?.add(
                                "score=$score near@c=${yAt(near, centerX).toInt()} far@c=${yAt(far, centerX).toInt()} " +
                                    "NL=${nl.first.toInt()},${nl.second.toInt()} NR=${nr.first.toInt()},${nr.second.toInt()} " +
                                    "model y=${MODEL_YS[ya]},${MODEL_YS[yb]} x=${MODEL_XS[xa]},${MODEL_XS[xb]}",
                            )
                        }
                    }
                }
            }
        }
        trace?.hypothesesTried = tried
        return top.map { it.second }
    }

    /** Seen from behind the near baseline: near corners below far ones, left of right, near side wider. */
    private fun plausibleQuad(
        nl: Pair<Double, Double>,
        nr: Pair<Double, Double>,
        fl: Pair<Double, Double>,
        fr: Pair<Double, Double>,
        w: Int,
        h: Int,
    ): Boolean {
        val points = listOf(nl, nr, fl, fr)
        if (points.any { it.first < -w || it.first > 2.0 * w || it.second < -h || it.second > 2.0 * h }) return false
        if (nl.second - fl.second < MIN_QUAD_HEIGHT * h || nr.second - fr.second < MIN_QUAD_HEIGHT * h) return false
        if (fl.first >= fr.first) return false
        val nearWidth = nr.first - nl.first
        val farWidth = fr.first - fl.first
        return nearWidth >= farWidth && nearWidth >= MIN_NEAR_WIDTH * w && farWidth >= MIN_FAR_WIDTH * w
    }

    /**
     * Hits minus weighted misses over every in-frame court-line sample, each
     * image pixel counted once. Negative infinity if any sample is behind the
     * camera.
     *
     * Counting pixels once matters: court-space samples are uniform, so a
     * wrong hypothesis that squeezes the whole court into a few pixels —
     * typically near the vanishing point, where many detected lines converge
     * — would otherwise score every collapsed sample as a separate hit.
     * [stamp] holds the id of the last hypothesis that counted each pixel.
     */
    private fun score(m: DoubleArray, w: Int, h: Int, hitMask: BooleanArray, stamp: IntArray, id: Int): Double {
        var hits = 0
        var misses = 0
        for (k in sampleXs.indices) {
            val x = sampleXs[k]
            val y = sampleYs[k]
            val den = m[6] * x + m[7] * y + m[8]
            if (den <= 1e-9) return Double.NEGATIVE_INFINITY
            val u = (m[0] * x + m[1] * y + m[2]) / den
            val v = (m[3] * x + m[4] * y + m[5]) / den
            if (u < 0 || v < 0 || u >= w || v >= h) continue
            val i = v.toInt() * w + u.toInt()
            if (stamp[i] == id) continue
            stamp[i] = id
            if (hitMask[i]) hits++ else misses++
        }
        return hits - MISS_WEIGHT * misses
    }

    /**
     * Total support of the detected [lines] that some projected court line lies
     * along — parallel within [MAX_EXPLAIN_SIN] and within
     * [MAX_EXPLAIN_DISTANCE] px where the court line crosses the frame.
     */
    private fun explainedSupport(m: DoubleArray, lines: List<ImageLine>, w: Int, h: Int): Double {
        var total = 0
        for (line in lines) {
            val horizontal = abs(line.ny) >= abs(line.nx)
            for (s in SEGMENTS) {
                val a = project(m, s.x0, s.y0) ?: continue
                val b = project(m, s.x1, s.y1) ?: continue
                val length = hypot(b.first - a.first, b.second - a.second)
                if (length < 1e-6) continue
                val modelNx = -(b.second - a.second) / length
                val modelNy = (b.first - a.first) / length
                if (abs(line.nx * modelNy - line.ny * modelNx) > MAX_EXPLAIN_SIN) continue

                // Compare the two lines at the model segment's midpoint, clamped into the frame.
                val midX = ((a.first + b.first) / 2).coerceIn(0.0, w - 1.0)
                val midY = ((a.second + b.second) / 2).coerceIn(0.0, h - 1.0)
                val px = if (horizontal) midX else (line.c - line.ny * midY) / line.nx
                val py = if (horizontal) (line.c - line.nx * midX) / line.ny else midY
                if (px < 0 || py < 0 || px > w - 1 || py > h - 1) continue
                if (abs(modelNx * (px - a.first) + modelNy * (py - a.second)) <= MAX_EXPLAIN_DISTANCE) {
                    total += line.support
                    break
                }
            }
        }
        return total.toDouble()
    }

    private fun coverage(m: DoubleArray, w: Int, h: Int, hitMask: BooleanArray): Float {
        var hits = 0
        var inFrame = 0
        for (k in sampleXs.indices) {
            val p = project(m, sampleXs[k], sampleYs[k]) ?: return 0f
            val u = p.first
            val v = p.second
            if (u < 0 || v < 0 || u >= w || v >= h) continue
            inFrame++
            if (hitMask[v.toInt() * w + u.toInt()]) hits++
        }
        return if (inFrame < MIN_IN_FRAME_SAMPLES) 0f else hits.toFloat() / inFrame
    }

    private const val MIN_IN_FRAME_SAMPLES = 60
    private const val MIN_COVERAGE = 0.45f
    private const val MIN_COVERAGE_OVER_CHANCE = 4f

    // ------------------------------------------------------------- refinement

    /**
     * Perpendicular search half-widths, in working pixels (scaled to full
     * resolution by the caller): coarse, then fine. The coarse pass has to be
     * wide because a hypothesis fitted on far-court lines extrapolates the
     * near baseline tens of pixels off; the coverage guard in [detect] stops a
     * wide search that grabbed the wrong line from being kept.
     */
    private val REFINE_RADII = intArrayOf(16, 6, 3)
    private const val REFINE_SAMPLES_PER_SEGMENT = 160
    private const val MIN_RIDGE_CONTRAST = 20.0
    private const val MIN_REFINE_POINTS = 12

    /** Working-resolution coverage is too coarse to reward sub-pixel gains, so allow a sliver of noise either way. */
    private const val REFINE_COVERAGE_SLACK = 0.03f

    /**
     * Re-finds every court line near where [courtToPixel] predicts it, at full
     * resolution, and re-solves the homography by least squares over the
     * intersections of the lines that were found. Null if too few lines were
     * found to constrain it.
     */
    private fun refine(frame: GrayscaleFrame, courtToPixel: DoubleArray, radius: Int): DoubleArray? {
        val fitted = SEGMENTS.map { fitSegment(frame, courtToPixel, it, radius) }

        val src = ArrayList<Double>()
        val dst = ArrayList<Double>()
        var horizontalLines = 0
        var verticalLines = 0
        SEGMENTS.forEachIndexed { i, s ->
            if (fitted[i] != null) if (s.isHorizontal) horizontalLines++ else verticalLines++
        }
        if (horizontalLines < 2 || verticalLines < 2) return null

        SEGMENTS.forEachIndexed { hi, hs ->
            if (!hs.isHorizontal) return@forEachIndexed
            val hl = fitted[hi] ?: return@forEachIndexed
            SEGMENTS.forEachIndexed { vi, vs ->
                if (vs.isHorizontal) return@forEachIndexed
                val vl = fitted[vi] ?: return@forEachIndexed
                val p = intersect(hl, vl) ?: return@forEachIndexed
                src += vs.x0; src += hs.y0
                dst += p.first; dst += p.second
            }
        }
        if (src.size < 8) return null
        return solveHomographyLeastSquares(src.toDoubleArray(), dst.toDoubleArray())
    }

    private fun fitSegment(frame: GrayscaleFrame, m: DoubleArray, s: Segment, radius: Int): ImageLine? {
        val length = hypot(s.x1 - s.x0, s.y1 - s.y0)
        val dirX = (s.x1 - s.x0) / length
        val dirY = (s.y1 - s.y0) / length
        val xs = DoubleArray(REFINE_SAMPLES_PER_SEGMENT)
        val ys = DoubleArray(REFINE_SAMPLES_PER_SEGMENT)
        val profile = DoubleArray(2 * radius + 1)
        var n = 0
        var firstPredicted: Pair<Double, Double>? = null
        var lastPredicted: Pair<Double, Double>? = null

        for (k in 0 until REFINE_SAMPLES_PER_SEGMENT) {
            val t = (k + 0.5) / REFINE_SAMPLES_PER_SEGMENT
            val cx = s.x0 + (s.x1 - s.x0) * t
            val cy = s.y0 + (s.y1 - s.y0) * t
            val p = project(m, cx, cy) ?: continue
            val q = project(m, cx + dirX * 0.1, cy + dirY * 0.1) ?: continue
            val tl = hypot(q.first - p.first, q.second - p.second)
            if (tl < 1e-6) continue
            val normalX = -(q.second - p.second) / tl
            val normalY = (q.first - p.first) / tl

            val margin = radius + 2.0
            if (p.first < margin || p.second < margin ||
                p.first > frame.width - 1 - margin || p.second > frame.height - 1 - margin
            ) continue
            if (firstPredicted == null) firstPredicted = p
            lastPredicted = p

            for (o in -radius..radius) {
                profile[o + radius] = bilinear(frame, p.first + o * normalX, p.second + o * normalY)
            }
            val offset = ridgeOffset(profile, radius) ?: continue
            xs[n] = p.first + offset * normalX
            ys[n] = p.second + offset * normalY
            n++
        }
        if (n < MIN_REFINE_POINTS) return null

        // Fit, drop outliers (occlusions, the net's own tape crossing a sideline), refit.
        val first = fitLine(n, { xs[it] }, { ys[it] }) ?: return null
        val residuals = DoubleArray(n) { abs(first[0] * xs[it] + first[1] * ys[it] - first[2]) }
        val cutoff = max(1.5, 3.0 * median(residuals))
        val keep = (0 until n).filter { residuals[it] <= cutoff }
        if (keep.size < MIN_REFINE_POINTS) return null
        val fit = fitLine(keep.size, { xs[keep[it]] }, { ys[keep[it]] }) ?: return null

        // Every ridge point was within `radius` of the prediction, but a fit through
        // a cluster of wrong ones can still tilt away from it; reject that too.
        for (p in listOfNotNull(firstPredicted, lastPredicted)) {
            if (abs(fit[0] * p.first + fit[1] * p.second - fit[2]) > radius) return null
        }
        return ImageLine(fit[0], fit[1], fit[2], keep.size)
    }

    /**
     * Sub-pixel offset of the brightness ridge closest to the profile's centre,
     * or null if there isn't one brighter than both its sides by
     * [MIN_RIDGE_CONTRAST]. Closest rather than brightest: a singles and a
     * doubles sideline can both fall inside the search window far down the
     * court, and the prediction is already near the right one.
     */
    private fun ridgeOffset(v: DoubleArray, radius: Int): Double? {
        var chosen = -1
        var chosenLeftMin = 0.0
        var chosenRightMin = 0.0
        for (i in 1 until v.size - 1) {
            if (v[i] < v[i - 1] || v[i] < v[i + 1]) continue
            var leftMin = v[i]
            for (j in 0..i) leftMin = min(leftMin, v[j])
            var rightMin = v[i]
            for (j in i until v.size) rightMin = min(rightMin, v[j])
            if (v[i] - max(leftMin, rightMin) < MIN_RIDGE_CONTRAST) continue
            if (chosen < 0 || abs(i - radius) < abs(chosen - radius)) {
                chosen = i
                chosenLeftMin = leftMin
                chosenRightMin = rightMin
            }
        }
        if (chosen < 0) return null

        // Midpoint of the line's two edges, each found at half height against
        // *its own side's* background. A single threshold (or a brightness
        // centroid) is biased toward the brighter side: a baseline between
        // sunlit green and dark blue court measured ~2 px toward the green on
        // a real frame, which moved its corner ~3 px along the sideline.
        val peak = v[chosen]
        val leftHalf = (peak + chosenLeftMin) / 2
        var lo = chosen
        while (lo > 0 && v[lo - 1] > leftHalf) lo--
        val leftEdge = if (lo > 0) lo - 1 + (leftHalf - v[lo - 1]) / (v[lo] - v[lo - 1]) else lo.toDouble()

        val rightHalf = (peak + chosenRightMin) / 2
        var hi = chosen
        while (hi < v.size - 1 && v[hi + 1] > rightHalf) hi++
        val rightEdge = if (hi < v.size - 1) hi + (v[hi] - rightHalf) / (v[hi] - v[hi + 1]) else hi.toDouble()

        return (leftEdge + rightEdge) / 2 - radius
    }

    // ---------------------------------------------------------------- helpers

    /** Maps working-image pixel coordinates (block centres) to full-resolution ones. */
    private fun workToFull(factor: Int): DoubleArray {
        val offset = (factor - 1) / 2.0
        return doubleArrayOf(
            factor.toDouble(), 0.0, offset,
            0.0, factor.toDouble(), offset,
            0.0, 0.0, 1.0,
        )
    }

    private fun project(m: DoubleArray, x: Double, y: Double): Pair<Double, Double>? {
        val den = m[6] * x + m[7] * y + m[8]
        if (den <= 1e-9) return null
        return (m[0] * x + m[1] * y + m[2]) / den to (m[3] * x + m[4] * y + m[5]) / den
    }

    private fun yAt(line: ImageLine, x: Double): Double = (line.c - line.nx * x) / line.ny

    private fun intersect(a: ImageLine, b: ImageLine): Pair<Double, Double>? {
        val det = a.nx * b.ny - a.ny * b.nx
        if (abs(det) < 1e-6) return null
        return (a.c * b.ny - a.ny * b.c) / det to (a.nx * b.c - a.c * b.nx) / det
    }

    /** Total-least-squares line through `n` points: (nx, ny, c) with a unit normal, or null if degenerate. */
    private inline fun fitLine(n: Int, x: (Int) -> Double, y: (Int) -> Double): DoubleArray? {
        if (n < 2) return null
        var mx = 0.0
        var my = 0.0
        for (i in 0 until n) {
            mx += x(i); my += y(i)
        }
        mx /= n; my /= n
        var sxx = 0.0
        var sxy = 0.0
        var syy = 0.0
        for (i in 0 until n) {
            val dx = x(i) - mx
            val dy = y(i) - my
            sxx += dx * dx; sxy += dx * dy; syy += dy * dy
        }
        if (sxx + syy < 1e-9) return null
        val angle = 0.5 * kotlin.math.atan2(2 * sxy, sxx - syy)
        val nx = -sin(angle)
        val ny = cos(angle)
        return doubleArrayOf(nx, ny, nx * mx + ny * my)
    }

    private fun bilinear(frame: GrayscaleFrame, x: Double, y: Double): Double {
        val x0 = x.toInt()
        val y0 = y.toInt()
        val fx = x - x0
        val fy = y - y0
        val w = frame.width
        val p = frame.pixels
        val i = y0 * w + x0
        val top = p[i] * (1 - fx) + p[i + 1] * fx
        val bottom = p[i + w] * (1 - fx) + p[i + w + 1] * fx
        return top * (1 - fy) + bottom * fy
    }

    private fun median(values: DoubleArray): Double {
        val sorted = values.sortedArray()
        return sorted[sorted.size / 2]
    }
}

/** Intermediate results of one [CourtLineDetector.detect] run, for tests and tuning. */
internal class DetectorTrace {
    var factor = 1
    var working: GrayscaleFrame? = null
    var mask: BooleanArray? = null
    var lines: List<CourtLineDetector.ImageLine> = emptyList()
    var hypothesesTried = 0

    /** Court (doubles-anchored meters) -> working-image pixels, before refinement. */
    var hypothesis: DoubleArray? = null

    /** Court (doubles-anchored meters) -> full-resolution pixels, after refinement. */
    var refined: DoubleArray? = null
    var coverage = 0f
    var chanceRate = 0f

    /** Every hypothesis that became the best so far, in order — the last one won. */
    val bestSoFar = ArrayList<String>()
}
