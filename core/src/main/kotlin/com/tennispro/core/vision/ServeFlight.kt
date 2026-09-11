package com.tennispro.core.vision

import com.tennispro.core.court.CourtDimensions
import com.tennispro.core.court.CourtFormat
import com.tennispro.core.court.CourtPoint
import com.tennispro.core.court.Homography
import com.tennispro.core.court.PixelPoint
import com.tennispro.core.court.ServiceCall
import com.tennispro.core.court.ServiceLineCall
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** The ball candidates [MotionBlobs] found in one frame. */
data class FrameBlobs(val timeMs: Long, val blobs: List<BallCandidate>)

/** What the ball did after a proposed serve. */
sealed interface ServeOutcome {

    /**
     * A serve that crossed the net and bounced. [launchSpeedMetersPerSecond]
     * is the speed off the racket — what a radar gun reports — not the slower
     * average over the flight.
     */
    data class Measured(
        val contactMs: Long,
        val bounceMs: Long,
        val bounce: CourtPoint,
        val launchSpeedMetersPerSecond: Double,
        val errorBandPercent: Double,
        val call: ServiceCall,
    ) : ServeOutcome {
        val kmh: Double get() = launchSpeedMetersPerSecond * 3.6
    }

    /** A serve into the net: the ball stopped at the net or dropped back on the server's side. */
    data class NetFault(val contactMs: Long) : ServeOutcome

    /** No flight that could be a serve — the proposal was something else, or the ball wasn't visible. */
    data class NoFlight(val reason: String) : ServeOutcome
}

/**
 * Confirms a [ServeProposal] by finding the ball's flight in the frames that
 * follow it, and measures the serve.
 *
 * **The flight.** Ball candidates are chained frame to frame from just after
 * the racket rises: each extension predicts the next position from the last
 * three (constant acceleration on screen — gravity plus perspective), also
 * offering a reflected continuation while the ball is descending so a track
 * survives its bounce, and takes the nearest candidate in a window that widens
 * with every missed frame. Every chain is judged on the full measurement it
 * would produce, and the best *complete* one wins — preferring a plausible
 * measured serve, then a net fault, then length. Longest-chain-wins alone
 * picked, on real footage, the slow player on the next court, the rising toss,
 * and a short junk track that happened to end in the service box.
 *
 * **What makes it a serve and not a groundstroke:** a toss above the server's
 * head just before the flight, and a flight that starts above the head. On the
 * 2026-09-08 rally recording, pose alone proposed groundstrokes and an overhead.
 *
 * **Contact** is midway between the last blob above the head (the falling
 * toss, or the racket meeting it) and the flight's first point: the racket
 * hides the ball in between.
 *
 * **The bounce** is where the ground's impulse kinks the on-screen track. It
 * is searched for from the track's lowest on-screen point onward, not taken as
 * that point: seen from behind the server, a ball flying away climbs the screen
 * through perspective faster than it falls, so its on-screen low point comes
 * *before* it lands — taking it as the bounce read a simulated 162 km/h serve
 * as 183. Nor the sharpest kink anywhere: close to the camera, perspective
 * alone kinks a real track more than the bounce does, far down the court.
 *
 * **The speed** comes from contact to bounce, not from the flight's pixels.
 * Mapping an airborne ball through the ground-plane homography (Phase 3's
 * approach) has no geometric meaning, and a full 3D camera fit was too
 * sensitive to the camera's focal length, which the phone reports only
 * approximately. What *is* known well: the bounce point (on the ground, exact
 * through the homography), the flight time (frame timestamps), and roughly
 * where contact happened (above the server's feet, ~2.6 m up). The straight
 * distance and time give an average speed; the drag equation turns that into
 * the launch speed.
 */
object ServeFlight {

    fun analyze(
        frames: List<FrameBlobs>,
        proposal: ServeProposal,
        homography: Homography,
        format: CourtFormat,
        fps: Double,
    ): ServeOutcome {
        if (frames.size < MIN_CHAIN) return ServeOutcome.NoFlight("Too few frames to track a ball")
        val blobs = withoutStaticNoise(frames)
        val grids = blobs.map { Grid(it) }
        val box = proposal.server.box
        val headY = proposal.server.nose.y.toDouble()
        val seedRegion = PixelRect(
            left = box.left - SEED_MARGIN_X,
            top = Double.NEGATIVE_INFINITY,
            right = box.right + SEED_MARGIN_X,
            bottom = headY,
        )
        val feet = homography.mapToCourt(proposal.server.feet)
        val contactPosition = doubleArrayOf(feet.xMeters.toDouble(), feet.yMeters + CONTACT_AHEAD_OF_FEET_M, CONTACT_HEIGHT_M)
        val body = bodyMask(proposal.server, frameHeight = Int.MAX_VALUE)
        val context = Context(frames, blobs, proposal, homography, format, contactPosition, fps, headY, body)

        // Blobs already part of a full-length chain don't seed another: the same
        // flight would otherwise be rebuilt from every one of its points.
        val used = HashSet<Long>()
        var best: Evaluation? = null
        for (seed in 0 until frames.size - 1) {
            val sinceRacketUp = frames[seed].timeMs - proposal.racketUpMs
            if (sinceRacketUp < 0) continue
            if (sinceRacketUp > SEED_WINDOW_MS) break
            blobs[seed].forEachIndexed { ai, a ->
                if (!seedRegion.contains(a.x, a.y) || key(seed, ai) in used) return@forEachIndexed
                blobs[seed + 1].forEachIndexed { bi, b ->
                    val step = hypot(b.x - a.x, b.y - a.y)
                    if (step < MIN_SEED_STEP_PX || step > MAX_SEED_STEP_PX) return@forEachIndexed
                    val chain = extend(grids, seed, Link(seed, ai, a.x, a.y), Link(seed + 1, bi, b.x, b.y))
                    if (chain.size >= MIN_CHAIN) chain.forEach { used += key(it.frame, it.index) }
                    if (!movesLikeABall(chain)) return@forEachIndexed
                    val evaluation = evaluate(chain, context) ?: return@forEachIndexed
                    if (best == null || evaluation.betterThan(best!!)) best = evaluation
                }
            }
        }

        return best?.outcome ?: ServeOutcome.NoFlight("No ball flight found after the swing")
    }

    /**
     * The parts of the frame the server covers, for [MotionBlobs]' exclusion —
     * and for refusing a bounce hidden behind them: the body, full pose width,
     * from just above the nose down; and the head above that, only as wide as
     * a head.
     *
     * The first cut stopped at 20 px above the nose, leaving the top of the
     * head unmasked: on a 2026-09-08 serve the ball landed behind the server's
     * head from the camera's view, the tracker followed the head's own movement
     * instead, and the serve was called OUT by 1.72 m. Masking the full pose
     * width that high fixed that but swallowed another serve's toss beside the
     * head, reading 117 km/h for a 136 km/h serve — hence the narrow head box.
     */
    fun bodyMask(server: PoseKeypoints, frameHeight: Int): List<PixelRect> {
        val neck = max(MIN_NECK_PX, (server.leftShoulder.y + server.rightShoulder.y) / 2.0 - server.nose.y)
        val body = PixelRect(
            left = server.box.left - BODY_MARGIN_PX,
            top = server.nose.y - MIN_NECK_PX,
            right = server.box.right + BODY_MARGIN_PX,
            bottom = frameHeight.toDouble(),
        )
        val head = PixelRect(
            left = server.nose.x - HEAD_HALF_WIDTH_PER_NECK * neck,
            top = server.nose.y - HEAD_ABOVE_NOSE_PER_NECK * neck,
            right = server.nose.x + HEAD_HALF_WIDTH_PER_NECK * neck,
            bottom = server.nose.y.toDouble(),
        )
        return listOf(body, head)
    }

    // ------------------------------------------------------------------ chains

    private class Link(val frame: Int, val index: Int, val x: Double, val y: Double)

    private fun key(frame: Int, index: Int) = frame.toLong() shl 32 or index.toLong()

    /** Buckets one frame's candidates for nearest-within-radius lookups. */
    private class Grid(val blobs: List<BallCandidate>) {
        private val cells = HashMap<Long, MutableList<Int>>()

        init {
            blobs.forEachIndexed { i, b -> cells.getOrPut(cell(b.x, b.y)) { mutableListOf() } += i }
        }

        /** Index of the candidate nearest to either point, within [radius] of it, or -1. */
        fun nearest(x1: Double, y1: Double, x2: Double, y2: Double, radius: Double): Int {
            var best = -1
            var bestDistance = radius
            for ((x, y) in listOf(x1 to y1, x2 to y2)) {
                val cx0 = ((x - radius) / CELL_PX).toInt()
                val cx1 = ((x + radius) / CELL_PX).toInt()
                val cy0 = ((y - radius) / CELL_PX).toInt()
                val cy1 = ((y + radius) / CELL_PX).toInt()
                for (cx in cx0..cx1) for (cy in cy0..cy1) {
                    val bucket = cells[cx.toLong() shl 32 or (cy.toLong() and 0xffffffffL)] ?: continue
                    for (i in bucket) {
                        val d = hypot(blobs[i].x - x, blobs[i].y - y)
                        if (d <= bestDistance) {
                            bestDistance = d
                            best = i
                        }
                    }
                }
            }
            return best
        }

        private fun cell(x: Double, y: Double) = (x / CELL_PX).toInt().toLong() shl 32 or ((y / CELL_PX).toInt().toLong() and 0xffffffffL)

        private companion object {
            const val CELL_PX = 32.0
        }
    }

    private fun extend(grids: List<Grid>, seed: Int, a: Link, b: Link): List<Link> {
        val chain = mutableListOf(a, b)
        var missed = 0
        var frame = seed + 2
        while (frame < grids.size && missed <= MAX_MISSED_FRAMES) {
            val last = chain[chain.size - 1]
            val before = chain[chain.size - 2]
            val steps = (frame - last.frame).toDouble()
            val vx = (last.x - before.x) / (last.frame - before.frame)
            val vy = (last.y - before.y) / (last.frame - before.frame)
            var ax = 0.0
            var ay = 0.0
            if (chain.size >= 3) {
                val first = chain[chain.size - 3]
                val vx0 = (before.x - first.x) / (before.frame - first.frame)
                val vy0 = (before.y - first.y) / (before.frame - first.frame)
                val span = (last.frame - first.frame) / 2.0
                ax = (vx - vx0) / span
                ay = (vy - vy0) / span
            }
            val px = last.x + vx * steps + 0.5 * ax * steps * steps
            val py = last.y + vy * steps + 0.5 * ay * steps * steps
            // A bounce flips vertical motion on screen from one frame to the next, which
            // no smooth prediction anticipates — a fast serve's track used to end right at
            // its bounce. While the ball is descending, offer the reflected continuation too.
            val bouncedY = if (vy > 0) last.y - BOUNCE_RESTITUTION * vy * steps else py
            val bouncedX = last.x + vx * steps
            val gate = GATE_BASE_PX + GATE_PER_STEP_PX * steps

            val grid = grids[frame]
            val next = grid.nearest(px, py, bouncedX, bouncedY, gate)
            if (next >= 0) {
                val blob = grid.blobs[next]
                chain += Link(frame, next, blob.x, blob.y)
                missed = 0
            } else {
                missed++
            }
            frame++
        }
        return chain
    }

    /**
     * At least [MIN_HEAD_SPEED_PX] px a frame over its first points. Only the
     * start: past the bounce, far away, a real ball crawls across the screen.
     */
    private fun movesLikeABall(chain: List<Link>): Boolean {
        val head = chain.take(HEAD_POINTS)
        val frames = max(1, head.last().frame - head.first().frame)
        return hypot(head.last().x - head.first().x, head.last().y - head.first().y) / frames >= MIN_HEAD_SPEED_PX
    }

    /** See the class doc: from the on-screen low point onward, the sharpest kink toward rising. */
    private fun bounceIndex(chain: List<Link>): Int? {
        val low = (1 until chain.size - 2).firstOrNull { j ->
            val y = chain[j].y
            y >= chain[j - 1].y && y > chain[j + 1].y && chain[j + 1].y >= chain[j + 2].y
        } ?: return null

        var best = low
        var sharpest = 0.0
        for (j in max(2, low - BOUNCE_SEARCH_BEFORE)..minOf(chain.size - 3, low + BOUNCE_SEARCH_AFTER)) {
            val before = (chain[j].y - chain[j - 2].y) / (chain[j].frame - chain[j - 2].frame)
            val after = (chain[j + 2].y - chain[j].y) / (chain[j + 2].frame - chain[j].frame)
            val kink = before - after
            if (kink > sharpest) {
                sharpest = kink
                best = j
            }
        }
        return if (sharpest >= MIN_BOUNCE_KINK_PX) best else low
    }

    // -------------------------------------------------------------- measurement

    private class Context(
        val frames: List<FrameBlobs>,
        val blobs: List<List<BallCandidate>>,
        val proposal: ServeProposal,
        val homography: Homography,
        val format: CourtFormat,
        val contactPosition: DoubleArray,
        val fps: Double,
        val headY: Double,
        val body: List<PixelRect>,
    )

    private class Evaluation(val rank: Int, val length: Int, val outcome: ServeOutcome) {
        fun betterThan(other: Evaluation) = rank > other.rank || (rank == other.rank && length > other.length)
    }

    private fun evaluate(chain: List<Link>, c: Context): Evaluation? {
        if (chain.size < MIN_CHAIN) return null
        // A serve is struck above the head; a groundstroke's flight starts lower.
        if (chain.first().y >= c.headY) return null
        val contactMs = contactTime(chain, c) ?: return null
        // Racket-up to contact was 0.2-0.6 s on every serve checked by eye.
        if (contactMs - c.proposal.racketUpMs !in 0..MAX_RACKET_UP_TO_CONTACT_MS) return null
        val bounceAt = bounceIndex(chain) ?: return null

        val bouncePixel = refineBounce(chain, bounceAt, c.frames)
        // A bounce the server's body hides can't be seen, so it can't be measured
        // or called: whatever the track did there, it wasn't the ball.
        if (c.body.any { it.contains(bouncePixel.x, bouncePixel.y) }) return null
        val bounce = c.homography.mapToCourt(PixelPoint(bouncePixel.x.toFloat(), bouncePixel.y.toFloat()))
        val netY = CourtDimensions.LENGTH_M / 2

        if (bounce.yMeters <= netY + NET_MARGIN_M && chain.size >= MIN_NET_FAULT_CHAIN) {
            return Evaluation(RANK_NET_FAULT, chain.size, ServeOutcome.NetFault(contactMs))
        }

        val dx = bounce.xMeters - c.contactPosition[0]
        val dy = bounce.yMeters - c.contactPosition[1]
        val distance = sqrt(dx * dx + dy * dy + c.contactPosition[2] * c.contactPosition[2])
        val bounceMs = bouncePixel.timeMs.toLong()
        val flightSeconds = (bouncePixel.timeMs - contactMs) / 1000.0
        if (flightSeconds <= 0) return Evaluation(RANK_IMPLAUSIBLE, chain.size, implausible(chain, "non-positive flight time"))
        val launch = launchSpeed(distance, flightSeconds)
        val kmh = launch * 3.6

        val courtWidth = CourtDimensions.widthFor(c.format)
        val offCourtSideways = bounce.xMeters < -SIDEWAYS_MARGIN_M || bounce.xMeters > courtWidth + SIDEWAYS_MARGIN_M
        if (flightSeconds !in MIN_FLIGHT_S..MAX_FLIGHT_S || kmh !in MIN_KMH..MAX_KMH ||
            bounce.yMeters <= netY + NET_MARGIN_M || offCourtSideways
        ) {
            return Evaluation(
                RANK_IMPLAUSIBLE,
                chain.size,
                implausible(chain, "flight %.2f s at %.0f km/h, bouncing %.1f m from the baseline".format(flightSeconds, kmh, bounce.yMeters)),
            )
        }

        val (errorX, errorY) = bouncePositionError(c.homography, bouncePixel)
        val call = ServiceLineCall.call(bounce, serverX = c.contactPosition[0], format = c.format, errorXMeters = errorX, errorYMeters = errorY)

        return Evaluation(
            RANK_MEASURED,
            chain.size,
            ServeOutcome.Measured(
                contactMs = contactMs,
                bounceMs = bounceMs,
                bounce = bounce,
                launchSpeedMetersPerSecond = launch,
                errorBandPercent = errorBandPercent(flightSeconds, c.fps),
                call = call,
            ),
        )
    }

    private class BouncePoint(val x: Double, val y: Double, val timeMs: Double)

    /**
     * The bounce between frames, per docs/ACCURACY.md's service-line method:
     * fit the track's last few points before the bounce and first few after as
     * straight lines in time, and take where their vertical positions meet. The
     * ball touches down between two frames almost every time; snapping to a
     * frame is up to half a frame's travel off — several centimetres at serve
     * speed. Falls back to the bounce frame's own point when either side has too
     * few points, or the lines meet outside the neighbouring frames.
     */
    private fun refineBounce(chain: List<Link>, at: Int, frames: List<FrameBlobs>): BouncePoint {
        val link = chain[at]
        val fallback = BouncePoint(link.x, link.y, frames[link.frame].timeMs.toDouble())
        val incoming = chain.subList(max(0, at - BOUNCE_FIT_POINTS), at)
        val outgoing = chain.subList(at + 1, minOf(chain.size, at + 1 + BOUNCE_FIT_POINTS))
        if (incoming.size < 2 || outgoing.size < 2) return fallback

        val origin = frames[link.frame].timeMs.toDouble()
        fun t(l: Link) = frames[l.frame].timeMs - origin
        val inX = fitLinear(incoming.map { t(it) }, incoming.map { it.x }) ?: return fallback
        val inY = fitLinear(incoming.map { t(it) }, incoming.map { it.y }) ?: return fallback
        val outX = fitLinear(outgoing.map { t(it) }, outgoing.map { it.x }) ?: return fallback
        val outY = fitLinear(outgoing.map { t(it) }, outgoing.map { it.y }) ?: return fallback
        if (abs(inY[1] - outY[1]) < 1e-9) return fallback

        val meet = (outY[0] - inY[0]) / (inY[1] - outY[1])
        if (meet < t(chain[at - 1]) || meet > t(chain[at + 1])) return fallback
        val x = (inX[0] + inX[1] * meet + outX[0] + outX[1] * meet) / 2
        return BouncePoint(x, inY[0] + inY[1] * meet, origin + meet)
    }

    /** Least-squares `a + b t`, or null if all [t] are equal. */
    private fun fitLinear(t: List<Double>, v: List<Double>): DoubleArray? {
        val n = t.size
        val meanT = t.sum() / n
        val meanV = v.sum() / n
        var stt = 0.0
        var stv = 0.0
        for (i in 0 until n) {
            stt += (t[i] - meanT) * (t[i] - meanT)
            stv += (t[i] - meanT) * (v[i] - meanV)
        }
        if (stt < 1e-12) return null
        val slope = stv / stt
        return doubleArrayOf(meanV - slope * meanT, slope)
    }

    /**
     * How far the bounce could be off, across (x) and along (y) the court: the
     * ball's pixel uncertainty — its centroid plus the calibration's corners —
     * scaled by how much court one pixel covers right there, plus what the
     * between-frames fit leaves. Far down the court from a behind-baseline
     * camera a pixel spans several times more court lengthwise than across.
     */
    private fun bouncePositionError(homography: Homography, at: BouncePoint): Pair<Double, Double> {
        val u = at.x.toFloat()
        val v = at.y.toFloat()
        val here = homography.mapToCourt(PixelPoint(u, v))
        val stepU = homography.mapToCourt(PixelPoint(u + 1, v))
        val stepV = homography.mapToCourt(PixelPoint(u, v + 1))
        val xPerPixel = hypot((stepU.xMeters - here.xMeters).toDouble(), (stepV.xMeters - here.xMeters).toDouble())
        val yPerPixel = hypot((stepU.yMeters - here.yMeters).toDouble(), (stepV.yMeters - here.yMeters).toDouble())
        fun combined(perPixel: Double) =
            sqrt((BOUNCE_PIXEL_ERROR * perPixel) * (BOUNCE_PIXEL_ERROR * perPixel) + BOUNCE_FIT_ERROR_M * BOUNCE_FIT_ERROR_M)
        return combined(xPerPixel) to combined(yPerPixel)
    }

    private fun implausible(chain: List<Link>, why: String) =
        ServeOutcome.NoFlight("Implausible flight (${chain.size}-point track): $why")

    /**
     * Midway between the toss's last visible frame and the flight's first
     * point. Null when there is no toss: no toss, no serve.
     *
     * The toss is a blob above the head at the end of a short track falling
     * nearly straight down — not merely *a* blob above the head: stray motion
     * there (tree leaves on real footage, random clutter in the tests) read as
     * the toss and made contact ~40 ms late, a 162 km/h serve reading 180.
     */
    private fun contactTime(chain: List<Link>, c: Context): Long? {
        val first = chain.first()
        for (frame in first.frame - 1 downTo max(TOSS_TRACK_FRAMES - 1, first.frame - CONTACT_LOOKBACK_FRAMES)) {
            if (c.blobs[frame].any { it.y < c.headY && abs(it.x - first.x) < CONTACT_NEAR_X_PX && endsFallingTrack(it, frame, c) }) {
                return (c.frames[frame].timeMs + c.frames[first.frame].timeMs) / 2
            }
        }
        return null
    }

    /** Whether [blob] in [frame] is preceded by blobs falling nearly vertically onto it, one per frame. */
    private fun endsFallingTrack(blob: BallCandidate, frame: Int, c: Context): Boolean {
        var current = blob
        for (back in 1 until TOSS_TRACK_FRAMES) {
            current = c.blobs[frame - back].firstOrNull { previous ->
                val dy = current.y - previous.y
                abs(current.x - previous.x) <= TOSS_MAX_DRIFT_PX && dy in TOSS_MIN_FALL_PX..TOSS_MAX_FALL_PX
            } ?: return false
        }
        return true
    }

    /**
     * Launch speed for a ball decelerated by quadratic drag alone over a
     * straight [distance] in [seconds]: `s(t) = ln(1 + k v0 t) / k`, solved for
     * `v0`. Gravity bends the path but barely changes its length over a serve.
     */
    internal fun launchSpeed(distance: Double, seconds: Double): Double =
        (exp(DRAG_PER_METER * distance) - 1) / (DRAG_PER_METER * seconds)

    /**
     * ±1 frame at each end of the flight, plus fixed allowances for the contact
     * position (assumed, not seen), the bounce position, and the drag constant,
     * combined in quadrature.
     */
    private fun errorBandPercent(flightSeconds: Double, fps: Double): Double {
        val timing = 2.0 / (fps * flightSeconds)
        return 100 * sqrt(timing * timing + CONTACT_POSITION_ERROR * CONTACT_POSITION_ERROR +
            BOUNCE_POSITION_ERROR * BOUNCE_POSITION_ERROR + DRAG_ERROR * DRAG_ERROR)
    }

    /**
     * Drops candidates that sit in the same spot across much of the window — a
     * swaying light pole, a flickering fence — which would otherwise seed and
     * extend junk chains. A ball never stays put for [STATIC_MIN_FRAMES] frames.
     */
    private fun withoutStaticNoise(frames: List<FrameBlobs>): List<List<BallCandidate>> {
        val counts = HashMap<Long, Int>()
        fun cell(x: Double, y: Double) = (x / STATIC_CELL_PX).toLong() shl 32 or ((y / STATIC_CELL_PX).toLong() and 0xffffffffL)
        for (frame in frames) {
            frame.blobs.map { cell(it.x, it.y) }.toSet().forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        val limit = max(STATIC_MIN_FRAMES, frames.size / 4)
        return frames.map { frame -> frame.blobs.filter { (counts[cell(it.x, it.y)] ?: 0) < limit } }
    }

    // ---------------------------------------------------------------- constants

    private const val BODY_MARGIN_PX = 40.0
    private const val MIN_NECK_PX = 20.0

    /** The crown sits ~1.5 nose-to-shoulder distances above the nose; a head is about as wide as that. */
    private const val HEAD_ABOVE_NOSE_PER_NECK = 1.5
    private const val HEAD_HALF_WIDTH_PER_NECK = 0.9

    private const val MIN_CHAIN = 12
    private const val MIN_NET_FAULT_CHAIN = 20
    private const val HEAD_POINTS = 15
    private const val MIN_HEAD_SPEED_PX = 3.0
    private const val MAX_MISSED_FRAMES = 3
    private const val GATE_BASE_PX = 6.0
    private const val GATE_PER_STEP_PX = 4.0
    private const val MIN_SEED_STEP_PX = 8.0
    private const val MAX_SEED_STEP_PX = 200.0
    private const val SEED_WINDOW_MS = 900L
    private const val SEED_MARGIN_X = 450.0

    /** On-screen, roughly: a real bounce keeps ~70% of vertical speed, foreshortened by the far-court view. */
    private const val BOUNCE_RESTITUTION = 0.6

    /** Change in on-screen vertical speed, px/frame, below which the low point itself stands in for the bounce. */
    private const val MIN_BOUNCE_KINK_PX = 2.0
    private const val BOUNCE_SEARCH_BEFORE = 2
    private const val BOUNCE_SEARCH_AFTER = 10
    private const val BOUNCE_FIT_POINTS = 3

    /**
     * The bounce's pixel uncertainty: ~1.5 px of blob centroid and ~2 px of
     * calibration corner error, in quadrature.
     */
    private const val BOUNCE_PIXEL_ERROR = 2.5

    /** What the between-frames bounce fit leaves on top of that. */
    private const val BOUNCE_FIT_ERROR_M = 0.03

    private const val MAX_RACKET_UP_TO_CONTACT_MS = 800L
    private const val CONTACT_LOOKBACK_FRAMES = 12
    private const val CONTACT_NEAR_X_PX = 260.0

    /** A toss's last frames before contact, on 60 fps footage: 3 frames, nearly vertical, falling 1-40 px each. */
    private const val TOSS_TRACK_FRAMES = 3
    private const val TOSS_MAX_DRIFT_PX = 20.0
    private const val TOSS_MIN_FALL_PX = 1.0
    private const val TOSS_MAX_FALL_PX = 40.0

    /** A serve that bounces further than this outside the sidelines isn't a serve track. */
    private const val SIDEWAYS_MARGIN_M = 1.0

    /** Contact is a little in front of the feet and about 1.5x a club player's height up. */
    private const val CONTACT_AHEAD_OF_FEET_M = 0.4
    private const val CONTACT_HEIGHT_M = 2.6

    private const val NET_MARGIN_M = 0.5
    private const val MIN_FLIGHT_S = 0.3
    private const val MAX_FLIGHT_S = 1.0
    private const val MIN_KMH = 40.0
    private const val MAX_KMH = 260.0

    /** `0.5 * rho * Cd * A / m` for a tennis ball: 1.21 kg/m³, Cd 0.55, 3.35 cm radius, 57 g. */
    private const val DRAG_PER_METER = 0.0202

    private const val CONTACT_POSITION_ERROR = 0.02
    private const val BOUNCE_POSITION_ERROR = 0.02
    private const val DRAG_ERROR = 0.03

    private const val STATIC_CELL_PX = 4.0
    private const val STATIC_MIN_FRAMES = 8

    private const val RANK_MEASURED = 3
    private const val RANK_NET_FAULT = 2
    private const val RANK_IMPLAUSIBLE = 1
}
