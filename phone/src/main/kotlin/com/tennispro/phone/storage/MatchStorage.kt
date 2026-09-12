package com.tennispro.phone.storage

import android.content.Context
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
    val sizeBytes: Long get() = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
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

    fun deleteSession(session: MatchSession): Boolean = session.dir.deleteRecursively()

    /** Total bytes used by all sessions, for the "free up space" affordance. */
    fun totalBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

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
