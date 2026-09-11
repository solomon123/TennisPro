package com.tennispro.core.vision

import com.tennispro.core.court.Homography
import com.tennispro.core.court.PixelPoint
import kotlin.math.max
import kotlin.math.min

/**
 * The handful of pose landmarks serve detection looks at, for one person in
 * one frame, in the recording's pixel space. "Left"/"right" are the person's
 * own, as MediaPipe Pose reports them.
 */
data class PoseKeypoints(
    val nose: PixelPoint,
    val leftShoulder: PixelPoint,
    val rightShoulder: PixelPoint,
    val leftWrist: PixelPoint,
    val rightWrist: PixelPoint,
    val leftAnkle: PixelPoint,
    val rightAnkle: PixelPoint,
    /** Bounding box of every landmark, not just the ones above. */
    val box: PixelRect,
) {
    val lowestAnkleY: Float get() = max(leftAnkle.y, rightAnkle.y)
    val feet: PixelPoint get() = PixelPoint((leftAnkle.x + rightAnkle.x) / 2, lowestAnkleY)

    /** From behind, a person's own left shoulder is on the image's left. */
    val facingAway: Boolean get() = leftShoulder.x < rightShoulder.x
    val leftWristUp: Boolean get() = leftWrist.y < nose.y
    val rightWristUp: Boolean get() = rightWrist.y < nose.y
}

/** Everyone pose found in one sampled frame. */
data class PoseSample(val timeMs: Long, val people: List<PoseKeypoints>)

/**
 * A moment that looks like a serve from the server's body alone. Only a
 * proposal: [ServeFlight] confirms it by finding the ball's flight.
 *
 * @property racketUpMs when the hitting arm first rises above the head — the
 *   racket drop into the swing, 0.2-0.6 s before contact on every serve checked
 *   by eye.
 * @property server the server's pose at [racketUpMs].
 */
data class ServeProposal(val tossStartMs: Long, val racketUpMs: Long, val server: PoseKeypoints)

/**
 * Finds serve-shaped moments in a sequence of pose samples (~10 a second).
 *
 * Built against the 2026-09-08 field recordings, where a looser "a wrist above
 * the head" rule fired on groundstrokes, a player waving at the camera, and
 * poses MediaPipe hallucinated in the trees. What a serve looks like from a
 * camera behind the server, and what each check below rules out:
 *
 * - **On the near court.** Feet low in the frame and, through the court
 *   calibration, at or behind the near baseline — rules out the far player
 *   and hallucinated poses above the court.
 * - **Back to the camera** before the toss — rules out someone walking toward
 *   the camera with an arm up.
 * - **Toss arm up** (one wrist above the head, the other not) for at least
 *   [MIN_TOSS_SAMPLES] samples, **then the other arm up** — the hitting arm
 *   rising into the swing. A groundstroke's follow-through raises one arm only.
 * - **Standing still** from the toss to the racket rising — rules out running
 *   rally shots. Only until then: after a serve the server moves on.
 */
object ServeProposals {

    fun find(samples: List<PoseSample>, homography: Homography, frameWidth: Int, frameHeight: Int): List<ServeProposal> {
        val servers = samples.map { server(it, homography, frameHeight) }
        val proposals = mutableListOf<ServeProposal>()

        var i = 0
        while (i < samples.size) {
            val start = servers[i]
            if (start == null || start.leftWristUp == start.rightWristUp) {
                i++
                continue
            }
            val tossStartMs = samples[i].timeMs
            val tossIsLeft = start.leftWristUp

            var tossSamples = 0
            var racketUp: Int? = null
            var minFeetX = Float.MAX_VALUE
            var maxFeetX = -Float.MAX_VALUE
            var awayBefore = 0
            var seenBefore = 0

            for (j in samples.indices) {
                val t = samples[j].timeMs
                if (t < tossStartMs - LOOK_BACK_MS) continue
                if (t > tossStartMs + LOOK_AHEAD_MS) break
                val pose = servers[j] ?: continue
                if (t < tossStartMs) {
                    seenBefore++
                    if (pose.facingAway) awayBefore++
                    continue
                }
                if (racketUp == null) {
                    minFeetX = min(minFeetX, pose.feet.x)
                    maxFeetX = max(maxFeetX, pose.feet.x)
                }
                val tossUp = if (tossIsLeft) pose.leftWristUp else pose.rightWristUp
                val hitUp = if (tossIsLeft) pose.rightWristUp else pose.leftWristUp
                if (tossUp) tossSamples++
                if (racketUp == null && tossSamples >= MIN_TOSS_SAMPLES && hitUp) racketUp = j
            }

            val upIndex = racketUp
            val still = upIndex != null && (maxFeetX - minFeetX) <= MAX_FEET_DRIFT_FRACTION * frameWidth
            val facingAway = seenBefore > 0 && awayBefore * 2 >= seenBefore
            if (tossSamples >= MIN_TOSS_SAMPLES && upIndex != null && still && facingAway) {
                proposals += ServeProposal(tossStartMs, samples[upIndex].timeMs, servers[upIndex]!!)
                val resumeAfter = samples[upIndex].timeMs + LOOK_AHEAD_MS
                while (i < samples.size && samples[i].timeMs < resumeAfter) i++
                continue
            }
            i++
        }
        return proposals
    }

    /** The near-court player in [sample], if any: feet on the court foreground, closest to the camera. */
    private fun server(sample: PoseSample, homography: Homography, frameHeight: Int): PoseKeypoints? =
        sample.people
            .filter { it.lowestAnkleY >= MIN_ANKLE_Y_FRACTION * frameHeight }
            .filter {
                val feet = homography.mapToCourt(PixelPoint(it.feet.x, min(it.feet.y, frameHeight.toFloat())))
                feet.yMeters in NEAR_COURT_MIN_Y_M..NEAR_COURT_MAX_Y_M && feet.xMeters in COURT_MIN_X_M..COURT_MAX_X_M
            }
            .maxByOrNull { it.lowestAnkleY }

    private const val MIN_TOSS_SAMPLES = 3
    private const val LOOK_BACK_MS = 1_000L
    private const val LOOK_AHEAD_MS = 2_500L
    private const val MIN_ANKLE_Y_FRACTION = 0.55f

    /** Behind the near baseline (negative) up to a little inside it. */
    private const val NEAR_COURT_MIN_Y_M = -6f
    private const val NEAR_COURT_MAX_Y_M = 3f
    private const val COURT_MIN_X_M = -3f
    private const val COURT_MAX_X_M = 14f

    /** Feet drift allowed between the toss and the racket rising, as a fraction of frame width. */
    private const val MAX_FEET_DRIFT_FRACTION = 0.07f
}
