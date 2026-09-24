package com.tennispro.core.vision

import com.tennispro.core.court.CourtDimensions
import com.tennispro.core.court.CourtFormat
import com.tennispro.core.court.CourtPoint
import com.tennispro.core.court.Homography
import com.tennispro.core.court.PixelPoint
import com.tennispro.core.court.ServiceCall
import com.tennispro.core.court.ServiceLineCall
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** The ball candidates [MotionBlobs] found in one frame. */
data class FrameBlobs(val timeMs: Long, val blobs: List<BallCandidate>)

/** [MotionBlobs]' size limits for one serve: the largest blob that can still be the ball. */
data class BallBlobLimits(val maxPixels: Int, val maxDimension: Int)

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
 * **Contact** is where the falling toss and the flight meet (see
 * [meetingTime]): the racket hides the ball in between. Which blob is the
 * toss, and which chain can follow it, is checked from several sides — see
 * [contactTime] — because on footage from close behind the server the flight
 * itself, junk along a floodlit net, and a lob on the far court all passed
 * a looser test.
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
        val ballPixels = ballDiameterPx(proposal.server, homography)
        val minTossPixels = TOSS_MIN_AREA_FRACTION * PI / 4 * ballPixels * ballPixels
        val tossBelowY = headY - TOSS_MIN_ABOVE_NOSE_M * ballPixels / BALL_DIAMETER_M
        val context = Context(frames, blobs, proposal, homography, format, contactPosition, fps, headY, body, ballPixels, minTossPixels, tossBelowY)

        // Blobs already part of a full-length chain don't seed another: the same
        // flight would otherwise be rebuilt from every one of its points. Only a
        // chain that came out as a serve or a net fault, though: on 2026-09-23
        // tracks that began on junk or the racket and then joined the real flight
        // were rejected, or judged implausible, and marking their points kept the
        // flight from ever being tried on its own.
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
                    if (!movesLikeABall(chain)) return@forEachIndexed
                    val evaluation = evaluate(chain, context) ?: return@forEachIndexed
                    if (evaluation.rank > RANK_IMPLAUSIBLE) chain.forEach { used += key(it.frame, it.index) }
                    if (best == null || evaluation.betterThan(best!!)) best = evaluation
                }
            }
        }

        return best?.outcome ?: ServeOutcome.NoFlight("No ball flight found after the swing")
    }

    /**
     * How large the ball can look near the server, for [MotionBlobs].
     *
     * The fixed 150 px / 30 px limits were set on footage from a fence mount,
     * where the ball is a few pixels across. On 2026-09-23 the phone stood a
     * few metres behind the server: the toss measured 360-430 px and the ball
     * just off the strings 400-600 px and up to 45 px long, so neither passed
     * until ~0.3 s into the flight. The tracker then started mid-flight and
     * took the ball's own earlier path for the toss (see [contactTime]):
     * contact 260-600 ms late, a 92 km/h serve read as 126 and a slow 1 s
     * serve as 254.
     *
     * The ball's size near the server follows from the calibration: 6.7 cm
     * times the court's scale where the server stands. The limits allow for
     * motion blur on top, and never go below the defaults.
     */
    fun blobLimits(server: PoseKeypoints, homography: Homography): BallBlobLimits {
        val d = ballDiameterPx(server, homography)
        return BallBlobLimits(
            maxPixels = max(MotionBlobs.DEFAULT_MAX_PIXELS, (BLOB_AREA_PER_DIAMETER_SQ * d * d).roundToInt()),
            maxDimension = max(MotionBlobs.DEFAULT_MAX_DIMENSION, (BLOB_LENGTH_PER_DIAMETER * d).roundToInt()),
        )
    }

    /**
     * The ball's diameter in pixels where the server stands: the court's
     * pixels-per-metre across the court at their feet. Contact is higher and a
     * little further away, so this errs large, which is the safe side for a
     * size limit. Bounded so a wild pose can't make it absurd.
     */
    internal fun ballDiameterPx(server: PoseKeypoints, homography: Homography): Double {
        val feet = homography.mapToCourt(server.feet)
        val left = homography.mapToPixel(CourtPoint(feet.xMeters - 0.5f, feet.yMeters))
        val right = homography.mapToPixel(CourtPoint(feet.xMeters + 0.5f, feet.yMeters))
        val perMeter = hypot((right.x - left.x).toDouble(), (right.y - left.y).toDouble())
        val d = BALL_DIAMETER_M * perMeter
        return if (d.isFinite()) d.coerceIn(MIN_BALL_DIAMETER_PX, MAX_BALL_DIAMETER_PX) else MIN_BALL_DIAMETER_PX
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

        /**
         * Index of the candidate within [radius] of either point that best
         * continues a ball last seen at [lastPixels] — nearest, with a change of
         * size counted against it — or -1. Candidates under a fifth of that size
         * are specks, not the ball.
         */
        fun nearest(x1: Double, y1: Double, x2: Double, y2: Double, radius: Double, lastPixels: Int): Int {
            val minPixels = (MIN_SIZE_KEPT * lastPixels).toInt()
            var best = -1
            var bestCost = Double.MAX_VALUE
            for ((x, y) in listOf(x1 to y1, x2 to y2)) {
                val cx0 = ((x - radius) / CELL_PX).toInt()
                val cx1 = ((x + radius) / CELL_PX).toInt()
                val cy0 = ((y - radius) / CELL_PX).toInt()
                val cy1 = ((y + radius) / CELL_PX).toInt()
                for (cx in cx0..cx1) for (cy in cy0..cy1) {
                    val bucket = cells[cx.toLong() shl 32 or (cy.toLong() and 0xffffffffL)] ?: continue
                    for (i in bucket) {
                        if (blobs[i].pixelCount < minPixels) continue
                        val d = hypot(blobs[i].x - x, blobs[i].y - y)
                        if (d > radius) continue
                        val cost = d + SIZE_CHANGE_COST_PX * abs(ln(blobs[i].pixelCount.toDouble() / lastPixels))
                        if (cost < bestCost) {
                            bestCost = cost
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
            // The ball shrinks gradually as it flies away; a speck or fragment beside
            // it is not the ball. On 2026-09-23, against a floodlit fence, a 3 px
            // speck and then a 33 px fragment of the split ball, each nearer the
            // prediction than the ~130 px ball, took the track off it mid-flight.
            val lastPixels = grids[last.frame].blobs[last.index].pixelCount
            // Wider for a ball close to the camera: its blob's centre wanders by a
            // good part of its width as the smear breaks up and joins, and the fixed
            // 6 px gate, set on distant balls, lost a 16 px ball 0.3 s into its flight.
            val gate = GATE_BASE_PX + GATE_PER_STEP_PX * steps + GATE_PER_BALL_WIDTH * sqrt(4 / PI * lastPixels)

            val grid = grids[frame]
            val next = grid.nearest(px, py, bouncedX, bouncedY, gate, lastPixels)
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

    /**
     * See the class doc: from the on-screen low point onward, the sharpest kink
     * toward rising. Only from [from] on — no serve lands straight off the
     * racket, and on 2026-09-23 a streak just off the strings, then a dip on
     * screen, was taken for the bounce and a serve called a net fault.
     *
     * The low point must be followed by the ball climbing for
     * [BOUNCE_RISE_POINTS] points: a bounce sends it up for many frames. On
     * 2026-09-23 a serve clipped the net tape and dropped; the track lost it
     * there and picked up two stray points higher up, and that kink was read
     * as a bounce near the far service line — "180 km/h OUT" for a net cord.
     */
    private fun bounceIndex(chain: List<Link>, from: Int): Int? {
        val low = (max(1, from) until chain.size - BOUNCE_RISE_POINTS).firstOrNull { j ->
            chain[j].y >= chain[j - 1].y && chain[j + 1].y < chain[j].y &&
                (2..BOUNCE_RISE_POINTS).all { k -> chain[j + k].y <= chain[j + k - 1].y }
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
        /** The ball's diameter near the server. */
        val ballPixels: Double,
        /** Smallest blob that can be the tossed ball, this close to the camera. */
        val minTossPixels: Double,
        /**
         * A toss is last seen above this line. "Above the head" alone let junk
         * in a floodlit band just over a close server's head pass as the toss
         * on 2026-09-23.
         */
        val tossBelowY: Double,
    )

    private class Evaluation(val rank: Int, val length: Int, val outcome: ServeOutcome) {
        fun betterThan(other: Evaluation) = rank > other.rank || (rank == other.rank && length > other.length)
    }

    private fun evaluate(chain: List<Link>, c: Context): Evaluation? {
        if (chain.size < MIN_CHAIN) return null
        // A serve is struck above the head; a groundstroke's flight starts lower.
        if (chain.first().y >= c.headY) return null
        // Starting by falling straight down, it starts on the toss: on 2026-09-23 such
        // a chain ran on into the flight and put contact 100 ms early, reading a
        // 113 km/h serve as 98. The chain that starts at contact is tried on its own.
        if (fallsLikeToss(chain[0].x, chain[0].y, chain[1].x, chain[1].y, (chain[1].frame - chain[0].frame).toDouble())) return null
        // Just off the racket the ball is still about as close to the camera as the
        // toss was, so as large. Junk along a floodlit net, and fragments of a
        // swinging racket, chained into "flights" on 2026-09-23 without ever
        // being ball-sized.
        if (chain.take(BALL_SIZED_HEAD_POINTS).any { c.blobs[it.frame][it.index].pixelCount < c.minTossPixels }) return null
        val contactMs = contactTime(chain, c) ?: return null
        // Racket-up to contact was 0.2-0.6 s on every serve checked by eye.
        if (contactMs - c.proposal.racketUpMs !in 0..MAX_RACKET_UP_TO_CONTACT_MS) return null
        val landable = chain.indexOfFirst { c.frames[it.frame].timeMs - contactMs >= MIN_BOUNCE_AFTER_CONTACT_MS }
        if (landable < 0) return null
        val bounceAt = bounceIndex(chain, from = landable) ?: return null

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

        val (errorX, errorY) = bouncePositionError(c.homography, bouncePixel)

        val courtWidth = CourtDimensions.widthFor(c.format)
        val offCourtSideways = bounce.xMeters < -SIDEWAYS_MARGIN_M || bounce.xMeters > courtWidth + SIDEWAYS_MARGIN_M

        /*
         * A serve's first bounce cannot be past the far baseline — the court ends
         * there. When one appears to be, the court model or the track is wrong,
         * and both the speed and the line call computed from it are worthless.
         * Filming a televised match on 2026-09-21 produced exactly this: a bounce
         * placed 1.6 m beyond the baseline, reported as a confident OUT call with
         * a 0.33 m error band, from a serve that had landed in the box.
         *
         * Judged against the bounce's own uncertainty, which grows with distance
         * from the camera, so a genuinely deep serve measured loosely is kept.
         */
        val offCourtLong = bounce.yMeters - errorY > CourtDimensions.LENGTH_M

        if (flightSeconds !in MIN_FLIGHT_S..MAX_FLIGHT_S || kmh !in MIN_KMH..MAX_KMH ||
            bounce.yMeters <= netY + NET_MARGIN_M || offCourtSideways || offCourtLong
        ) {
            return Evaluation(
                RANK_IMPLAUSIBLE,
                chain.size,
                implausible(chain, "flight %.2f s at %.0f km/h, bouncing %.1f m from the baseline".format(flightSeconds, kmh, bounce.yMeters)),
            )
        }

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
     * When the racket met the toss that [chain] flew off, or null when there
     * is no such toss: no toss, no serve.
     *
     * The toss is a blob well above the head at the end of a short track
     * falling straight down — not merely *a* blob above the head: stray motion
     * there (tree leaves on real footage, random clutter in the tests) read as
     * the toss and made contact ~40 ms late, a 162 km/h serve reading 180. And
     * it must belong to this chain: not the chain's own earlier path, not
     * still falling when the chain starts, and ending where the chain begins.
     * Contact is where the two meet ([meetingTime]), else midway between them.
     */
    private fun contactTime(chain: List<Link>, c: Context): Long? {
        val first = chain.first()
        for (frame in first.frame - 1 downTo max(TOSS_TRACK_FRAMES - 1, first.frame - CONTACT_LOOKBACK_FRAMES)) {
            for (blob in c.blobs[frame]) {
                if (blob.y >= c.tossBelowY || abs(blob.x - first.x) >= CONTACT_NEAR_X_PX) continue
                val toss = fallingTrackEndingAt(blob, frame, c) ?: continue
                if (movesWithFlight(toss, chain)) continue
                // Still falling when this "flight" starts: the ball hasn't been hit yet.
                if (keepsFalling(blob, frame, first, c)) return null
                if (!startsWhereTossEnds(blob, frame, chain, c)) continue
                val midpoint = (c.frames[frame].timeMs + c.frames[first.frame].timeMs) / 2
                return meetingTime(toss, chain, c) ?: midpoint
            }
        }
        return null
    }

    /**
     * Where the falling toss and the rising flight would have been in the same
     * place: the racket was there, so that is contact.
     *
     * The midpoint of the last toss frame and the first flight frame only holds
     * while both are seen close to contact. Off the strings the ball is large
     * and smeared, and [MotionBlobs]' size filter drops it for several frames,
     * so the flight is first seen late while the toss is not — and the midpoint
     * slides late with it, shortening the flight and inflating the speed. On
     * simulated serves this was +2.9% at three blind frames and +6.5% at six.
     *
     * Both sides are extrapolated as constant acceleration on screen — gravity
     * plus perspective, the same model the chaining already uses — and the time
     * between them that brings the two closest together is contact. A fit that
     * leaves them far apart is not trustworthy, so the caller keeps the
     * midpoint in that case.
     */
    private fun meetingTime(toss: List<Link>, chain: List<Link>, c: Context): Long? {
        val flight = chain.take(FLIGHT_FIT_POINTS)
        if (toss.size < 2 || flight.size < 2) return null

        // Relative to the flight's first frame, in seconds: keeps the fit well
        // conditioned, since absolute timestamps are large.
        val origin = c.frames[flight.first().frame].timeMs
        fun times(links: List<Link>) = DoubleArray(links.size) { (c.frames[links[it].frame].timeMs - origin) / 1000.0 }

        val tossT = times(toss)
        val flightT = times(flight)
        val tossX = fit(tossT, DoubleArray(toss.size) { toss[it].x }) ?: return null
        val tossY = fit(tossT, DoubleArray(toss.size) { toss[it].y }) ?: return null
        val flightX = fit(flightT, DoubleArray(flight.size) { flight[it].x }) ?: return null
        val flightY = fit(flightT, DoubleArray(flight.size) { flight[it].y }) ?: return null

        // Solved on the vertical axis alone. The toss is falling and the flight,
        // heading away from the camera, climbs the screen through perspective, so
        // the two y-curves cross cleanly. Their 2D separation, by contrast, is
        // nearly flat around contact: the paths are close for several frames
        // either side, and the nearest point slid ~10 ms early on every serve.
        val from = tossT.last()
        var bestT = Double.NaN
        var previous = at(flightY, from) - at(tossY, from)
        var step = from + MEETING_STEP_S
        while (step <= 0.0) {
            val gap = at(flightY, step) - at(tossY, step)
            if (gap == 0.0 || (gap < 0) != (previous < 0)) {
                bestT = step
                break
            }
            previous = gap
            step += MEETING_STEP_S
        }
        if (bestT.isNaN()) return null

        // A crossing with the ball somewhere else horizontally is not contact.
        val bestDistance = abs(at(flightX, bestT) - at(tossX, bestT))
        if (bestDistance > MEETING_MAX_GAP_PX) return null
        return origin + (bestT * 1000).roundToLong()
    }

    /** Least-squares `v(t) = c0 + c1 t + c2 t²`, dropping to a line when given only two points. */
    private fun fit(t: DoubleArray, v: DoubleArray): DoubleArray? {
        val degree = if (t.size >= 3) 2 else 1
        val n = degree + 1
        val a = Array(n) { DoubleArray(n + 1) }
        for (row in 0 until n) {
            for (col in 0 until n) a[row][col] = t.indices.sumOf { pow(t[it], row + col) }
            a[row][n] = t.indices.sumOf { pow(t[it], row) * v[it] }
        }
        for (col in 0 until n) {
            val pivot = (col until n).maxByOrNull { abs(a[it][col]) } ?: return null
            if (abs(a[pivot][col]) < 1e-12) return null
            val swap = a[col]; a[col] = a[pivot]; a[pivot] = swap
            for (row in 0 until n) {
                if (row == col) continue
                val factor = a[row][col] / a[col][col]
                for (k in col..n) a[row][k] -= factor * a[col][k]
            }
        }
        return DoubleArray(3) { if (it < n) a[it][n] / a[it][it] else 0.0 }
    }

    private fun at(coefficients: DoubleArray, t: Double) =
        coefficients[0] + coefficients[1] * t + coefficients[2] * t * t

    private fun pow(base: Double, exponent: Int): Double {
        var result = 1.0
        repeat(exponent) { result *= base }
        return result
    }

    /**
     * The blobs falling nearly vertically onto [blob], one per frame, oldest
     * first — or null if there is no such track. Not merely *a* blob above the
     * head: stray motion there (tree leaves on real footage, random clutter in
     * the tests) read as the toss and made contact ~40 ms late, a 162 km/h
     * serve reading 180.
     *
     * *Nearly* vertical is relative to the fall, not a fixed 20 px: on
     * 2026-09-23 the ball's own flight, descending the screen at 5-8 px a
     * frame sideways and down, passed the fixed limit and was taken for the
     * toss. A toss drifts a pixel or two. And ball-sized for this distance
     * from the camera: a lob falling on the far court, a few pixels across,
     * passed as the near server's toss and turned a forehand into a
     * "186 km/h serve".
     */
    private fun fallingTrackEndingAt(blob: BallCandidate, frame: Int, c: Context): List<Link>? {
        if (blob.pixelCount < c.minTossPixels) return null
        val track = mutableListOf(Link(frame, 0, blob.x, blob.y))
        var current = blob
        for (back in 1 until TOSS_TRACK_FRAMES) {
            current = c.blobs[frame - back].firstOrNull { previous ->
                val dy = current.y - previous.y
                dy in TOSS_MIN_FALL_PX..TOSS_MAX_FALL_PX &&
                    abs(current.x - previous.x) <= max(TOSS_MIN_DRIFT_PX, TOSS_DRIFT_PER_FALL * dy) &&
                    previous.pixelCount >= c.minTossPixels
            } ?: return null
            track += Link(frame - back, 0, current.x, current.y)
        }
        return track.reversed()
    }

    /** A step from (x0, y0) to (x1, y1) over [frames] that falls nearly straight down, as a toss does. */
    private fun fallsLikeToss(x0: Double, y0: Double, x1: Double, y1: Double, frames: Double): Boolean {
        val dy = (y1 - y0) / frames
        return dy in TOSS_MIN_FALL_PX..TOSS_MAX_FALL_PX && abs(x1 - x0) / frames <= max(TOSS_MIN_DRIFT_PX, TOSS_DRIFT_PER_FALL * dy)
    }

    /**
     * Whether the toss ending at [blob] in [frame] carries on falling through
     * the frame the flight starts in, beside it: then it was never hit before
     * that frame, and nothing that starts there is the serve. The flight's own
     * first point doesn't count — just off the strings it can sit right below
     * the toss. On the 2026-09-23 night recording, junk tracks along the
     * floodlit net started 0.1 s before contact, borrowed the toss above them
     * — still in the air — and came out as a 190 km/h serve.
     */
    private fun keepsFalling(blob: BallCandidate, frame: Int, flightStart: Link, c: Context): Boolean {
        var current = blob
        for (next in frame + 1..flightStart.frame) {
            current = c.blobs[next].filterIndexed { i, _ -> next != flightStart.frame || i != flightStart.index }.firstOrNull { following ->
                val dy = following.y - current.y
                dy in TOSS_MIN_FALL_PX..TOSS_MAX_FALL_PX &&
                    abs(following.x - current.x) <= max(TOSS_MIN_DRIFT_PX, TOSS_DRIFT_PER_FALL * dy) &&
                    following.pixelCount >= c.minTossPixels
            } ?: return false
        }
        return true
    }

    /**
     * Whether the flight's first point is where the ball could be after
     * leaving the racket at the toss's last point, [frames] later: the racket
     * meets the toss, so the flight starts there. Allows the flight's own
     * speed for the frames between, and a few ball widths for the racket's
     * reach. Junk along a floodlit net on 2026-09-23 was taking a toss 400 px
     * away as its own.
     */
    private fun startsWhereTossEnds(tossEnd: BallCandidate, frame: Int, chain: List<Link>, c: Context): Boolean {
        val first = chain[0]
        val step = hypot(chain[1].x - first.x, chain[1].y - first.y) / (chain[1].frame - first.frame)
        val frames = first.frame - frame
        val reach = frames * max(step, MIN_FLIGHT_STEP_PX) * FLIGHT_STEP_SLACK + CONTACT_REACH_BALLS * c.ballPixels
        return hypot(first.x - tossEnd.x, first.y - tossEnd.y) <= reach
    }

    /**
     * Whether [toss] is the flight's own earlier path rather than a toss: a
     * blob moving with the same velocity as the flight's first step. A real
     * toss falls onto the racket and the ball leaves in another direction.
     */
    private fun movesWithFlight(toss: List<Link>, chain: List<Link>): Boolean {
        val tossVx = (toss.last().x - toss[toss.size - 2].x) / (toss.last().frame - toss[toss.size - 2].frame)
        val tossVy = (toss.last().y - toss[toss.size - 2].y) / (toss.last().frame - toss[toss.size - 2].frame)
        val flightVx = (chain[1].x - chain[0].x) / (chain[1].frame - chain[0].frame)
        val flightVy = (chain[1].y - chain[0].y) / (chain[1].frame - chain[0].frame)
        val difference = hypot(tossVx - flightVx, tossVy - flightVy)
        return difference <= SAME_BALL_VELOCITY_PX + SAME_BALL_VELOCITY_FRACTION * hypot(flightVx, flightVy)
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
    /**
     * A serve hit straight away from the camera hangs at the top of its
     * on-screen arc, moving a pixel or two a frame, and frame differencing
     * loses it for ~5 frames (2026-09-23).
     */
    private const val MAX_MISSED_FRAMES = 6
    private const val GATE_BASE_PX = 6.0
    private const val GATE_PER_STEP_PX = 4.0
    private const val GATE_PER_BALL_WIDTH = 0.5
    /** A serve hit straight away from the camera moves only ~8 px a frame on screen just off the racket. */
    private const val MIN_SEED_STEP_PX = 4.0
    private const val MAX_SEED_STEP_PX = 200.0
    private const val SEED_WINDOW_MS = 900L
    private const val SEED_MARGIN_X = 450.0

    /** Of the previous point's size: the least the next point on a track can be. */
    private const val MIN_SIZE_KEPT = 0.2

    /** Pixels of distance a doubling or halving of size counts as, when picking the next point. */
    private const val SIZE_CHANGE_COST_PX = 8.0

    /** On-screen, roughly: a real bounce keeps ~70% of vertical speed, foreshortened by the far-court view. */
    private const val BOUNCE_RESTITUTION = 0.6

    /** Change in on-screen vertical speed, px/frame, below which the low point itself stands in for the bounce. */
    private const val MIN_BOUNCE_KINK_PX = 2.0
    private const val BOUNCE_SEARCH_BEFORE = 2
    private const val BOUNCE_SEARCH_AFTER = 10
    private const val BOUNCE_FIT_POINTS = 3

    /** Points after the on-screen low point that must keep climbing for it to be a bounce. */
    private const val BOUNCE_RISE_POINTS = 4

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

    /** Flight points used to extrapolate back to contact: enough to fit a curve, few enough to stay near contact. */
    private const val FLIGHT_FIT_POINTS = 4

    /** Resolution of the search for the moment toss and flight meet. Finer than a frame, which is the point. */
    private const val MEETING_STEP_S = 0.001

    /** How close the two extrapolations must come before the meeting point is believed, in pixels. */
    private const val MEETING_MAX_GAP_PX = 120.0

    /**
     * A toss's last frames before contact, on 60 fps footage: 3 frames, falling
     * 1-40 px each, drifting sideways at most a third of the fall (or a few
     * pixels of centroid jitter near the apex).
     */
    private const val TOSS_TRACK_FRAMES = 3
    private const val TOSS_MIN_DRIFT_PX = 4.0
    private const val TOSS_DRIFT_PER_FALL = 0.35
    private const val TOSS_MIN_FALL_PX = 1.0
    private const val TOSS_MAX_FALL_PX = 40.0

    /** Of a whole ball's area at the server's distance: the toss measured 1.2-1.5x on 2026-09-23, a far-court lob under 0.2x. */
    private const val TOSS_MIN_AREA_FRACTION = 0.25

    /** The flight may have slowed from, or first been seen slower than, its speed off the racket. */
    private const val MIN_FLIGHT_STEP_PX = 20.0
    private const val FLIGHT_STEP_SLACK = 1.5

    /** Ball widths between the toss's last sighting and where the racket sends it off. */
    private const val CONTACT_REACH_BALLS = 5.0

    /** Velocities, px a frame, closer than this are one ball moving on, not a toss and a flight. */
    private const val SAME_BALL_VELOCITY_PX = 4.0
    private const val SAME_BALL_VELOCITY_FRACTION = 0.25

    /** Contact is about a metre above the nose; a toss last seen lower than half that is something else. */
    private const val TOSS_MIN_ABOVE_NOSE_M = 0.5

    /** The flight's first points, which must be the size of the toss. */
    private const val BALL_SIZED_HEAD_POINTS = 3

    private const val BALL_DIAMETER_M = 0.067
    private const val MIN_BALL_DIAMETER_PX = 2.0
    private const val MAX_BALL_DIAMETER_PX = 60.0

    /**
     * Blob limits per ball diameter. Just off the strings the ball is a streak:
     * on 2026-09-23 one flying mostly away measured 600 px (1.6 d²) and 45 px
     * long (2.3 d), but one crossing the view at 40-70 px a frame was longer
     * than 2.6 d until 0.1 s into the flight, and the serve was lost.
     */
    private const val BLOB_AREA_PER_DIAMETER_SQ = 5.0
    private const val BLOB_LENGTH_PER_DIAMETER = 5.0

    /** A serve that bounces further than this outside the sidelines isn't a serve track. */
    private const val SIDEWAYS_MARGIN_M = 1.0

    /** Contact is a little in front of the feet and about 1.5x a club player's height up. */
    private const val CONTACT_AHEAD_OF_FEET_M = 0.4
    private const val CONTACT_HEIGHT_M = 2.6

    private const val NET_MARGIN_M = 0.5
    private const val MIN_FLIGHT_S = 0.3

    /** Earliest a bounce is looked for, a little inside [MIN_FLIGHT_S] so a fast serve's bounce is still found and judged. */
    private const val MIN_BOUNCE_AFTER_CONTACT_MS = 250L
    /** A slow, high club second serve took 1.0 s on 2026-09-23 and was thrown out at the old 1.0 s limit. */
    private const val MAX_FLIGHT_S = 1.4
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
