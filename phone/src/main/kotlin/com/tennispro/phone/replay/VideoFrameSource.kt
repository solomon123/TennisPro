package com.tennispro.phone.replay

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File

/**
 * Pulls still frames out of a recorded session's video file — the "video
 * replay harness" groundwork docs/ARCHITECTURE.md calls out as the single
 * biggest productivity lever for Phases 3-4: piping recorded footage through
 * the same processing off-court, rather than needing a live camera every time.
 *
 * Backed by [MediaMetadataRetriever.getFrameAtTime] with [MediaMetadataRetriever.OPTION_CLOSEST]
 * — frame-accurate but not fast, which is the right trade for a scrub-through
 * UI. A sequential `MediaExtractor`/`MediaCodec` decoder would be faster for
 * walking many frames in order, and is worth building once Phase 3/4's
 * detector actually needs that throughput; this is deliberately not that yet.
 */
class VideoFrameSource(videoFile: File) : AutoCloseable {

    private val retriever = MediaMetadataRetriever().apply {
        runCatching { setDataSource(videoFile.absolutePath) }
            .onFailure { Log.w(TAG, "Could not open ${videoFile.name} for replay", it) }
    }

    val durationMs: Long by lazy {
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    }

    /** The frame at [positionMs], or null if the file can't be decoded. */
    fun frameAt(positionMs: Long): Bitmap? =
        runCatching {
            retriever.getFrameAtTime(positionMs * 1_000, MediaMetadataRetriever.OPTION_CLOSEST)
        }.onFailure { Log.w(TAG, "Could not decode frame at ${positionMs}ms", it) }.getOrNull()

    override fun close() {
        runCatching { retriever.release() }
    }

    private companion object {
        const val TAG = "VideoFrameSource"
    }
}
