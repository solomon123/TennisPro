package com.tennispro.phone.replay

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log

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
class VideoFrameSource(context: Context, videoUri: Uri) : AutoCloseable {

    private val retriever = MediaMetadataRetriever().apply {
        // Context + Uri rather than a path: a recording published to the gallery
        // is a content:// URI the app no longer has a file for. This form also
        // accepts the file:// URI of one not yet exported.
        runCatching { setDataSource(context, videoUri) }
            .onFailure { Log.w(TAG, "Could not open $videoUri for replay", it) }
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
