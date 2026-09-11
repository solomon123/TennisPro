package com.tennispro.phone.vision

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.tennispro.core.court.PixelPoint
import com.tennispro.core.vision.PixelRect
import com.tennispro.core.vision.PoseKeypoints
import com.tennispro.core.vision.PoseSample

/**
 * MediaPipe Pose over a recording's sampled frames, reduced to the landmarks
 * [com.tennispro.core.vision.ServeProposals] uses, in the recording's own
 * pixel space (landmarks come back normalized, so the sampled frame's own
 * resolution doesn't matter).
 *
 * VIDEO running mode: frames must arrive in increasing time order, and the
 * model uses the previous frame to track people between detections. Up to
 * [MAX_PEOPLE] people per frame — the server, the opponent, and whoever is on
 * the next court.
 */
class PoseSampler(context: Context) : AutoCloseable {

    private val landmarker: PoseLandmarker = PoseLandmarker.createFromOptions(
        context,
        PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumPoses(MAX_PEOPLE)
            .build(),
    )

    fun sample(frame: Bitmap, timeMs: Long, videoWidth: Int, videoHeight: Int): PoseSample {
        val result = landmarker.detectForVideo(BitmapImageBuilder(frame).build(), timeMs)
        val people = result.landmarks().mapNotNull { landmarks -> toKeypoints(landmarks, videoWidth, videoHeight) }
        return PoseSample(timeMs, people)
    }

    private fun toKeypoints(landmarks: List<NormalizedLandmark>, width: Int, height: Int): PoseKeypoints? {
        if (landmarks.size <= RIGHT_ANKLE) return null
        fun point(index: Int) = PixelPoint(landmarks[index].x() * width, landmarks[index].y() * height)
        return PoseKeypoints(
            nose = point(NOSE),
            leftShoulder = point(LEFT_SHOULDER),
            rightShoulder = point(RIGHT_SHOULDER),
            leftWrist = point(LEFT_WRIST),
            rightWrist = point(RIGHT_WRIST),
            leftAnkle = point(LEFT_ANKLE),
            rightAnkle = point(RIGHT_ANKLE),
            box = PixelRect(
                left = landmarks.minOf { it.x() } * width.toDouble(),
                top = landmarks.minOf { it.y() } * height.toDouble(),
                right = landmarks.maxOf { it.x() } * width.toDouble(),
                bottom = landmarks.maxOf { it.y() } * height.toDouble(),
            ),
        )
    }

    override fun close() {
        runCatching { landmarker.close() }
    }

    private companion object {
        const val MODEL_ASSET = "pose_landmarker_lite.task"
        const val MAX_PEOPLE = 3

        // MediaPipe Pose's 33-point topology.
        const val NOSE = 0
        const val LEFT_SHOULDER = 11
        const val RIGHT_SHOULDER = 12
        const val LEFT_WRIST = 15
        const val RIGHT_WRIST = 16
        const val LEFT_ANKLE = 27
        const val RIGHT_ANKLE = 28
    }
}
