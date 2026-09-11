package com.tennispro.core.vision

import com.tennispro.core.court.CalibrationPoints
import com.tennispro.core.court.CourtFormat
import com.tennispro.core.court.Homography
import com.tennispro.core.court.PixelPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic pose sequences at 10 samples/s on the real 2026-09-08 camera
 * framing, one per behaviour the rule was tuned to accept or reject.
 */
class ServeProposalsTest {

    private val homography = Homography.fromCalibration(
        CalibrationPoints(
            format = CourtFormat.SINGLES,
            frameWidth = 1920,
            frameHeight = 1080,
            nearLeft = PixelPoint(-275.9f, 1087.1f),
            nearRight = PixelPoint(1677.6f, 990.5f),
            farLeft = PixelPoint(676.5f, 630.9f),
            farRight = PixelPoint(1064.4f, 614.8f),
        ),
    )!!

    private enum class Arm { DOWN, LEFT_UP, RIGHT_UP, BOTH_UP }

    /** A person at [feetX], [feetY] (pixels) about 480 px tall, facing away or toward the camera. */
    private fun person(arm: Arm, feetX: Float = 480f, feetY: Float = 1060f, facingAway: Boolean = true): PoseKeypoints {
        val top = feetY - 480f
        val noseY = top + 40f
        val up = top - 60f
        val down = top + 260f
        val leftX = if (facingAway) feetX - 30f else feetX + 30f
        val rightX = if (facingAway) feetX + 30f else feetX - 30f
        return PoseKeypoints(
            nose = PixelPoint(feetX, noseY),
            leftShoulder = PixelPoint(leftX, top + 90f),
            rightShoulder = PixelPoint(rightX, top + 90f),
            leftWrist = PixelPoint(leftX, if (arm == Arm.LEFT_UP || arm == Arm.BOTH_UP) up else down),
            rightWrist = PixelPoint(rightX, if (arm == Arm.RIGHT_UP || arm == Arm.BOTH_UP) up else down),
            leftAnkle = PixelPoint(feetX - 20f, feetY),
            rightAnkle = PixelPoint(feetX + 20f, feetY - 5f),
            box = PixelRect(feetX - 90.0, up.toDouble(), feetX + 90.0, feetY.toDouble()),
        )
    }

    /** One sample per 100 ms; each segment is (duration in samples, pose builder). */
    private fun sequence(vararg segments: Pair<Int, (Int) -> PoseKeypoints?>): List<PoseSample> {
        val samples = mutableListOf<PoseSample>()
        for ((count, build) in segments) {
            repeat(count) {
                val t = samples.size * 100L
                samples += PoseSample(t, listOfNotNull(build(samples.size)))
            }
        }
        return samples
    }

    @Test
    fun `a toss then the hitting arm rising, standing still with back to camera, is a serve`() {
        val samples = sequence(
            15 to { _ -> person(Arm.DOWN) },
            8 to { _ -> person(Arm.LEFT_UP) },
            3 to { _ -> person(Arm.RIGHT_UP) },
            15 to { _ -> person(Arm.DOWN) },
        )
        val proposals = ServeProposals.find(samples, homography, 1920, 1080)

        assertEquals(1, proposals.size)
        assertEquals(1500L, proposals[0].tossStartMs)
        assertEquals(2300L, proposals[0].racketUpMs)
    }

    @Test
    fun `a left-hander's serve, tossing with the right arm, is a serve too`() {
        val samples = sequence(
            15 to { _ -> person(Arm.DOWN) },
            8 to { _ -> person(Arm.RIGHT_UP) },
            3 to { _ -> person(Arm.LEFT_UP) },
            15 to { _ -> person(Arm.DOWN) },
        )
        assertEquals(1, ServeProposals.find(samples, homography, 1920, 1080).size)
    }

    @Test
    fun `walking toward the camera with one arm up is not a serve`() {
        val samples = sequence(
            15 to { i -> person(Arm.DOWN, feetY = 900f + i * 6f, facingAway = false) },
            12 to { i -> person(Arm.RIGHT_UP, feetY = 900f + i * 6f, facingAway = false) },
            15 to { i -> person(Arm.DOWN, feetY = 900f + i * 6f, facingAway = false) },
        )
        assertTrue(ServeProposals.find(samples, homography, 1920, 1080).isEmpty())
    }

    @Test
    fun `a groundstroke follow-through raising one arm only is not a serve`() {
        val samples = sequence(
            15 to { _ -> person(Arm.DOWN) },
            6 to { _ -> person(Arm.RIGHT_UP) },
            15 to { _ -> person(Arm.DOWN) },
        )
        assertTrue(ServeProposals.find(samples, homography, 1920, 1080).isEmpty())
    }

    @Test
    fun `the serve pattern while running across the court is not a serve`() {
        // ~100 px per 100 ms near the baseline: about 4 m/s, a rally sprint.
        val samples = sequence(
            15 to { _ -> person(Arm.DOWN) },
            8 to { i -> person(Arm.LEFT_UP, feetX = 480f + (i - 15) * 100f) },
            3 to { i -> person(Arm.RIGHT_UP, feetX = 480f + (i - 15) * 100f) },
            15 to { _ -> person(Arm.DOWN, feetX = 1600f) },
        )
        assertTrue(ServeProposals.find(samples, homography, 1920, 1080).isEmpty())
    }

    @Test
    fun `the far player serving is ignored, since the near camera can't measure it`() {
        // Feet at the far baseline: high in the frame, beyond the near-court band.
        val samples = sequence(
            15 to { _ -> person(Arm.DOWN, feetX = 900f, feetY = 625f) },
            8 to { _ -> person(Arm.LEFT_UP, feetX = 900f, feetY = 625f) },
            3 to { _ -> person(Arm.RIGHT_UP, feetX = 900f, feetY = 625f) },
            15 to { _ -> person(Arm.DOWN, feetX = 900f, feetY = 625f) },
        )
        assertTrue(ServeProposals.find(samples, homography, 1920, 1080).isEmpty())
    }

    @Test
    fun `two serves in a row are two proposals`() {
        val serve = arrayOf(
            15 to { _: Int -> person(Arm.DOWN) },
            8 to { _: Int -> person(Arm.LEFT_UP) },
            3 to { _: Int -> person(Arm.RIGHT_UP) },
            40 to { _: Int -> person(Arm.DOWN) },
        )
        assertEquals(2, ServeProposals.find(sequence(*serve, *serve), homography, 1920, 1080).size)
    }
}
