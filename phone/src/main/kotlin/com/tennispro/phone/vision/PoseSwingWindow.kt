package com.tennispro.phone.vision

import android.content.Context
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.tennispro.phone.replay.VideoFrameSource
import java.io.File
import kotlin.math.abs

/**
 * Narrows a rough, multi-second bookmark window down to roughly when the
 * server's swing happens, using MediaPipe Pose's wrist landmark.
 *
 * Deliberately **not** used to pinpoint the contact frame itself — a
 * standard pose model tracks body joints, not a racket or the ball, so it
 * has no way to know the exact instant of contact. The robust signal for
 * that is the ball's own trajectory discontinuity (see [com.tennispro.core.vision.Trajectory.findContactIndex]),
 * which is precise because it's observing the thing that actually matters.
 * This class's job is just making sure the ball tracker isn't searching an
 * entire multi-second window blindly, which might contain other motion — a
 * returning opponent, a ball boy.
 *
 * Sampled sparsely (every [SAMPLE_INTERVAL_MS], not every frame) via the
 * existing [VideoFrameSource]: pose timing only needs to be right to within
 * a few hundred milliseconds, not frame-accurate, so there's no need for
 * [com.tennispro.phone.replay.FrameSequenceSource]'s throughput here.
 */
class PoseSwingWindow(context: Context) {

    private val landmarker: PoseLandmarker? = runCatching {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .build()
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.VIDEO)
            .setNumPoses(1)
            .build()
        PoseLandmarker.createFromOptions(context, options)
    }.onFailure { Log.w(TAG, "Could not load pose landmarker", it) }.getOrNull()

    /**
     * Returns the sub-window of `[startMs, endMs]` around the sharpest wrist
     * motion found — a coarse bound, not a frame-precise timestamp. Falls
     * back to the original, unnarrowed window if pose detection isn't
     * available or finds nothing conclusive, so the ball detector still has
     * a window to search either way.
     */
    fun narrow(videoFile: File, startMs: Long, endMs: Long): LongRange {
        val model = landmarker ?: return startMs..endMs
        val source = runCatching { VideoFrameSource(videoFile) }.getOrNull() ?: return startMs..endMs

        val wristHeights = mutableListOf<Pair<Long, Float>>()
        try {
            var t = startMs
            while (t <= endMs) {
                val bitmap = source.frameAt(t)
                if (bitmap != null) {
                    val result = runCatching {
                        model.detectForVideo(BitmapImageBuilder(bitmap).build(), t)
                    }.onFailure { Log.w(TAG, "Pose detection failed at ${t}ms", it) }.getOrNull()

                    // Right wrist, MediaPipe Pose's standard 33-point topology.
                    val wristY = result?.landmarks()?.firstOrNull()?.getOrNull(RIGHT_WRIST_LANDMARK)?.y()
                    if (wristY != null) wristHeights += t to wristY
                }
                t += SAMPLE_INTERVAL_MS
            }
        } finally {
            source.close()
        }

        if (wristHeights.size < 3) return startMs..endMs

        // The swing shows up as the sharpest change in wrist height between
        // consecutive samples — the arm driving upward toward contact, or the
        // follow-through afterward. Pad a margin either side of it, since this
        // is only ever a coarse bound.
        var peakIndex = 1
        var peakDelta = 0f
        for (i in 1 until wristHeights.size) {
            val delta = abs(wristHeights[i].second - wristHeights[i - 1].second)
            if (delta > peakDelta) {
                peakDelta = delta
                peakIndex = i
            }
        }
        val centerMs = wristHeights[peakIndex].first
        return (centerMs - WINDOW_MARGIN_MS).coerceAtLeast(startMs)..(centerMs + WINDOW_MARGIN_MS).coerceAtMost(endMs)
    }

    fun close() {
        runCatching { landmarker?.close() }
    }

    private companion object {
        const val TAG = "PoseSwingWindow"
        const val MODEL_ASSET = "pose_landmarker_lite.task"
        const val RIGHT_WRIST_LANDMARK = 16
        const val SAMPLE_INTERVAL_MS = 66L // ~15 samples/sec — plenty for swing timing, not frame-precise
        const val WINDOW_MARGIN_MS = 700L
    }
}
