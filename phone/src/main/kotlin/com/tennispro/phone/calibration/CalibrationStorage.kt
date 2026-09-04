package com.tennispro.phone.calibration

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.tennispro.core.court.CalibrationCodec
import com.tennispro.core.court.CalibrationPoints
import java.io.File
import java.io.FileOutputStream

/**
 * Persists the one calibration currently in effect for this mount.
 *
 * A single fixed slot, not per-recording — mirrors
 * [com.tennispro.phone.score.ScoreStorage]'s reasoning: one camera stays
 * mounted in one spot across many recordings (see the README's Setup
 * section), so calibration is a property of the mount, not of any one
 * session. [reference_frame.jpg] exists only for the drift check — a small
 * downscaled JPEG, not the full-resolution frame the user actually calibrated
 * against.
 */
class CalibrationStorage(private val context: Context) {

    private val dir: File get() = File(context.filesDir, "calibration").apply { mkdirs() }
    private val pointsFile: File get() = File(dir, "calibration.json")
    private val referenceFrameFile: File get() = File(dir, "reference_frame.jpg")

    fun load(): CalibrationPoints? {
        if (!pointsFile.exists()) return null
        return runCatching { CalibrationCodec.decode(pointsFile.readText()) }
            .onFailure { Log.w(TAG, "Could not read calibration", it) }
            .getOrNull()
    }

    fun save(points: CalibrationPoints, referenceFrame: Bitmap) {
        runCatching { pointsFile.writeText(CalibrationCodec.encode(points)) }
            .onFailure { Log.w(TAG, "Could not persist calibration", it) }
        runCatching {
            FileOutputStream(referenceFrameFile).use { out ->
                referenceFrame.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
        }.onFailure { Log.w(TAG, "Could not persist calibration reference frame", it) }
    }

    fun loadReferenceFrame(): Bitmap? {
        if (!referenceFrameFile.exists()) return null
        return runCatching { android.graphics.BitmapFactory.decodeFile(referenceFrameFile.absolutePath) }
            .onFailure { Log.w(TAG, "Could not read calibration reference frame", it) }
            .getOrNull()
    }

    fun clear() {
        runCatching { pointsFile.delete() }
        runCatching { referenceFrameFile.delete() }
    }

    private companion object {
        const val TAG = "CalibrationStorage"
    }
}
