package com.tennispro.phone.replay

import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.tennispro.core.vision.GrayscaleFrame
import java.io.File

/**
 * Decodes every frame in a short time window sequentially — fast, unlike
 * [VideoFrameSource]'s `MediaMetadataRetriever.getFrameAtTime`, which
 * re-seeks from the nearest keyframe on *every* call: fine for one frame at
 * a time in a scrub UI, effectively O(n²) for n frames pulled in sequence.
 * Deferred from Phase 2 until a caller actually needed that throughput — see
 * docs/ARCHITECTURE.md's Calibration section — and Phase 3's ball tracker,
 * which needs every frame across a serve, is that caller.
 *
 * Decodes straight to grayscale, not full ARGB `Bitmap`s: the video track's
 * Y (luma) plane already *is* grayscale, so there is no RGB conversion to
 * pay for. The decoder is configured with **no output `Surface`**, reading
 * frames via [MediaCodec.getOutputImage] instead — deliberately not a
 * `Surface`-backed `ImageReader`, which was the first design here and hit a
 * confirmed, repeatable native crash on-device (`JNI DETECTED ERROR ...
 * nativeCreatePlanes ... nullptr`): a hardware decoder can write to a
 * `Surface` in an opaque, GPU-private buffer format that `ImageReader`
 * cannot safely expose as CPU-readable `YUV_420_888` planes, even with the
 * producer/consumer signaling correctly synchronized (which was tried first
 * and did not fix it). `getOutputImage` reads directly from the codec's own
 * output buffer and does not have this failure mode.
 */
class FrameSequenceSource(private val videoFile: File) {

    /**
     * Calls [onFrame] once per decoded frame with a presentation timestamp in
     * `[startMs, endMs]`, in order. Frames before [startMs] are still
     * decoded — a codec can only start from the nearest preceding sync frame
     * — but are not delivered to [onFrame].
     */
    fun decodeRange(startMs: Long, endMs: Long, onFrame: (timeMs: Long, frame: GrayscaleFrame) -> Unit) {
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

            val mediaCodec = MediaCodec.createDecoderByType(mime)
            codec = mediaCodec
            mediaCodec.configure(format, null, null, 0)
            mediaCodec.start()

            runDecodeLoop(extractor, mediaCodec, startMs * 1_000L, endMs * 1_000L, onFrame)
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
        onFrame: (timeMs: Long, frame: GrayscaleFrame) -> Unit,
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

                if (inWindow) {
                    val image = codec.getOutputImage(outputIndex)
                    if (image != null) {
                        onFrame(presentationUs / 1_000, imageToGrayscale(image))
                        image.close()
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
