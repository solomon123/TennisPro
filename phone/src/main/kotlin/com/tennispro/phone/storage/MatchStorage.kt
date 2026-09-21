package com.tennispro.phone.storage

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.tennispro.core.court.CalibrationPoints
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A user-marked moment, as an offset into the session's video. */
@Serializable
data class Bookmark(
    val offsetMs: Long,
    val label: String,
    val createdAtEpochMs: Long,
)

/** On-disk metadata for one recorded match. */
@Serializable
data class SessionMeta(
    val id: String,
    val startedAtEpochMs: Long,
    /** Wall-clock recording length we observed; null while still recording. */
    val durationMs: Long? = null,
    val resolution: String? = null,
    val frameRate: Int? = null,
    /**
     * Where the video went once it was published to the gallery, as a MediaStore
     * `content://` URI. Null for a recording still being written, one made before
     * gallery export existed, or one whose export failed — in all three the file
     * under [MatchSession.dir] is still the video.
     */
    val videoUri: String? = null,
    /** Size of the published video, so storage totals stay right once it has left [MatchSession.dir]. */
    val videoBytes: Long? = null,
)

/**
 * One serve found by [com.tennispro.phone.vision.ServeScanner]. A net fault
 * has no speed: the flight never reached the ground past the net, which is
 * what the measurement needs.
 */
@Serializable
data class DetectedServe(
    val contactMs: Long,
    val speedKmh: Double? = null,
    val errorBandPercent: Double? = null,
    val netFault: Boolean = false,
    /** [com.tennispro.core.court.CallVerdict] name: IN, OUT or TOO_CLOSE. Null for net faults, and for scans before calls existed. */
    val callVerdict: String? = null,
    /** Distance to the deciding line: positive inside the box, negative outside. */
    val callMarginMeters: Double? = null,
    val callErrorMeters: Double? = null,
    /** [com.tennispro.core.court.BoxEdge] name. */
    val callEdge: String? = null,
    val bounceXMeters: Double? = null,
    val bounceYMeters: Double? = null,
    /** The court this serve was measured against, found in the recording at the serve. Null for scans before 2026-09-12. */
    val court: CalibrationPoints? = null,
)

/** The result of scanning one recording for serves; [error] set if the scan couldn't run at all. */
@Serializable
data class SessionServes(
    val scannedAtEpochMs: Long,
    val serves: List<DetectedServe>,
    val error: String? = null,
    /** The court found near the recording's start, used for any serve where it couldn't be found again. */
    val court: CalibrationPoints? = null,
)

/** A session as presented to the UI: metadata plus what is actually on disk. */
data class MatchSession(
    val meta: SessionMeta,
    val dir: File,
    val videoFile: File,
    val bookmarks: List<Bookmark>,
    val serves: SessionServes? = null,
) {
    /**
     * Everything this recording occupies, wherever it lives. Once the video is in
     * the gallery it is no longer under [dir], but it is still this recording's
     * storage and deleting the recording still frees it.
     */
    val sizeBytes: Long
        get() = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } + (meta.videoBytes ?: 0L)

    /** The gallery entry holding this recording's video, if it was published. */
    val galleryUri: Uri? get() = meta.videoUri?.let { runCatching { Uri.parse(it) }.getOrNull() }

    /** True once the video lives in the gallery rather than in [dir]. */
    val inGallery: Boolean get() = galleryUri != null
}

/**
 * Flat file storage for match recordings.
 *
 * Everything lives under app-specific external storage
 * (`Android/data/com.tennisreplay/files/Movies/sessions/<id>/`). That choice means no
 * storage permission is needed on any supported API level and the files are still
 * reachable over USB or the Files app for pulling footage onto a laptop, which is
 * how the computer-vision work in Phases 3-4 will get its test data.
 *
 * The cost is that recordings do not appear in the gallery. Exporting a marked clip
 * to MediaStore so it shows up in Photos is deliberately deferred to the clip-export
 * phase — full raw files are what we want on disk right now.
 */
class MatchStorage(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val root: File
        get() = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: context.filesDir,
            "sessions",
        ).apply { mkdirs() }

    fun newSessionId(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss", Locale.US).format(Date())

    fun createSession(id: String = newSessionId()): MatchSession {
        val dir = File(root, id).apply { mkdirs() }
        val meta = SessionMeta(id = id, startedAtEpochMs = System.currentTimeMillis())
        writeMeta(dir, meta)
        return MatchSession(meta, dir, File(dir, VIDEO_NAME), emptyList())
    }

    fun finalizeSession(session: MatchSession, durationMs: Long, resolution: String?, frameRate: Int?) {
        val updated = session.meta.copy(
            durationMs = durationMs,
            resolution = resolution,
            frameRate = frameRate,
        )
        writeMeta(session.dir, updated)
    }

    /**
     * Appends a bookmark as one JSON object per line. Append-only because this is
     * written mid-recording, sometimes from a watch message: a torn rewrite of a
     * whole file would cost the whole session's marks, a torn append costs one line.
     */
    fun addBookmark(session: MatchSession, offsetMs: Long, label: String): Bookmark {
        val bookmark = Bookmark(offsetMs, label, System.currentTimeMillis())
        runCatching {
            File(session.dir, BOOKMARKS_NAME).appendText(
                Json.encodeToString(Bookmark.serializer(), bookmark) + "\n",
            )
        }.onFailure { Log.w(TAG, "Could not append bookmark", it) }
        return bookmark
    }

    /** Replaces the session's serve scan result (written once per scan, so a whole-file write is fine). */
    fun writeServes(session: MatchSession, serves: SessionServes) {
        runCatching {
            File(session.dir, SERVES_NAME).writeText(json.encodeToString(SessionServes.serializer(), serves))
        }.onFailure { Log.w(TAG, "Could not write serves", it) }
    }

    fun findSession(id: String): MatchSession? = File(root, id).takeIf { it.isDirectory }?.let { readSession(it) }

    fun listSessions(): List<MatchSession> =
        (root.listFiles { f -> f.isDirectory } ?: emptyArray())
            .mapNotNull { dir -> readSession(dir) }
            .sortedByDescending { it.meta.startedAtEpochMs }

    /**
     * Deletes the recording wherever it lives: the session directory, and the
     * gallery copy if the video was published there. A recording the user
     * deleted in the app must not stay in their gallery.
     */
    fun deleteSession(session: MatchSession): Boolean {
        session.galleryUri?.let { GalleryVideos.delete(context, it) }
        return session.dir.deleteRecursively()
    }

    /**
     * Moves this session's video into the gallery and records where it went.
     * A no-op if it is already published or there is nothing to publish; safe to
     * call again after a failed attempt, which leaves the local file in place.
     */
    fun exportToGallery(session: MatchSession): MatchSession {
        if (session.meta.videoUri != null) return session
        val published = GalleryVideos.publish(
            context = context,
            file = videoFileFor(session),
            displayName = "TennisReplay ${session.meta.id}.mp4",
            takenAtEpochMs = session.meta.startedAtEpochMs,
        ) ?: return session

        val meta = session.meta.copy(videoUri = published.uri.toString(), videoBytes = published.bytes)
        writeMeta(session.dir, meta)
        return session.copy(meta = meta)
    }

    /**
     * Publishes every recording whose video is still sitting in its session
     * directory, and returns how many moved. Covers recordings made before the
     * app published to the gallery at all, and any export that failed at the
     * time — a failed publish leaves the local file exactly where it was, so
     * retrying later is always safe.
     */
    fun exportPending(): Int =
        listSessions()
            .filter { it.meta.videoUri == null && videoFileFor(it).isFile }
            .count { exportToGallery(it).meta.videoUri != null }

    /**
     * Drops recordings whose video the user deleted from their gallery. Without
     * this they linger as entries that list and scan but will not play.
     *
     * Deleting the video is taken as deleting the recording, so its bookmarks
     * and serve results go too. Only a definite absence counts: if MediaStore
     * cannot be asked, the recording is left alone, because the cost of being
     * wrong here is destroying a match the user still has.
     */
    fun pruneMissingVideos(): Int =
        listSessions()
            .filter { session ->
                val uri = session.galleryUri ?: return@filter false
                GalleryVideos.presence(context, uri) == GalleryVideos.Presence.MISSING
            }
            .count { session ->
                Log.i(TAG, "Dropping ${session.meta.id}: its video is no longer in the gallery")
                deleteSession(session)
            }

    /**
     * Brings the recordings in line with the gallery, in both directions: anything
     * not yet published is moved there, anything the user deleted from the gallery
     * is dropped here. Returns true if either changed something, so a list showing
     * these sessions knows to reload.
     */
    fun reconcileWithGallery(): Boolean {
        val exported = exportPending()
        val dropped = pruneMissingVideos()
        return exported > 0 || dropped > 0
    }

    /**
     * Where to read this session's video from, for playback, scanning or sharing.
     * A published recording is a `content://` URI; one not yet exported is still
     * the file in the session directory. Every reader takes both.
     */
    fun videoUriFor(session: MatchSession): Uri =
        session.galleryUri ?: Uri.fromFile(videoFileFor(session))

    /**
     * Total bytes used by all sessions, for the "free up space" affordance.
     * Sums each session rather than walking the directory, so videos published
     * to the gallery — no longer under [root] but still this app's recordings,
     * and still freed by deleting them — are counted.
     */
    fun totalBytes(): Long = listSessions().sumOf { it.sizeBytes }

    /** Free bytes on the volume the recordings live on. */
    fun freeBytes(): Long = runCatching { root.usableSpace }.getOrDefault(0L)

    fun videoFileFor(session: MatchSession): File = File(session.dir, VIDEO_NAME)

    private fun readSession(dir: File): MatchSession? {
        val metaFile = File(dir, META_NAME)
        val meta = runCatching {
            json.decodeFromString(SessionMeta.serializer(), metaFile.readText())
        }.getOrElse {
            // A session directory with no readable metadata is still worth listing
            // so the user can delete it and reclaim the space.
            Log.w(TAG, "Unreadable metadata in ${dir.name}", it)
            SessionMeta(id = dir.name, startedAtEpochMs = dir.lastModified())
        }
        return MatchSession(
            meta = meta,
            dir = dir,
            videoFile = File(dir, VIDEO_NAME),
            bookmarks = readBookmarks(dir),
            serves = readServes(dir),
        )
    }

    private fun readServes(dir: File): SessionServes? {
        val file = File(dir, SERVES_NAME)
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(SessionServes.serializer(), file.readText()) }
            .onFailure { Log.w(TAG, "Unreadable serves in ${dir.name}", it) }
            .getOrNull()
    }

    private fun readBookmarks(dir: File): List<Bookmark> {
        val file = File(dir, BOOKMARKS_NAME)
        if (!file.exists()) return emptyList()
        return file.useLines { lines ->
            lines.filter { it.isNotBlank() }
                .mapNotNull { line ->
                    runCatching { Json.decodeFromString(Bookmark.serializer(), line) }.getOrNull()
                }
                .toList()
        }.sortedBy { it.offsetMs }
    }

    private fun writeMeta(dir: File, meta: SessionMeta) {
        runCatching {
            File(dir, META_NAME).writeText(json.encodeToString(SessionMeta.serializer(), meta))
        }.onFailure { Log.w(TAG, "Could not write session metadata", it) }
    }

    private companion object {
        const val TAG = "MatchStorage"
        const val VIDEO_NAME = "match.mp4"
        const val META_NAME = "session.json"
        const val BOOKMARKS_NAME = "bookmarks.jsonl"
        const val SERVES_NAME = "serves.json"
    }
}
