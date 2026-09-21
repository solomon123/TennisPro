package com.tennispro.phone.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Publishes finished recordings to the gallery, and takes them away again.
 *
 * Recordings used to live only in app-specific external storage, which Android
 * erases on uninstall and hides from USB file transfer — a match was lost that
 * way. The video is therefore **moved**, not copied: published to
 * `Movies/TennisReplay`, then removed from the session directory once the copy
 * is confirmed byte-for-byte the same length. Copying would double the space a
 * recording takes, and these run to hundreds of megabytes for a few minutes.
 *
 * The session directory keeps everything else — metadata, bookmarks, serves —
 * so the recording is still the app's, and [MatchStorage.deleteSession] takes
 * the gallery entry with it. The app owns every row it inserts here, so it can
 * delete them without a permission prompt.
 *
 * The user can still delete the video from their gallery behind the app's back.
 * That leaves a session whose video will not open, which the UI reports rather
 * than treating as a crash.
 */
object GalleryVideos {

    private const val TAG = "GalleryVideos"
    private const val FOLDER = "TennisReplay"

    /** What [publish] did, so the caller can record it or leave the local file alone. */
    data class Published(val uri: Uri, val bytes: Long)

    /**
     * Moves [file] into the gallery. Returns null and leaves [file] untouched on
     * any failure — losing the recording to a half-finished export would be far
     * worse than leaving it where it already works.
     */
    fun publish(context: Context, file: File, displayName: String, takenAtEpochMs: Long): Published? {
        if (!file.isFile || file.length() == 0L) return null
        val resolver = context.contentResolver
        val expected = file.length()

        val pending = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$FOLDER")
            put(MediaStore.Video.Media.DATE_TAKEN, takenAtEpochMs)
            // Hides the row from the gallery until the bytes are all there, so a
            // half-written video is never offered to the user or to a share sheet.
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val uri = runCatching {
            resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), pending)
        }.getOrNull() ?: run {
            Log.w(TAG, "MediaStore would not accept a new video row")
            return null
        }

        val copied = runCatching {
            resolver.openOutputStream(uri, "w").use { out ->
                requireNotNull(out) { "no output stream for $uri" }
                file.inputStream().use { it.copyTo(out) }
            }
        }.isSuccess

        val actual = if (copied) sizeOf(context, uri) else null
        if (!copied || actual != expected) {
            Log.w(TAG, "Export of ${file.name} incomplete (expected $expected, got $actual) — keeping the local file")
            runCatching { resolver.delete(uri, null, null) }
            return null
        }

        runCatching {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        }.onFailure {
            Log.w(TAG, "Could not publish $uri", it)
            runCatching { resolver.delete(uri, null, null) }
            return null
        }

        // Only now, with a verified copy visible in the gallery, is it safe to
        // give up the one the app has.
        if (!file.delete()) Log.w(TAG, "Exported ${file.name} but could not remove the local copy")

        Log.i(TAG, "Exported ${file.name} to the gallery as $uri ($expected bytes)")
        return Published(uri, expected)
    }

    /** Removes a published video. Missing or already-deleted rows count as success. */
    fun delete(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.delete(uri, null, null)
        true
    }.getOrElse {
        Log.w(TAG, "Could not delete $uri from the gallery", it)
        false
    }

    /** True if the video is still there — the user can delete it from their gallery at any time. */
    fun exists(context: Context, uri: Uri): Boolean = sizeOf(context, uri) != null

    private fun sizeOf(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.Video.Media.SIZE), null, null, null)
            ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
    }.getOrNull()
}
