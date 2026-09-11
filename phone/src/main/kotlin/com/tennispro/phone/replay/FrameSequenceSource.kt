package com.tennispro.phone.replay

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import android.util.Size
import com.tennispro.core.vision.GrayscaleFrame
import java.io.File

/**
 * Decodes a recorded session's frames sequentially — fast, unlike
 * [VideoFrameSource]'s `MediaMetadataRetriever.getFrameAtTime`, which
 * re-seeks from the nearest keyframe on *every* call: fine for one frame at
 * a time in a scrub UI, effectively O(n²) for n frames pulled in sequence.
 *
 * Two outputs, for the two passes of serve detection:
 * - [decodeRange]: every frame in a window, full-resolution grayscale — the
 *   video's Y (luma) plane already *is* grayscale, so there is no colour
 *   conversion to pay for. For ball tracking.
 * - [decodeSampledColor]: roughly one frame per interval across a long span,
 *   half-resolution colour. For pose: MediaPipe on grayscale lost the server
 *   for most of a serve on the 2026-09-08 footage, while half-resolution colour
 *   matched full resolution (the model downsamples to 256 px anyway).
 *
 * The decoder is configured with **no output `Surface`**, reading frames via
 * [MediaCodec.getOutputImage] instead — deliberately not a `Surface`-backed
 * `ImageReader`, which was the first design here and hit a confirmed,
 * repeatable native crash on-device (`JNI DETECTED ERROR ...
 * nativeCreatePlanes ... nullptr`): a hardware decoder can write to a
 * `Surface` in an opaque, GPU-private buffer format that `ImageReader`
 * cannot safely expose as CPU-readable `YUV_420_888` planes, even with the
 * producer/consumer signaling correctly synchronized (which was tried first
 * and did not fix it). `getOutputImage` reads directly from the codec's own
 * output buffer and does not have this failure mode.
 */
class FrameSequenceSource(private val videoFile: File) {

    /** Duration and frame size from the container, or null if there is no readable video track. */
    fun videoInfo(): VideoInfo? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(videoFile.absolutePath)
            val track = selectVideoTrack(extractor) ?: return null
            val format = extractor.getTrackFormat(track)
            VideoInfo(
                durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1_000,
                size = Size(format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT)),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not read video info for ${videoFile.name}", e)
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    data class VideoInfo(val durationMs: Long, val size: Size)

    /**
     * Calls [onFrame] once per decoded frame with a presentation timestamp in
     * `[startMs, endMs]`, in order. Frames before [startMs] are still
     * decoded — a codec can only start from the nearest preceding sync frame
     * — but are not delivered to [onFrame].
     */
    fun decodeRange(startMs: Long, endMs: Long, onFrame: (timeMs: Long, frame: GrayscaleFrame) -> Unit) {
        decode(startMs, endMs, wants = { true }) { timeMs, image -> onFrame(timeMs, imageToGrayscale(image)) }
    }

    /**
     * Calls [onFrame] with the first frame at or after each multiple of
     * [intervalMs] in `[startMs, endMs]`, as a half-resolution ARGB bitmap.
     *
     * The bitmap is **reused between calls** — copy it to keep it. Decoding
     * still visits every frame (H.264 can't skip); only the colour conversion
     * is limited to the sampled ones, which is where the cost is.
     */
    fun decodeSampledColor(
        startMs: Long,
        endMs: Long,
        intervalMs: Long,
        onFrame: (timeMs: Long, frame: Bitmap) -> Unit,
    ) {
        var nextSampleMs = startMs
        var bitmap: Bitmap? = null
        var pixels = IntArray(0)
        decode(startMs, endMs, wants = { timeMs -> timeMs >= nextSampleMs }) { timeMs, image ->
            val width = image.width / 2
            val height = image.height / 2
            val target = bitmap?.takeIf { it.width == width && it.height == height }
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    bitmap = it
                    pixels = IntArray(width * height)
                }
            imageToHalfArgb(image, pixels, width, height)
            target.setPixels(pixels, 0, width, 0, 0, width, height)
            onFrame(timeMs, target)
            while (nextSampleMs <= timeMs) nextSampleMs += intervalMs
        }
    }

    /**
     * [wants] decides per frame, before its image is fetched, whether [onImage]
     * sees it: `getOutputImage` maps the codec buffer into CPU-readable planes,
     * which is wasted on the 5-in-6 frames the pose pass skips.
     */
    private fun decode(startMs: Long, endMs: Long, wants: (timeMs: Long) -> Boolean, onImage: (timeMs: Long, image: Image) -> Unit) {
        require(endMs > startMs) { "endMs ($endMs) must be after startMs ($startMs)" }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(videoFile.absolutePath)
            val trackIndex = selectVideoTrack(extractor)
            if (trackIndex == null) {
                Log.w(TAG, "No video track in ${videoFile.name}")
                return
            }
            val format = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)
            extractor.seekTo(startMs * 1_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime == null) {
                Log.w(TAG, "Video track has no MIME type")
                return
            }

            // Offline decoding, not playback: ask for maximum speed rather than
            // letting the codec pace itself as if feeding a display.
            format.setInteger(MediaFormat.KEY_PRIORITY, 1)
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())

            val mediaCodec = MediaCodec.createDecoderByType(mime)
            codec = mediaCodec
            mediaCodec.configure(format, null, null, 0)
            mediaCodec.start()

            runDecodeLoop(extractor, mediaCodec, startMs * 1_000L, endMs * 1_000L, wants, onImage)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun runDecodeLoop(
        extractor: MediaExtractor,
        codec: MediaCodec,
        startUs: Long,
        endUs: Long,
        wants: (timeMs: Long) -> Boolean,
        onImage: (timeMs: Long, image: Image) -> Unit,
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var idleIterations = 0

        // Both dequeue calls are allowed to time out with nothing ready — normal
        // mid-stream. Only bail out if *neither side* makes progress for a while,
        // which means the codec is genuinely stuck, not just between buffers.
        while (!outputDone && idleIterations < MAX_IDLE_ITERATIONS) {
            var madeProgress = false

            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    madeProgress = true
                    val sampleTimeUs = extractor.sampleTime
                    if (sampleTimeUs < 0 || sampleTimeUs > endUs) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }
            }

            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (outputIndex >= 0) {
                madeProgress = true
                val presentationUs = bufferInfo.presentationTimeUs
                val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                val inWindow = bufferInfo.size > 0 && presentationUs in startUs..endUs

                if (inWindow && wants(presentationUs / 1_000)) {
                    val image = codec.getOutputImage(outputIndex)
                    if (image != null) {
                        try {
                            onImage(presentationUs / 1_000, image)
                        } finally {
                            image.close()
                        }
                    }
                }
                codec.releaseOutputBuffer(outputIndex, false)

                if (isEos || presentationUs > endUs) outputDone = true
            }

            idleIterations = if (madeProgress) 0 else idleIterations + 1
        }
        if (idleIterations >= MAX_IDLE_ITERATIONS) {
            Log.w(TAG, "Decode loop stalled — stopped after $MAX_IDLE_ITERATIONS idle iterations")
        }
    }

    private fun imageToGrayscale(image: Image): GrayscaleFrame {
        val width = image.width
        val height = image.height
        val plane = image.planes[0] // Y (luma) is always plane 0 for YUV_420_888.
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val pixels = IntArray(width * height)

        if (pixelStride == 1) {
            val row = ByteArray(rowStride)
            for (y in 0 until height) {
                buffer.position(y * rowStride)
                buffer.get(row, 0, minOf(rowStride, buffer.remaining()))
                for (x in 0 until width) {
                    pixels[y * width + x] = row[x].toInt() and 0xFF
                }
            }
        } else {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    pixels[y * width + x] = buffer.get(y * rowStride + x * pixelStride).toInt() and 0xFF
                }
            }
        }
        return GrayscaleFrame(width, height, pixels)
    }

    /**
     * YUV_420_888 to ARGB at half resolution: every other luma sample, and the
     * chroma planes at their native (already half) resolution, so no chroma
     * interpolation is needed. BT.601 limited-range coefficients in 10-bit
     * fixed point.
     */
    private fun imageToHalfArgb(image: Image, out: IntArray, width: Int, height: Int) {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        // Whole rows copied out in one bulk get each: three absolute ByteBuffer.get
        // calls per output pixel were a large share of the pose pass's time.
        val yRow = ByteArray(yPlane.rowStride)
        val uRow = ByteArray(uPlane.rowStride)
        val vRow = ByteArray(vPlane.rowStride)
        val yPixel = yPlane.pixelStride
        val uPixel = uPlane.pixelStride
        val vPixel = vPlane.pixelStride
        for (oy in 0 until height) {
            copyRow(yPlane.buffer, 2 * oy * yPlane.rowStride, yRow)
            copyRow(uPlane.buffer, oy * uPlane.rowStride, uRow)
            copyRow(vPlane.buffer, oy * vPlane.rowStride, vRow)
            for (ox in 0 until width) {
                val luma = ((yRow[2 * ox * yPixel].toInt() and 0xFF) - 16).coerceAtLeast(0) * 1192
                val u = (uRow[ox * uPixel].toInt() and 0xFF) - 128
                val v = (vRow[ox * vPixel].toInt() and 0xFF) - 128
                val r = ((luma + 1634 * v) shr 10).coerceIn(0, 255)
                val g = ((luma - 833 * v - 400 * u) shr 10).coerceIn(0, 255)
                val b = ((luma + 2066 * u) shr 10).coerceIn(0, 255)
                out[oy * width + ox] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    /** Copies up to `into.size` bytes starting at [offset]; the last row of a plane can be shorter than its stride. */
    private fun copyRow(buffer: java.nio.ByteBuffer, offset: Int, into: ByteArray) {
        buffer.position(offset)
        buffer.get(into, 0, minOf(into.size, buffer.remaining()))
    }

    private fun selectVideoTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i
        }
        return null
    }

    private companion object {
        const val TAG = "FrameSequenceSource"
        const val TIMEOUT_US = 10_000L
        const val MAX_IDLE_ITERATIONS = 200
    }
}
