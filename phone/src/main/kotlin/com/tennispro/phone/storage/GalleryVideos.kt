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

        // Verified against the bytes this copy actually wrote, not against
        // MediaStore's SIZE column: while IS_PENDING is 1 that column is not
        // reliably populated, and trusting it here threw away good exports.
        val written = runCatching {
            resolver.openOutputStream(uri, "w").use { out ->
                requireNotNull(out) { "no output stream for $uri" }
                file.inputStream().use { it.copyTo(out) }
            }
        }.getOrElse {
            Log.w(TAG, "Could not write ${file.name} into the gallery", it)
            runCatching { resolver.delete(uri, null, null) }
            return null
        }

        if (written != expected) {
            Log.w(TAG, "Export of ${file.name} short: wrote $written of $expected bytes")
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

        // Second check, now that the row is published and its size means something.
        // A null here is MediaStore being unhelpful, not evidence of a bad copy, so
        // only a definite mismatch is treated as failure.
        val reported = sizeOf(context, uri)
        if (reported != null && reported != expected) {
            Log.w(TAG, "Published $uri reports $reported bytes, expected $expected — keeping the local file")
            runCatching { resolver.delete(uri, null, null) }
            return null
        }

        // Only now, with a verified copy visible in the gallery, is it safe to
        // give up the one the app has.
        if (!file.delete()) Log.w(TAG, "Exported ${file.name} but could not remove the local copy")

        Log.i(TAG, "Exported ${file.name} to the gallery as $uri ($expected bytes)")
        return Published(uri, expected)
    }

    /**
     * Removes a published video.
     *
     * The app owns the rows it inserts, but only while they stay ordinary rows:
     * once the gallery moves one to its own trash the app loses access and the
     * delete throws `SecurityException`. That is the user having deleted it
     * there, so it counts as done — a row a normal query can no longer see is
     * gone as far as this app is concerned. Only a video that is demonstrably
     * still present and still refuses to be deleted is worth a warning, since
     * that one really does stay behind in the gallery.
     */
    fun delete(context: Context, uri: Uri): Boolean {
        val failure = runCatching { context.contentResolver.delete(uri, null, null) }.exceptionOrNull()
            ?: return true
        if (presence(context, uri) == Presence.MISSING) return true
        Log.w(TAG, "Could not delete $uri from the gallery; it will stay there", failure)
        return false
    }

    /**
     * Whether a published video is still in the gallery. [UNKNOWN] matters as
     * much as the other two: a recording is only discarded on a definite
     * [MISSING], never because MediaStore happened to be unreachable.
     */
    enum class Presence { PRESENT, MISSING, UNKNOWN }

    /**
     * A video the user deleted in their gallery is gone from a normal query even
     * while it sits in the gallery's own trash, which is the right reading here:
     * they deleted it, and the app should follow.
     */
    fun presence(context: Context, uri: Uri): Presence = runCatching {
        context.contentResolver
            .query(uri, arrayOf(MediaStore.Video.Media._ID), null, null, null)
            ?.use { if (it.moveToFirst()) Presence.PRESENT else Presence.MISSING }
            ?: Presence.MISSING
    }.getOrElse {
        Log.w(TAG, "Could not tell whether $uri is still in the gallery", it)
        Presence.UNKNOWN
    }

    private fun sizeOf(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.Video.Media.SIZE), null, null, null)
            ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
    }.getOrNull()
}
