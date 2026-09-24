package com.tennispro.core.vision

import com.tennispro.core.court.CallVerdict
import com.tennispro.core.court.CalibrationPoints
import com.tennispro.core.court.CourtFormat
import com.tennispro.core.court.Homography
import com.tennispro.core.court.PixelPoint
import com.tennispro.core.court.solveHomography4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Serves simulated in 3D — gravity, quadratic drag, a bounce — and projected
 * through a camera built from the real 2026-09-08 court calibration, so the
 * launch speed [ServeFlight] should recover is known exactly.
 */
class ServeFlightTest {

    private val nearLeft = PixelPoint(-275.9f, 1087.1f)
    private val nearRight = PixelPoint(1677.6f, 990.5f)
    private val farLeft = PixelPoint(676.5f, 630.9f)
    private val farRight = PixelPoint(1064.4f, 614.8f)

    private val homography = Homography.fromCalibration(
        CalibrationPoints(CourtFormat.SINGLES, 1920, 1080, nearLeft, nearRight, farLeft, farRight),
    )!!

    /** A pinhole camera consistent with the calibration: ground points project exactly as the homography says. */
    private inner class Camera(private val focal: Double = 1400.0, private val cx: Double = 960.0, private val cy: Double = 540.0) {
        private val r1: DoubleArray
        private val r2: DoubleArray
        private val r3: DoubleArray
        private val t: DoubleArray

        init {
            val h = solveHomography4(
                doubleArrayOf(0.0, 0.0, 8.23, 0.0, 0.0, 23.77, 8.23, 23.77),
                doubleArrayOf(
                    nearLeft.x.toDouble(), nearLeft.y.toDouble(), nearRight.x.toDouble(), nearRight.y.toDouble(),
                    farLeft.x.toDouble(), farLeft.y.toDouble(), farRight.x.toDouble(), farRight.y.toDouble(),
                ),
            )!!
            fun column(c: Int) = doubleArrayOf((h[c] - cx * h[6 + c]) / focal, (h[3 + c] - cy * h[6 + c]) / focal, h[6 + c])
            val m1 = column(0)
            val m2 = column(1)
            val m3 = column(2)
            var scale = 1 / sqrt(norm(m1) * norm(m2))
            if (m3[2] * scale < 0) scale = -scale
            r1 = m1.map { it * scale }.toDoubleArray()
            r2 = m2.map { it * scale }.toDoubleArray()
            t = m3.map { it * scale }.toDoubleArray()
            r3 = doubleArrayOf(r1[1] * r2[2] - r1[2] * r2[1], r1[2] * r2[0] - r1[0] * r2[2], r1[0] * r2[1] - r1[1] * r2[0])
        }

        fun project(x: Double, y: Double, z: Double): PixelPoint {
            val c = cameraSpace(x, y, z)
            return PixelPoint((focal * c[0] / c[2] + cx).toFloat(), (focal * c[1] / c[2] + cy).toFloat())
        }

        /** The ball's area on screen at (x, y, z): a 6.7 cm disc at that depth. */
        fun ballPixels(x: Double, y: Double, z: Double): Int {
            val d = focal * 0.067 / cameraSpace(x, y, z)[2]
            return maxOf(3, (Math.PI / 4 * d * d).toInt())
        }

        private fun cameraSpace(x: Double, y: Double, z: Double) = DoubleArray(3) { r1[it] * x + r2[it] * y + r3[it] * z + t[it] }

        private fun norm(v: DoubleArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
    }

    private val camera = Camera()
    private val fps = 60.0

    /** Ball positions (x, y, z metres) every 1/fps s from contact, gravity + drag, bouncing (or hitting the net). */
    private fun flight(start: DoubleArray, velocity: DoubleArray, frames: Int, intoNet: Boolean = false): List<DoubleArray> {
        val k = 0.0202
        val p = start.copyOf()
        val v = velocity.copyOf()
        val out = mutableListOf<DoubleArray>()
        val dt = 0.001
        var t = 0.0
        var stoppedAtNet = false
        for (frame in 0 until frames) {
            val target = frame / fps
            while (t + dt <= target + 1e-9) {
                if (intoNet && !stoppedAtNet && p[1] >= 11.885 && p[2] < 0.914) {
                    stoppedAtNet = true
                    v[0] = 0.0; v[1] = -1.5; v[2] = 0.0
                }
                val speed = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                for (i in 0..2) {
                    val drag = -k * speed * v[i]
                    v[i] += (drag + if (i == 2) -9.81 else 0.0) * dt
                    p[i] += v[i] * dt
                }
                if (p[2] < 0 && v[2] < 0) {
                    p[2] = -p[2]
                    v[2] = -0.7 * v[2]
                }
                t += dt
            }
            out += p.copyOf()
        }
        return out
    }

    private data class Scenario(
        val frames: List<FrameBlobs>,
        val proposal: ServeProposal,
        val contactMs: Long,
        /** When and where the simulated ball first touched the ground (or null if it never did). */
        val bounceMs: Long?,
        val bounceAt: DoubleArray?,
    )

    /**
     * Frames from 0.5 s before contact to 1.8 s after: the falling toss above
     * the server's head, the racket hiding the ball for a few frames around
     * contact, then the flight — plus random noise blobs and a static
     * "light pole" that never moves.
     */
    private fun scenario(
        velocity: DoubleArray,
        intoNet: Boolean = false,
        seed: Int = 1,
        /**
         * Last frame after contact on which the ball is still invisible. The
         * racket hides it for a frame or two; on real 60 fps footage the ball
         * also comes off the strings too blurred and too large to pass
         * [MotionBlobs]' size filter, so the track starts several frames late.
         */
        lastBlindFrame: Int = 1,
        /** Scales the toss's blobs, to fake a ball too small to be the server's. */
        tossSizeFactor: Double = 1.0,
        /** No toss at all, as when it is too large for [MotionBlobs]. */
        hideToss: Boolean = false,
        /** Frames after contact on which the flight is lost, e.g. against a busy background. */
        flightGap: IntRange = IntRange.EMPTY,
    ): Scenario {
        val random = Random(seed)
        val feet = doubleArrayOf(3.2, -0.3)
        val contact = doubleArrayOf(feet[0], feet[1] + 0.4, 2.6)
        val flightPoints = flight(contact, velocity, frames = (1.8 * fps).toInt(), intoNet = intoNet)

        val contactFrame = (0.5 * fps).toInt()
        val contactMs = (contactFrame * 1000 / fps).toLong()
        val frames = (0 until contactFrame + flightPoints.size).map { frame ->
            val blobs = mutableListOf<BallCandidate>()
            val sinceContact = frame - contactFrame
            val ball: DoubleArray? = when {
                sinceContact in -2..lastBlindFrame -> null // behind the racket, then too blurred to pass as a ball
                sinceContact in flightGap -> null
                sinceContact < 0 && hideToss -> null
                sinceContact < 0 -> {
                    // The toss falling onto the contact point from a 3.4 m apex.
                    val s = -sinceContact / fps
                    doubleArrayOf(contact[0], contact[1], contact[2] + 0.5 * 9.81 * s * s).takeIf { s < 0.4 }
                }
                else -> flightPoints[sinceContact]
            }
            if (ball != null) {
                val at = camera.project(ball[0], ball[1], ball[2])
                val size = camera.ballPixels(ball[0], ball[1], ball[2]).let { if (sinceContact < 0) (it * tossSizeFactor).toInt() else it }
                if (at.x in 0f..1919f && at.y in 0f..1079f) blobs += BallCandidate(at.x.toDouble(), at.y.toDouble(), size)
            }
            repeat(25) { blobs += BallCandidate(random.nextDouble(0.0, 1920.0), random.nextDouble(550.0, 1080.0), 30) }
            blobs += BallCandidate(1810.0, 550.0, 25)
            FrameBlobs((frame * 1000 / fps).toLong(), blobs)
        }

        val feetPixel = camera.project(feet[0], feet[1], 0.0)
        val head = camera.project(feet[0], feet[1], 1.75)
        val top = camera.project(feet[0], feet[1], 2.1)
        val server = PoseKeypoints(
            nose = head,
            leftShoulder = PixelPoint(feetPixel.x - 25, head.y + 40),
            rightShoulder = PixelPoint(feetPixel.x + 25, head.y + 40),
            leftWrist = PixelPoint(feetPixel.x - 30, head.y + 200),
            rightWrist = PixelPoint(feetPixel.x + 30, top.y),
            leftAnkle = PixelPoint(feetPixel.x - 15, feetPixel.y),
            rightAnkle = PixelPoint(feetPixel.x + 15, feetPixel.y),
            box = PixelRect(feetPixel.x - 90.0, top.y.toDouble(), feetPixel.x + 90.0, feetPixel.y.toDouble()),
        )
        val bounceFrame = (1 until flightPoints.size - 1).firstOrNull {
            flightPoints[it][2] <= flightPoints[it - 1][2] && flightPoints[it][2] < flightPoints[it + 1][2]
        }
        return Scenario(
            frames,
            ServeProposal(contactMs - 800, contactMs - 400, server),
            contactMs,
            bounceMs = bounceFrame?.let { ((contactFrame + it) * 1000 / fps).toLong() },
            bounceAt = bounceFrame?.let { flightPoints[it] },
        )
    }

    private fun describe(s: Scenario, outcome: ServeOutcome): String {
        val truth = "true contact ${s.contactMs} ms, bounce ${s.bounceMs} ms at " +
            (s.bounceAt?.let { "(%.2f, %.2f)".format(it[0], it[1]) } ?: "none")
        return "$outcome — $truth"
    }

    /** Speed and direction aimed at a point on the far half, with a slight downward launch angle. */
    private fun aimed(speed: Double, targetX: Double, targetY: Double, downDegrees: Double): DoubleArray {
        val dx = targetX - 3.2
        val dy = targetY - 0.1
        val horizontal = hypot(dx, dy)
        val down = Math.toRadians(downDegrees)
        return doubleArrayOf(speed * Math.cos(down) * dx / horizontal, speed * Math.cos(down) * dy / horizontal, -speed * Math.sin(down))
    }

    @Test
    fun `recovers the launch speed and bounce of a first serve into the box`() {
        val launch = 45.0 // 162 km/h
        val s = scenario(aimed(launch, targetX = 5.5, targetY = 17.0, downDegrees = 5.0))

        val outcome = ServeFlight.analyze(s.frames, s.proposal, homography, CourtFormat.SINGLES, fps)

        assertTrue("expected a measured serve, got ${describe(s, outcome)}", outcome is ServeOutcome.Measured)
        outcome as ServeOutcome.Measured
        assertEquals(describe(s, outcome), launch * 3.6, outcome.kmh, launch * 3.6 * 0.06)
        // True bounce (5.62, 17.87): in the far right box, 42 cm inside the service line.
        assertTrue("call ${outcome.call} for a ball 42 cm inside the line", outcome.call.verdict != CallVerdict.OUT)
        assertTrue("margin ${outcome.call.marginMeters}", outcome.call.marginMeters in 0.25..0.6)
        assertTrue("contact ${outcome.contactMs} vs ${s.contactMs}", abs(outcome.contactMs - s.contactMs) <= 40)
        assertTrue(outcome.errorBandPercent in 5.0..15.0)
    }

    /**
     * The field bug this guards: on the 2026-09-11 recording the ball's first
     * frames after contact were dropped by [MotionBlobs]' size filter — off the
     * strings it is large and smeared — so the flight was first seen three or
     * four frames late. Taking contact as the midpoint of toss-last and
     * flight-first then put it ~50 ms late, shortening the flight and reading
     * every serve about 10% fast.
     */
    @Test
    fun `a serve whose first frames are too blurred to detect is still measured`() {
        val launch = 45.0 // 162 km/h
        val s = scenario(
            aimed(launch, targetX = 5.5, targetY = 17.0, downDegrees = 5.0),
            lastBlindFrame = 4,
        )

        val outcome = ServeFlight.analyze(s.frames, s.proposal, homography, CourtFormat.SINGLES, fps)

        assertTrue("expected a measured serve, got ${describe(s, outcome)}", outcome is ServeOutcome.Measured)
        outcome as ServeOutcome.Measured
        assertTrue("contact ${outcome.contactMs} vs ${s.contactMs}", abs(outcome.contactMs - s.contactMs) <= 25)
        // Was +2.9% on the midpoint rule this replaced; the tolerance is set to
        // catch a regression back toward it rather than to describe the limit.
        assertEquals(describe(s, outcome), launch * 3.6, outcome.kmh, launch * 3.6 * 0.03)
    }

    /**
     * The 2026-09-23 night recordings, filmed from a few metres behind the
     * server: the ball stayed too large for [MotionBlobs] for ~0.3 s after
     * contact, the track started mid-flight, and the flight's own earlier
     * frames — falling down the screen — were taken for the toss. Contact came
     * out 260-600 ms late and a 92 km/h serve read 126. Better to measure
     * nothing than that.
     */
    @Test
    fun `the flight's own earlier frames are not taken for the toss`() {
        val launch = 30.0 // 108 km/h
        val s = scenario(
            aimed(launch, targetX = 5.0, targetY = 16.0, downDegrees = -2.0),
            seed = 2,
            hideToss = true,
            flightGap = 12..16,
        )

        val outcome = ServeFlight.analyze(s.frames, s.proposal, homography, CourtFormat.SINGLES, fps)

        if (outcome is ServeOutcome.Measured) {
            assertTrue("contact ${outcome.contactMs} vs ${s.contactMs}: ${describe(s, outcome)}", abs(outcome.contactMs - s.contactMs) <= 40)
        }
    }

    /**
     * A slow, high second serve on 2026-09-23 was in the air 1.0 s and was
     * thrown out by a 1.0 s limit; the tracker then measured something else
     * at 254 km/h.
     */
    @Test
    fun `a slow serve in the air over a second is measured`() {
        val launch = 19.0 // 68 km/h
        val s = scenario(aimed(launch, targetX = 5.0, targetY = 17.0, downDegrees = -12.0), seed = 6)
        val flightSeconds = (s.bounceMs!! - s.contactMs) / 1000.0
        assertTrue("simulated flight $flightSeconds s should be over 1 s", flightSeconds > 1.0)

        val outcome = ServeFlight.analyze(s.frames, s.proposal, homography, CourtFormat.SINGLES, fps)

        assertTrue("expected a measured serve, got ${describe(s, outcome)}", outcome is ServeOutcome.Measured)
        assertEquals(describe(s, outcome), launch * 3.6, (outcome as ServeOutcome.Measured).kmh, launch * 3.6 * 0.06)
    }

    /**
     * A lob falling on the far court is a falling blob above the near
     * player's head, but a few pixels across: on 2026-09-23 it passed as the
     * toss and a forehand was reported as a 186 km/h serve.
     */
    @Test
    fun `a falling ball too small to be at the server is not the toss`() {
        val s = scenario(aimed(45.0, targetX = 5.5, targetY = 17.0, downDegrees = 5.0), tossSizeFactor = 0.1)

        val outcome = ServeFlight.analyze(s.frames, s.proposal, homography, CourtFormat.SINGLES, fps)

        assertTrue("expected no serve without a ball-sized toss, got $outcome", outcome !is ServeOutcome.Measured)
    }

    @Test
    fun `blob limits grow with the ball's size near the server and never shrink below the defaults`() {
        val s = scenario(aimed(45.0, targetX = 5.5, targetY = 17.0, downDegrees = 5.0))
        // This camera sees the ball ~16 px across at the server: ~200 px of area.
        val near = ServeFlight.blobLimits(s.proposal.server, homography)
        assertTrue("$near", near.maxPixels > MotionBlobs.DEFAULT_MAX_PIXELS && near.maxDimension > MotionBlobs.DEFAULT_MAX_DIMENSION)

        // The same view from four times as far: a ~4 px ball.
        fun shrink(p: PixelPoint) = PixelPoint(960 + (p.x - 960) / 4, 540 + (p.y - 540) / 4)
        val far = Homography.fromCalibration(
            CalibrationPoints(CourtFormat.SINGLES, 1920, 1080, shrink(nearLeft), shrink(nearRight), shrink(farLeft), shrink(farRight)),
        )!!
        val server = s.proposal.server.let {
            it.copy(leftAnkle = shrink(it.leftAnkle), rightAnkle = shrink(it.rightAnkle))
        }
        assertEquals(BallBlobLimits(MotionBlobs.DEFAULT_MAX_PIXELS, MotionBlobs.DEFAULT_MAX_DIMENSION), ServeFlight.blobLimits(server, far))
    }

    @Test
    fun `a slower second serve is measured too`() {
        val launch = 30.0 // 108 km/h
        val s = scenario(aimed(launch, targetX = 5.0, targetY = 16.0, downDegrees = -2.0), seed = 2)

        val outcome = ServeFlight.analyze(s.frames, s.proposal, homography, CourtFormat.SINGLES, fps)

        assertTrue("expected a measured serve, got ${describe(s, outcome)}", outcome is ServeOutcome.Measured)
        assertEquals(describe(s, outcome), launch * 3.6, (outcome as ServeOutcome.Measured).kmh, launch * 3.6 * 0.06)
    }

    /**
     * A serve cannot first bounce past the far baseline; the court ends there.
     * Filming a televised match produced a track that appeared to, and the app
     * reported it as a measured serve with a confident OUT call. Saying nothing
     * is the honest answer when the geometry is impossible.
     */
    @Test
    fun `a bounce past the far baseline is not measured`() {
        // Flat and fast enough to first land beyond the back of the court.
        val s = scenario(aimed(62.0, targetX = 4.5, targetY = 26.5, downDegrees = 0.5), seed = 5)

        // Without this the test could pass because the serve was rejected for some
        // other reason, or never bounced past the baseline at all.
        assertTrue(
            "simulated bounce ${s.bounceAt?.get(1)} should be past the 23.77 m baseline",
            (s.bounceAt?.get(1) ?: 0.0) > 23.77,
        )

        val outcome = ServeFlight.analyze(s.frames, s.proposal, homography, CourtFormat.SINGLES, fps)

        assertTrue(
            "a bounce past the baseline must not be measured, got ${describe(s, outcome)}",
            outcome !is ServeOutcome.Measured,
        )
    }

    @Test
    fun `a serve into the net is a net fault, with no speed claimed`() {
        val s = scenario(aimed(40.0, targetX = 5.5, targetY = 17.0, downDegrees = 9.0), intoNet = true, seed = 3)

        val outcome = ServeFlight.analyze(s.frames, s.proposal, homography, CourtFormat.SINGLES, fps)

        assertTrue("expected a net fault, got $outcome", outcome is ServeOutcome.NetFault)
    }

    @Test
    fun `noise alone is not a serve`() {
        val s = scenario(aimed(45.0, 5.5, 17.0, 5.0), seed = 4)
        val noiseOnly = s.frames.map { frame -> FrameBlobs(frame.timeMs, frame.blobs.filter { it.pixelCount == 30 || it.pixelCount == 25 }) }

        val outcome = ServeFlight.analyze(noiseOnly, s.proposal, homography, CourtFormat.SINGLES, fps)

        assertTrue("expected no flight, got $outcome", outcome is ServeOutcome.NoFlight)
    }

    @Test
    fun `launch speed reduces to distance over time when drag is negligible`() {
        // Over 1 m the drag term is ~1%: v0 = (e^{kd} - 1) / (kT).
        assertEquals(10.1, ServeFlight.launchSpeed(1.0, 0.1), 0.05)
    }
}
