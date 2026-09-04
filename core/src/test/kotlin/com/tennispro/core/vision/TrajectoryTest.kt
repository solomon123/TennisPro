package com.tennispro.core.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun point(frameIndex: Int, x: Double, y: Double, timeMs: Long = frameIndex * 16L) =
    TrackedPoint(frameIndex, timeMs, x, y)

class TrajectoryTest {

    @Test
    fun `contact index is where a slow toss becomes a fast serve`() {
        val points = listOf(
            point(0, 0.0, 0.0),
            point(1, 1.0, 0.0), // toss: slow, roughly constant
            point(2, 2.0, 0.0),
            point(3, 3.0, 0.0),
            point(4, 40.0, 5.0), // contact: sudden large jump
            point(5, 80.0, 12.0),
            point(6, 120.0, 22.0),
        )

        // Index 3, not 4: it's the hinge point shared by both segments — the last
        // point of the slow (pre-contact) segment and the first point of the fast
        // (post-contact) one — which is the frame closest to the actual contact.
        assertEquals(3, Trajectory.findContactIndex(points))
    }

    @Test
    fun `contact index is null with too few points to compare`() {
        assertNull(Trajectory.findContactIndex(listOf(point(0, 0.0, 0.0), point(1, 1.0, 0.0))))
    }

    @Test
    fun `bounce index is the interior vertex where descent turns into ascent`() {
        // Image y grows downward, so the vertex (bounce) is the frame with the largest y.
        val points = listOf(
            point(0, 0.0, 10.0),
            point(1, 10.0, 30.0),
            point(2, 20.0, 55.0),
            point(3, 30.0, 70.0), // bounce
            point(4, 40.0, 50.0),
            point(5, 50.0, 35.0),
        )

        assertEquals(3, Trajectory.findBounceIndex(points))
    }

    @Test
    fun `bounce index respects searchFrom`() {
        // The first, larger peak is before searchFrom and must be ignored.
        val points = listOf(
            point(0, 0.0, 100.0), // a taller peak, but before searchFrom
            point(1, 10.0, 20.0),
            point(2, 20.0, 40.0),
            point(3, 30.0, 60.0), // the vertex within [searchFrom, end)
            point(4, 40.0, 45.0),
            point(5, 50.0, 30.0),
        )

        assertEquals(3, Trajectory.findBounceIndex(points, searchFrom = 1))
    }

    @Test
    fun `bounce index is null when the trajectory is still descending at the end`() {
        val points = listOf(
            point(0, 0.0, 10.0),
            point(1, 10.0, 20.0),
            point(2, 20.0, 30.0), // monotonically increasing — no observed turnaround yet
        )
        assertNull(Trajectory.findBounceIndex(points))
    }

    @Test
    fun `bounce index is null when the peak is the very first point`() {
        val points = listOf(
            point(0, 0.0, 50.0), // already at the peak — no rise observed before it
            point(1, 10.0, 30.0),
            point(2, 20.0, 10.0),
        )
        assertNull(Trajectory.findBounceIndex(points))
    }
}
