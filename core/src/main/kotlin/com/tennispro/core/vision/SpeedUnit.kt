package com.tennispro.core.vision

import kotlin.math.roundToInt

/**
 * How to show a serve speed.
 *
 * Speeds are measured, stored and compared in km/h throughout — [DetectedServe]
 * and [ServeOutcome.Measured] both carry km/h — and converted only where they
 * are shown. Keeping one unit in the data means a user switching units never
 * rewrites a recording's results, and a serve scanned under one setting reads
 * correctly under the other.
 */
enum class SpeedUnit(val label: String) {
    KMH("km/h"),
    MPH("mph"),
    ;

    fun fromKmh(kmh: Double): Double = when (this) {
        KMH -> kmh
        MPH -> kmh / KM_PER_MILE
    }

    /** Rounded to whole units: the measurement's error band is percent, not decimals. */
    fun format(kmh: Double): String = "${fromKmh(kmh).roundToInt()} $label"

    companion object {
        /** Exact by definition: a mile is 1609.344 m. */
        const val KM_PER_MILE = 1.609344
    }
}
