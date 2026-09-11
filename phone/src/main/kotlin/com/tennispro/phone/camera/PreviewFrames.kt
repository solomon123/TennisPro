package com.tennispro.phone.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Size
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Maps a [androidx.camera.view.PreviewView] snapshot into the recorded
 * video's own pixel space — the space calibration has to be saved in, because
 * serve analysis runs on decoded video frames.
 *
 * Found in the first real-court test (Galaxy S25 Ultra, 2026-09-08): the live
 * "Freeze frame" calibration was saved straight in snapshot space, which was
 * wrong twice over. `PreviewView.getBitmap()` is the whole *view* — 2340x1080
 * with FIT_CENTER's letterbox bars baked in — and CameraX's default Preview
 * stream was 4:3 while the FHD recording is 16:9, so the recording was a
 * vertical crop of what the preview showed (the near baseline sat at 84% of
 * the preview's height but 95% of the video's). Every tapped corner landed
 * somewhere else on the frames the analysis actually measured.
 *
 * [RecordingService] now asks for a 16:9 preview, so in practice the crop
 * below is a no-op — but it's still computed from the real stream sizes
 * rather than assumed, since CameraX is allowed to fall back to another
 * aspect ratio on some devices.
 */
internal object PreviewFrames {

    fun toVideoFrame(viewSnapshot: Bitmap, previewSize: Size, videoSize: Size): Bitmap {
        val crop = videoRegionInView(viewSnapshot.width, viewSnapshot.height, previewSize, videoSize)
        val out = Bitmap.createBitmap(videoSize.width, videoSize.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            viewSnapshot,
            crop,
            Rect(0, 0, videoSize.width, videoSize.height),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        return out
    }

    /**
     * The part of a FIT_CENTER [viewWidth]x[viewHeight] snapshot showing
     * exactly what the video records. Both streams are centred crops of the
     * same sensor, so the video region is the preview content centred-cropped
     * to the video's aspect ratio.
     */
    fun videoRegionInView(viewWidth: Int, viewHeight: Int, previewSize: Size, videoSize: Size): Rect {
        val scale = min(viewWidth / previewSize.width.toFloat(), viewHeight / previewSize.height.toFloat())
        val contentWidth = previewSize.width * scale
        val contentHeight = previewSize.height * scale

        val videoAspect = videoSize.width / videoSize.height.toFloat()
        var cropWidth = contentWidth
        var cropHeight = contentWidth / videoAspect
        if (cropHeight > contentHeight) {
            cropHeight = contentHeight
            cropWidth = contentHeight * videoAspect
        }

        val left = (viewWidth - cropWidth) / 2f
        val top = (viewHeight - cropHeight) / 2f
        return Rect(
            left.roundToInt(),
            top.roundToInt(),
            (left + cropWidth).roundToInt(),
            (top + cropHeight).roundToInt(),
        )
    }
}
