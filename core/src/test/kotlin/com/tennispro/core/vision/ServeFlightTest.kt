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
            val c = DoubleArray(3) { r1[it] * x + r2[it] * y + r3[it] * z + t[it] }
            return PixelPoint((focal * c[0] / c[2] + cx).toFloat(), (focal * c[1] / c[2] + cy).toFloat())
        }

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
    private fun scenario(velocity: DoubleArray, intoNet: Boolean = false, seed: Int = 1): Scenario {
        val random = Random(seed)
        val feet = doubleArrayOf(3.2, -0.3)
        val contact = doubleArrayOf(feet[0], feet[1] + 0.4, 2.6)
        val flightPoints = flight(contact, velocity, frames = (1.8 * fps).toInt(), intoNet = intoNet)

        val contactFrame = (0.5 * fps).toInt()
        val contactMs = (contactFrame * 1000 / fps).toLong()
        val frames = (0 until contactFrame + flightPoints.size).map { frame ->
            val blobs = mutableListOf<BallCandidate>()
            val sinceContact = frame - contactFrame
            val ball: PixelPoint? = when {
                sinceContact in -2..1 -> null // behind the racket
                sinceContact < 0 -> {
                    // The toss falling onto the contact point from a 3.4 m apex.
                    val s = -sinceContact / fps
                    camera.project(contact[0], contact[1], contact[2] + 0.5 * 9.81 * s * s).takeIf { s < 0.4 }
                }
                else -> flightPoints[sinceContact].let { camera.project(it[0], it[1], it[2]) }
            }
            if (ball != null && ball.x in 0f..1919f && ball.y in 0f..1079f) blobs += BallCandidate(ball.x.toDouble(), ball.y.toDouble(), 20)
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

    @Test
    fun `a slower second serve is measured too`() {
        val launch = 30.0 // 108 km/h
        val s = scenario(aimed(launch, targetX = 5.0, targetY = 16.0, downDegrees = -2.0), seed = 2)

        val outcome = ServeFlight.analyze(s.frames, s.proposal, homography, CourtFormat.SINGLES, fps)

        assertTrue("expected a measured serve, got ${describe(s, outcome)}", outcome is ServeOutcome.Measured)
        assertEquals(describe(s, outcome), launch * 3.6, (outcome as ServeOutcome.Measured).kmh, launch * 3.6 * 0.06)
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
        val noiseOnly = s.frames.map { frame -> FrameBlobs(frame.timeMs, frame.blobs.filter { it.pixelCount != 20 }) }

        val outcome = ServeFlight.analyze(noiseOnly, s.proposal, homography, CourtFormat.SINGLES, fps)

        assertTrue("expected no flight, got $outcome", outcome is ServeOutcome.NoFlight)
    }

    @Test
    fun `launch speed reduces to distance over time when drag is negligible`() {
        // Over 1 m the drag term is ~1%: v0 = (e^{kd} - 1) / (kT).
        assertEquals(10.1, ServeFlight.launchSpeed(1.0, 0.1), 0.05)
    }
}
