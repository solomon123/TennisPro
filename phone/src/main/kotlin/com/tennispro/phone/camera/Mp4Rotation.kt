package com.tennispro.phone.camera

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Rewrites an MP4's video-track rotation to identity (no rotation) in place.
 *
 * Exists because CameraX's `VideoCapture.setTargetRotation` does not control
 * the front camera's recorded-file rotation on this hardware — confirmed with
 * `ffprobe`: front and back recordings came out of `tryBind` with the
 * identical container rotation regardless of what was requested at capture
 * time, correct for the back camera and exactly 180 degrees wrong for the
 * front. See the note on `newVideoCapture` in `RecordingService.tryBind`.
 *
 * This is a container-level fix, not a re-encode: the rotation lives in the
 * `tkhd` box's 3x3 transformation matrix (the ISO BMFF / QuickTime standard
 * location — see ISO/IEC 14496-12), a fixed 36-byte field at a known offset.
 * Overwriting just those bytes in place is exact and lossless, and — unlike
 * reading the whole file into memory — safe for a two-hour match recording.
 */
object Mp4Rotation {

    private const val TAG = "Mp4Rotation"

    // 16.16 fixed-point identity matrix: [1 0 0 / 0 1 0 / 0 0 1].
    private val IDENTITY_MATRIX: ByteArray = ByteBuffer.allocate(36).order(ByteOrder.BIG_ENDIAN).apply {
        putInt(0x00010000); putInt(0); putInt(0)
        putInt(0); putInt(0x00010000); putInt(0)
        putInt(0); putInt(0); putInt(0x40000000)
    }.array()

    private val CONTAINER_BOX_TYPES = setOf("moov", "trak", "mdia", "minf", "stbl", "udta", "edts")

    fun stripVideoRotation(file: File) {
        RandomAccessFile(file, "rw").use { raf ->
            val fixed = fixRotationInRange(raf, 0L, file.length())
            Log.i(TAG, "${file.name}: ${if (fixed) "rotation cleared" else "no non-identity tkhd found"}")
        }
    }

    /** Walks sibling boxes in `[start, end)`, recursing into container boxes. Returns true if any tkhd was changed. */
    private fun fixRotationInRange(raf: RandomAccessFile, start: Long, end: Long): Boolean {
        var pos = start
        var changedAny = false
        while (pos < end) {
            raf.seek(pos)
            val size = readUInt32(raf)
            val type = readFourCc(raf)

            var headerLen = 8L
            var boxSize = size
            if (size == 1L) {
                boxSize = readUInt64(raf)
                headerLen = 16L
            } else if (size == 0L) {
                boxSize = end - pos
            }
            if (boxSize < headerLen) break // malformed box, bail out rather than loop forever

            val contentStart = pos + headerLen

            if (type == "tkhd") {
                if (fixTkhdMatrix(raf, contentStart)) changedAny = true
            } else if (type in CONTAINER_BOX_TYPES) {
                if (fixRotationInRange(raf, contentStart, pos + boxSize)) changedAny = true
            }

            pos += boxSize
        }
        return changedAny
    }

    /** Returns true if this tkhd's matrix was non-identity and got overwritten. */
    private fun fixTkhdMatrix(raf: RandomAccessFile, contentStart: Long): Boolean {
        raf.seek(contentStart)
        val version = raf.readUnsignedByte()
        // version(1) + flags(3) + {creation,modification}time + track_ID + reserved + duration,
        // sized per version, then reserved(8) + layer(2) + alt_group(2) + volume(2) + reserved(2).
        val fixedFieldsLen = if (version == 1) (8 + 8 + 4 + 4 + 8) else (4 + 4 + 4 + 4 + 4)
        val matrixOffset = contentStart + 1 + 3 + fixedFieldsLen + 8 + 2 + 2 + 2 + 2

        raf.seek(matrixOffset)
        val current = ByteArray(36)
        raf.readFully(current)
        if (current.contentEquals(IDENTITY_MATRIX)) return false

        raf.seek(matrixOffset)
        raf.write(IDENTITY_MATRIX)
        return true
    }

    private fun readUInt32(raf: RandomAccessFile): Long {
        val b = ByteArray(4)
        raf.readFully(b)
        return ((b[0].toLong() and 0xFF) shl 24) or ((b[1].toLong() and 0xFF) shl 16) or
            ((b[2].toLong() and 0xFF) shl 8) or (b[3].toLong() and 0xFF)
    }

    private fun readUInt64(raf: RandomAccessFile): Long {
        val b = ByteArray(8)
        raf.readFully(b)
        var v = 0L
        for (byte in b) v = (v shl 8) or (byte.toLong() and 0xFF)
        return v
    }

    private fun readFourCc(raf: RandomAccessFile): String {
        val b = ByteArray(4)
        raf.readFully(b)
        return String(b, Charsets.US_ASCII)
    }
}
