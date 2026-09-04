package com.tennispro.core.court

import kotlinx.serialization.json.Json

/** JSON codec for [CalibrationPoints], the same shape as [com.tennispro.core.scoring.MatchStateCodec]. */
object CalibrationCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(points: CalibrationPoints): String =
        json.encodeToString(CalibrationPoints.serializer(), points)

    fun decode(text: String): CalibrationPoints? =
        runCatching { json.decodeFromString(CalibrationPoints.serializer(), text) }.getOrNull()
}
