package com.tennispro.core.scoring

import kotlinx.serialization.json.Json

/**
 * JSON codec for the things that need to cross a process/device boundary:
 *
 *  - [MatchState] — the full point history — to and from disk, so an app
 *    restart mid-match does not lose the ability to undo.
 *  - [MatchProjection] — the display snapshot — over the Data Layer to the
 *    watch. Sending the full history there would be needless bytes for
 *    something the watch never needs to replay.
 *  - [MatchConfig] — the last format chosen — to disk, so a match started from
 *    the watch, which has no way to express a format, gets the one last used.
 *
 * String-based like [com.tennispro.core.protocol.WearCodec]'s reasoning: the
 * payloads are tiny, and `DataMap.putString` is the natural fit for a Data
 * Layer item, so there is no reason to round-trip through a byte array first.
 */
object MatchStateCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodeState(state: MatchState): String = json.encodeToString(MatchState.serializer(), state)

    fun decodeState(text: String): MatchState? =
        runCatching { json.decodeFromString(MatchState.serializer(), text) }.getOrNull()

    fun encodeProjection(projection: MatchProjection): String =
        json.encodeToString(MatchProjection.serializer(), projection)

    fun decodeProjection(text: String): MatchProjection? =
        runCatching { json.decodeFromString(MatchProjection.serializer(), text) }.getOrNull()

    fun encodeConfig(config: MatchConfig): String = json.encodeToString(MatchConfig.serializer(), config)

    fun decodeConfig(text: String): MatchConfig? =
        runCatching { json.decodeFromString(MatchConfig.serializer(), text) }.getOrNull()
}
