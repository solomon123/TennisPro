package com.tennispro.phone.vision

import android.content.Context
import com.tennispro.core.court.Homography
import com.tennispro.core.vision.BallCandidate
import com.tennispro.core.vision.BallDetector
import com.tennispro.core.vision.GrayscaleFrame
import com.tennispro.core.vision.KalmanTracker2D
import com.tennispro.core.vision.ServeSpeed
import com.tennispro.core.vision.SpeedEstimate
import com.tennispro.core.vision.TrackedPoint
import com.tennispro.core.vision.Trajectory
import com.tennispro.phone.calibration.CalibrationStorage
import com.tennispro.phone.replay.FrameSequenceSource
import com.tennispro.phone.storage.MatchSession
import com.tennispro.phone.storage.MatchStorage

sealed interface ServeAnalysisResult {
    data class Success(val estimate: SpeedEstimate, val contactTimeMs: Long, val bounceTimeMs: Long?) : ServeAnalysisResult
    data class Failure(val reason: String) : ServeAnalysisResult
}

/**
 * Turns a rough bookmark ("a serve happened here") into a [SpeedEstimate],
 * running the full pipeline docs/ARCHITECTURE.md's Serve speed section
 * describes: [PoseSwingWindow] narrows the search, [BallDetector] +
 * [KalmanTracker2D] track the ball across it, [Trajectory] finds contact and
 * bounce, [ServeSpeed] does the actual math against the saved calibration.
 *
 * Every failure path returns a [ServeAnalysisResult.Failure] with a reason
 * rather than a fabricated estimate — the whole point of computing an error
 * band (see [ServeSpeed]) is defeated by also reporting a number when the
 * pipeline didn't actually get a usable track.
 */
class ServeAnalyzer(context: Context, private val calibrationStorage: CalibrationStorage) {

    private val poseWindow = PoseSwingWindow(context.applicationContext)

    fun analyze(session: MatchSession, storage: MatchStorage, bookmarkOffsetMs: Long): ServeAnalysisResult {
        val calibration = calibrationStorage.load()
            ?: return ServeAnalysisResult.Failure("No court calibration saved — calibrate first")
        val homography = Homography.fromCalibration(calibration)
            ?: return ServeAnalysisResult.Failure("Saved calibration is degenerate — recalibrate")

        val videoFile = storage.videoFileFor(session)
        val roughStart = (bookmarkOffsetMs - ROUGH_WINDOW_BEFORE_MS).coerceAtLeast(0)
        val roughEnd = bookmarkOffsetMs + ROUGH_WINDOW_AFTER_MS

        val narrowed = poseWindow.narrow(videoFile, roughStart, roughEnd)

        val trackedPoints = trackBall(videoFile, narrowed.first, narrowed.last)
        if (trackedPoints.size < MIN_TRACKED_POINTS) {
            return ServeAnalysisResult.Failure("Couldn't get a clean ball track in this window")
        }

        val contactIndex = Trajectory.findContactIndex(trackedPoints)
            ?: return ServeAnalysisResult.Failure("Couldn't identify the moment of contact in this window")
        val bounceIndex = Trajectory.findBounceIndex(trackedPoints, searchFrom = contactIndex)

        val fps = session.meta.frameRate?.toDouble() ?: DEFAULT_FPS
        val estimate = ServeSpeed.estimate(homography, trackedPoints, contactIndex, bounceIndex, fps)
            ?: return ServeAnalysisResult.Failure("Couldn't compute a speed from this track")

        return ServeAnalysisResult.Success(
            estimate = estimate,
            contactTimeMs = trackedPoints[contactIndex].timeMs,
            bounceTimeMs = bounceIndex?.let { trackedPoints[it].timeMs },
        )
    }

    /** Frame-differences and Kalman-tracks the ball across `[startMs, endMs]`, in order. */
    private fun trackBall(videoFile: java.io.File, startMs: Long, endMs: Long): List<TrackedPoint> {
        val trackedPoints = mutableListOf<TrackedPoint>()
        var previousFrame: GrayscaleFrame? = null
        var tracker: KalmanTracker2D? = null
        var lastTimeMs = startMs

        FrameSequenceSource(videoFile).decodeRange(startMs, endMs) { timeMs, frame ->
            val prev = previousFrame
            if (prev != null) {
                val candidates = BallDetector.detect(prev, frame)
                val dtSeconds = ((timeMs - lastTimeMs) / 1000.0).coerceAtLeast(MIN_DT_SECONDS)
                val activeTracker = tracker

                if (activeTracker == null) {
                    // No track started yet — the largest plausible candidate is the best guess to start from.
                    val first = candidates.maxByOrNull(BallCandidate::pixelCount)
                    if (first != null) {
                        tracker = KalmanTracker2D(first.x, first.y)
                        trackedPoints += TrackedPoint(trackedPoints.size, timeMs, first.x, first.y)
                    }
                } else {
                    activeTracker.predict(dtSeconds)
                    val chosen = candidates
                        .minByOrNull { activeTracker.gateDistance(it.x, it.y) }
                        ?.takeIf { activeTracker.gateDistance(it.x, it.y) < GATE_DISTANCE_PX }
                    if (chosen != null) activeTracker.correct(chosen.x, chosen.y)
                    // Recorded either way — a predict-only step still carries the track through a missed detection.
                    trackedPoints += TrackedPoint(trackedPoints.size, timeMs, activeTracker.x, activeTracker.y)
                }
            }
            previousFrame = frame
            lastTimeMs = timeMs
        }

        return trackedPoints
    }

    fun close() {
        poseWindow.close()
    }

    private companion object {
        const val ROUGH_WINDOW_BEFORE_MS = 1_000L
        const val ROUGH_WINDOW_AFTER_MS = 3_000L
        const val MIN_TRACKED_POINTS = 4
        const val MIN_DT_SECONDS = 0.001
        const val GATE_DISTANCE_PX = 150.0
        const val DEFAULT_FPS = 30.0
    }
}
