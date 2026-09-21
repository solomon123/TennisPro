package com.tennispro.core.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedUnitTest {

    @Test
    fun `km per hour is shown unchanged`() {
        assertEquals("162 km/h", SpeedUnit.KMH.format(162.0))
    }

    /**
     * The three serves read off the US Open board in the 2026-09-21 clip.
     * Converting back from the km/h the app would store has to land on the
     * number the stadium showed, or a speed shown in mph is not the same
     * measurement the app made.
     */
    @Test
    fun `the broadcast speeds convert back to the board's mph`() {
        for (mph in listOf(149, 141, 130)) {
            val storedKmh = mph * SpeedUnit.KM_PER_MILE
            assertEquals("$mph mph", SpeedUnit.MPH.format(storedKmh))
        }
    }

    @Test
    fun `a serve is the same speed either way`() {
        val kmh = 162.0
        assertEquals(kmh, SpeedUnit.MPH.fromKmh(kmh) * SpeedUnit.KM_PER_MILE, 1e-9)
    }

    @Test
    fun `rounding is to whole units, not truncation`() {
        // 100.7 km/h is 62.57 mph.
        assertEquals("63 mph", SpeedUnit.MPH.format(100.7))
        assertEquals("101 km/h", SpeedUnit.KMH.format(100.7))
    }
}
