package com.tennispro.core.court

import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceLineCallTest {

    private fun call(x: Double, y: Double, serverX: Double = 2.0, format: CourtFormat = CourtFormat.SINGLES, error: Double = 0.05) =
        ServiceLineCall.call(CourtPoint(x.toFloat(), y.toFloat()), serverX, format, errorXMeters = error, errorYMeters = error)

    @Test
    fun `serving from the left, a ball deep in the far right box is in, decided by the nearest line`() {
        val result = call(6.0, 16.0)

        assertEquals(CallVerdict.IN, result.verdict)
        // Centre line's box edge is at 4.115 - 0.025 - 0.02 = 4.07.
        assertEquals(BoxEdge.CENTRE_LINE, result.edge)
        assertEquals(6.0 - 4.07, result.marginMeters, 0.001)
    }

    @Test
    fun `a ball beyond the service line is out long by its distance`() {
        val result = call(6.0, 19.0)

        assertEquals(CallVerdict.OUT, result.verdict)
        assertEquals(BoxEdge.SERVICE_LINE, result.edge)
        assertEquals(-(19.0 - 18.305), result.marginMeters, 0.001)
    }

    @Test
    fun `landing in the other box is out over the centre line`() {
        val result = call(3.0, 15.0)

        assertEquals(CallVerdict.OUT, result.verdict)
        assertEquals(BoxEdge.CENTRE_LINE, result.edge)
        assertEquals(-(4.07 - 3.0), result.marginMeters, 0.001)
    }

    @Test
    fun `serving from the right targets the far left box`() {
        assertEquals(CallVerdict.IN, call(2.0, 15.0, serverX = 6.0).verdict)
        assertEquals(CallVerdict.OUT, call(6.0, 15.0, serverX = 6.0).verdict)
    }

    @Test
    fun `a ball touching the outside of the service line is in when the measurement is exact`() {
        // 1 cm past the line's outer edge is still within the ball's contact patch.
        val result = call(6.0, 18.295, error = 0.0)
        assertEquals(CallVerdict.IN, result.verdict)
    }

    @Test
    fun `a margin inside the error band is too close to call, either side of the line`() {
        assertEquals(CallVerdict.TOO_CLOSE, call(6.0, 18.2, error = 0.15).verdict)
        assertEquals(CallVerdict.TOO_CLOSE, call(6.0, 18.4, error = 0.15).verdict)
    }

    @Test
    fun `each line is judged against its own axis's uncertainty`() {
        // 30 cm inside the service line, but only 5 cm lengthwise precision: a clear call.
        val clear = ServiceLineCall.call(CourtPoint(6.0f, 18.0f), 2.0, CourtFormat.SINGLES, errorXMeters = 0.5, errorYMeters = 0.05)
        assertEquals(CallVerdict.IN, clear.verdict)
        // Same ball with poor lengthwise precision: no call.
        val unclear = ServiceLineCall.call(CourtPoint(6.0f, 18.0f), 2.0, CourtFormat.SINGLES, errorXMeters = 0.05, errorYMeters = 0.5)
        assertEquals(CallVerdict.TOO_CLOSE, unclear.verdict)
    }

    @Test
    fun `doubles calibration uses the singles sidelines inside its wider origin`() {
        // Doubles origin: singles sidelines at 1.37 and 9.60, centre at 5.485. Serving from the right.
        assertEquals(CallVerdict.IN, call(3.0, 15.0, serverX = 7.0, format = CourtFormat.DOUBLES).verdict)
        val wide = call(1.0, 15.0, serverX = 7.0, format = CourtFormat.DOUBLES)
        assertEquals(CallVerdict.OUT, wide.verdict)
        assertEquals(BoxEdge.SIDELINE, wide.edge)
    }

    @Test
    fun `long and wide at once is decided by the larger miss`() {
        val result = call(9.5, 19.0)
        assertEquals(CallVerdict.OUT, result.verdict)
        assertEquals(BoxEdge.SIDELINE, result.edge)
    }
}
