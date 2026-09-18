package com.tennispro.phone.score

import android.content.Context
import android.util.Log
import com.tennispro.core.scoring.MatchConfig
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

    private val configFile: File get() = File(context.filesDir, CONFIG_FILE_NAME)

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

    /**
     * The format of the last match started, kept in its own file so it survives
     * [clear]. A match started from the watch has no way to carry a
     * [MatchConfig] — the wrist is one tap surface — so it inherits whatever was
     * last chosen on the phone, and falls back to the defaults on a fresh install.
     */
    fun lastConfig(): MatchConfig {
        val f = configFile
        if (!f.exists()) return MatchConfig()
        return runCatching { MatchStateCodec.decodeConfig(f.readText()) }
            .onFailure { Log.w(TAG, "Could not read last match config", it) }
            .getOrNull() ?: MatchConfig()
    }

    fun saveLastConfig(config: MatchConfig) {
        runCatching { configFile.writeText(MatchStateCodec.encodeConfig(config)) }
            .onFailure { Log.w(TAG, "Could not persist last match config", it) }
    }

    private companion object {
        const val TAG = "ScoreStorage"
        const val FILE_NAME = "current_match.json"
        const val CONFIG_FILE_NAME = "last_match_config.json"
    }
}
