package com.tennispro.core.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KalmanTracker2DTest {

    @Test
    fun `tracks a constant-velocity path closely given exact measurements`() {
        val tracker = KalmanTracker2D(initialX = 0.0, initialY = 0.0)
        val dt = 1.0
        val trueVx = 10.0
        val trueVy = -4.0

        var trueX = 0.0
        var trueY = 0.0
        repeat(10) { step ->
            trueX += trueVx * dt
            trueY += trueVy * dt
            tracker.predict(dt)
            tracker.correct(trueX, trueY)
            if (step > 2) {
                // Give the filter a few steps to converge before checking tightly.
                assertEquals(trueX, tracker.x, 1.0)
                assertEquals(trueY, tracker.y, 1.0)
            }
        }

        assertEquals(trueVx, tracker.vx, 1.5)
        assertEquals(trueVy, tracker.vy, 1.5)
    }

    @Test
    fun `predict alone extrapolates through a missed detection`() {
        val tracker = KalmanTracker2D(initialX = 0.0, initialY = 0.0, initialVx = 5.0, initialVy = 0.0)
        tracker.predict(1.0)
        tracker.correct(5.0, 0.0)
        tracker.predict(1.0)
        tracker.correct(10.0, 0.0)

        // Detection missed this frame — predict-only, no correct().
        tracker.predict(1.0)

        assertEquals(15.0, tracker.x, 1.0)
        assertEquals(0.0, tracker.y, 1.0)
    }

    @Test
    fun `gateDistance is near zero once the tracker has converged to a point`() {
        val tracker = KalmanTracker2D(initialX = 3.0, initialY = 4.0, initialVx = 0.0, initialVy = 0.0)
        repeat(5) {
            tracker.predict(1.0)
            tracker.correct(3.0, 4.0)
        }
        assertTrue(tracker.gateDistance(3.0, 4.0) < 1.0)
        assertTrue("an implausible jump should read as a large gate distance", tracker.gateDistance(500.0, 500.0) > 100.0)
    }

    @Test
    fun `starts exactly at the given initial state before any predict or correct`() {
        val tracker = KalmanTracker2D(initialX = 7.0, initialY = -2.0, initialVx = 1.5, initialVy = -0.5)
        assertEquals(7.0, tracker.x, 0.0)
        assertEquals(-2.0, tracker.y, 0.0)
        assertEquals(1.5, tracker.vx, 0.0)
        assertEquals(-0.5, tracker.vy, 0.0)
    }
}
