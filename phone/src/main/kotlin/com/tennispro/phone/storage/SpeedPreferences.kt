package com.tennispro.phone.storage

import android.content.Context
import android.util.Log
import com.tennispro.core.vision.SpeedUnit
import java.io.File

/**
 * Persists whether serve speeds are shown in km/h or mph.
 *
 * Display only — see [SpeedUnit] for why every stored speed stays in km/h
 * whichever way this is set. A single stored value, like
 * [com.tennispro.phone.camera.CameraPreferences]: set once, left alone.
 */
class SpeedPreferences(private val context: Context) {

    private val file: File get() = File(context.filesDir, "speed_unit.txt")

    fun load(): SpeedUnit {
        val text = runCatching { file.readText().trim() }.getOrNull()
        return text?.let { runCatching { SpeedUnit.valueOf(it) }.getOrNull() } ?: SpeedUnit.KMH
    }

    fun save(unit: SpeedUnit) {
        runCatching { file.writeText(unit.name) }
            .onFailure { Log.w(TAG, "Could not persist speed unit", it) }
    }

    private companion object {
        const val TAG = "SpeedPreferences"
    }
}
