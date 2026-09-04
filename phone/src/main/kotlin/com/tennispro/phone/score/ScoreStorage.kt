package com.tennispro.phone.score

import android.content.Context
import android.util.Log
import com.tennispro.core.scoring.MatchState
import com.tennispro.core.scoring.MatchStateCodec
import java.io.File

/**
 * Persists the one match currently being scored.
 *
 * A single fixed file, not a session directory like [com.tennispro.phone.storage.MatchStorage]:
 * there is only one score in play at a time (one watch on one wrist), which
 * mirrors the single latched [com.tennispro.core.protocol.WearPaths.MATCH_STATE]
 * DataItem pushed to the watch. Internal app storage is enough — nobody needs
 * to pull this file over USB the way they pull raw footage.
 */
class ScoreStorage(private val context: Context) {

    private val file: File get() = File(context.filesDir, FILE_NAME)

    fun load(): MatchState? {
        val f = file
        if (!f.exists()) return null
        return runCatching { MatchStateCodec.decodeState(f.readText()) }
            .onFailure { Log.w(TAG, "Could not read persisted match state", it) }
            .getOrNull()
    }

    fun save(state: MatchState) {
        runCatching { file.writeText(MatchStateCodec.encodeState(state)) }
            .onFailure { Log.w(TAG, "Could not persist match state", it) }
    }

    fun clear() {
        runCatching { file.delete() }
    }

    private companion object {
        const val TAG = "ScoreStorage"
        const val FILE_NAME = "current_match.json"
    }
}
