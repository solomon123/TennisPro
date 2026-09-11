package com.tennispro.phone.vision

import android.content.Context
import android.util.Log
import com.tennispro.core.court.CourtFormat
import com.tennispro.core.court.Homography
import com.tennispro.core.vision.CourtLineDetector
import com.tennispro.core.vision.FrameBlobs
import com.tennispro.core.vision.GrayscaleFrame
import com.tennispro.core.vision.MotionBlobs
import com.tennispro.core.vision.PixelRect
import com.tennispro.core.vision.PoseSample
import com.tennispro.core.vision.ServeFlight
import com.tennispro.core.vision.ServeOutcome
import com.tennispro.core.vision.ServeProposal
import com.tennispro.core.vision.ServeProposals
import com.tennispro.phone.calibration.CalibrationStorage
import com.tennispro.phone.replay.FrameSequenceSource
import com.tennispro.phone.storage.DetectedServe
import com.tennispro.phone.storage.MatchSession
import com.tennispro.phone.storage.MatchStorage
import com.tennispro.phone.storage.SessionServes
import kotlin.coroutines.cancellation.CancellationException

/**
 * Finds and measures every serve in a recording, with no marking by the user
 * — the second field-test request (2026-09-10): nobody can touch the phone
 * mid-match. See docs/ARCHITECTURE.md's "Serve detection" section.
 *
 * 1. **Court.** Found in the recording itself by [CourtLineDetector], not
 *    taken from the saved calibration: the phone moved twice inside one
 *    2026-09-08 recording. Found again at every serve for the same reason; the
 *    saved calibration is only a fallback when detection fails.
 * 2. **Pose pass.** MediaPipe Pose on ~10 frames a second across the whole
 *    recording; [ServeProposals] picks out serve-shaped moments.
 * 3. **Flight pass.** For each proposal, every frame from just before the
 *    swing to after the bounce, full-resolution grayscale, through
 *    [MotionBlobs] with the server's body masked out, then [ServeFlight].
 *
 * Blocking and CPU-heavy (minutes for a long match): call it off the main
 * thread, and poll [isCancelled] — it's checked between frames.
 */
class ServeScanner(
    private val context: Context,
    private val storage: MatchStorage,
    private val calibrationStorage: CalibrationStorage,
) {

    fun scan(session: MatchSession, onProgress: (Float) -> Unit, isCancelled: () -> Boolean): SessionServes {
        val source = FrameSequenceSource(storage.videoFileFor(session))
        val info = source.videoInfo() ?: return failed("Could not read this recording's video")
        val width = info.size.width
        val height = info.size.height

        val format = calibrationStorage.load()?.format ?: CourtFormat.SINGLES
        val sessionCourt = detectCourt(source, SESSION_COURT_FRAME_MS.coerceAtMost(info.durationMs - 1), format)
            ?: savedCalibration(width, height)
            ?: return failed("Couldn't find the court in this recording — calibrate, then scan again")

        val samples = ArrayList<PoseSample>()
        val poseStart = System.nanoTime()
        var inferenceNanos = 0L
        var delegate = "?"
        PoseSampler(context).use { sampler ->
            delegate = sampler.delegate
            source.decodeSampledColor(0, info.durationMs, POSE_INTERVAL_MS) { timeMs, frame ->
                if (isCancelled()) throw CancellationException("Serve scan cancelled")
                val t0 = System.nanoTime()
                samples += sampler.sample(frame, timeMs, width, height)
                inferenceNanos += System.nanoTime() - t0
                onProgress(POSE_PASS_SHARE * timeMs / info.durationMs)
            }
        }

        val proposals = ServeProposals.find(samples, sessionCourt, width, height)
        Log.i(
            TAG,
            "${session.meta.id}: ${samples.size} pose samples in %.1f s (%.1f s pose inference on %s), ${proposals.size} serve proposals"
                .format((System.nanoTime() - poseStart) / 1e9, inferenceNanos / 1e9, delegate),
        )

        val serves = proposals.mapIndexedNotNull { index, proposal ->
            if (isCancelled()) throw CancellationException("Serve scan cancelled")
            val measureStart = System.nanoTime()
            val outcome = measure(source, proposal, sessionCourt, format, width, height, info.durationMs)
            onProgress(POSE_PASS_SHARE + (1 - POSE_PASS_SHARE) * (index + 1) / proposals.size)
            Log.i(TAG, "${session.meta.id}: proposal at ${proposal.racketUpMs} ms -> $outcome (%.1f s)".format((System.nanoTime() - measureStart) / 1e9))
            when (outcome) {
                is ServeOutcome.Measured -> DetectedServe(
                    contactMs = outcome.contactMs,
                    speedKmh = outcome.kmh,
                    errorBandPercent = outcome.errorBandPercent,
                    callVerdict = outcome.call.verdict.name,
                    callMarginMeters = outcome.call.marginMeters,
                    callErrorMeters = outcome.call.errorBandMeters,
                    callEdge = outcome.call.edge.name,
                    bounceXMeters = outcome.bounce.xMeters.toDouble(),
                    bounceYMeters = outcome.bounce.yMeters.toDouble(),
                )
                is ServeOutcome.NetFault -> DetectedServe(contactMs = outcome.contactMs, netFault = true)
                is ServeOutcome.NoFlight -> null
            }
        }
        onProgress(1f)
        return SessionServes(scannedAtEpochMs = System.currentTimeMillis(), serves = serves)
    }

    private fun measure(
        source: FrameSequenceSource,
        proposal: ServeProposal,
        sessionCourt: Homography,
        format: CourtFormat,
        width: Int,
        height: Int,
        durationMs: Long,
    ): ServeOutcome {
        val startMs = (proposal.racketUpMs - WINDOW_BEFORE_MS).coerceAtLeast(0)
        val endMs = (proposal.racketUpMs + WINDOW_AFTER_MS).coerceAtMost(durationMs)
        if (endMs <= startMs) return ServeOutcome.NoFlight("Serve too close to the end of the recording")

        val body = ServeFlight.bodyMask(proposal.server, height)

        val frames = ArrayList<FrameBlobs>()
        var court: Homography? = null
        val window = ArrayDeque<Pair<Long, GrayscaleFrame>>(3)
        source.decodeRange(startMs, endMs) { timeMs, frame ->
            if (court == null) court = CourtLineDetector.detect(frame)?.let { Homography.fromCalibration(it.toCalibration(format, width, height)) }
            window.addLast(timeMs to frame)
            if (window.size == 3) {
                val (_, prev) = window[0]
                val (currMs, curr) = window[1]
                val (_, next) = window[2]
                frames += FrameBlobs(currMs, MotionBlobs.find(prev, curr, next, exclude = body))
                window.removeFirst()
            }
        }
        if (frames.size < 2) return ServeOutcome.NoFlight("Could not decode frames around the serve")

        val fps = 1000.0 * (frames.size - 1) / (frames.last().timeMs - frames.first().timeMs).coerceAtLeast(1)
        return ServeFlight.analyze(frames, proposal, court ?: sessionCourt, format, fps)
    }

    private fun detectCourt(source: FrameSequenceSource, atMs: Long, format: CourtFormat): Homography? {
        var homography: Homography? = null
        runCatching {
            source.decodeRange(atMs, atMs + COURT_FRAME_SPAN_MS) { _, frame ->
                if (homography == null) {
                    homography = CourtLineDetector.detect(frame)
                        ?.let { Homography.fromCalibration(it.toCalibration(format, frame.width, frame.height)) }
                }
            }
        }.onFailure { Log.w(TAG, "Court detection failed", it) }
        return homography
    }

    private fun savedCalibration(width: Int, height: Int): Homography? =
        calibrationStorage.load()?.scaledTo(width, height)?.let { Homography.fromCalibration(it) }

    private fun failed(reason: String) =
        SessionServes(scannedAtEpochMs = System.currentTimeMillis(), serves = emptyList(), error = reason)

    private companion object {
        const val TAG = "ServeScanner"
        const val POSE_INTERVAL_MS = 100L
        const val POSE_PASS_SHARE = 0.75f
        const val SESSION_COURT_FRAME_MS = 1_000L
        const val COURT_FRAME_SPAN_MS = 100L

        /** Decode window around racket-up: the toss before, the flight and bounce after. */
        const val WINDOW_BEFORE_MS = 700L
        const val WINDOW_AFTER_MS = 2_000L
    }
}
