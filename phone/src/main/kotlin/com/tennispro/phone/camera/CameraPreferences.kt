package com.tennispro.phone.camera

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Persists which physical camera to record and calibrate with.
 *
 * Some fence/clamp mounts hold the phone screen-out for monitoring, which
 * puts the *front* camera facing the court instead of the back one — a real
 * mounting constraint some users hit, not a preference of convenience. A
 * single stored value, not per-session: like the mount itself, this is set
 * once and left alone.
 */
class CameraPreferences(private val context: Context) {

    private val file: File get() = File(context.filesDir, "camera_facing.txt")

    fun load(): CameraFacing {
        val text = runCatching { file.readText().trim() }.getOrNull()
        return text?.let { runCatching { CameraFacing.valueOf(it) }.getOrNull() } ?: CameraFacing.BACK
    }

    fun save(facing: CameraFacing) {
        runCatching { file.writeText(facing.name) }
            .onFailure { Log.w(TAG, "Could not persist camera facing", it) }
    }

    private companion object {
        const val TAG = "CameraPreferences"
    }
}
