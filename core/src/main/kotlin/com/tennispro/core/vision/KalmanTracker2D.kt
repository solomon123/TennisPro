package com.tennispro.core.vision

/**
 * A constant-velocity 2D Kalman filter over pixel positions.
 *
 * State is `[x, y, vx, vy]`. Constant-velocity, not constant-acceleration —
 * a gravity-aware model would track a serve's parabola more precisely, but
 * this is a first cut for a short (sub-second) window, the same "first cut,
 * not a measurement" spirit as `wear/Haptics.kt`'s waveforms. [predict]
 * carries the trajectory through a missed detection; [correct] folds in a
 * real one. [gateDistance] is what a caller uses to reject an implausible
 * detection (e.g. a different blob entirely) rather than let it corrupt the
 * track — see [BallDetector], which returns every plausible blob per frame
 * and leaves picking the right one to whoever is holding the tracker.
 *
 * Process/measurement noise use a plain diagonal model rather than the
 * theoretically-fuller correlated white-noise-acceleration covariance — a
 * deliberate simplification for a well-understood algorithm rather than
 * unverified precision; correct enough to smooth noisy blob centroids over a
 * handful of frames, which is all this needs to do.
 */
class KalmanTracker2D(
    initialX: Double,
    initialY: Double,
    initialVx: Double = 0.0,
    initialVy: Double = 0.0,
    private val processNoise: Double = 4.0,
    private val measurementNoise: Double = 9.0,
) {
    private var state = doubleArrayOf(initialX, initialY, initialVx, initialVy)
    private var covariance = identity4(100.0)

    val x: Double get() = state[0]
    val y: Double get() = state[1]
    val vx: Double get() = state[2]
    val vy: Double get() = state[3]

    /** Advances the state by [dtSeconds] with no new measurement. */
    fun predict(dtSeconds: Double) {
        val f = transition(dtSeconds)
        state = matVec(f, state)

        val q = processNoiseMatrix(dtSeconds)
        val fp = matMul(f, covariance)
        val fpFt = matMul(fp, transpose(f))
        covariance = addMat(fpFt, q)
    }

    /** Folds in a real detection at the current time (call [predict] first to advance time). */
    fun correct(measuredX: Double, measuredY: Double) {
        // H selects [x, y] from the state, so H P H^T is just P's top-left 2x2 block.
        val s00 = covariance[0][0] + measurementNoise
        val s01 = covariance[0][1]
        val s10 = covariance[1][0]
        val s11 = covariance[1][1] + measurementNoise
        val det = s00 * s11 - s01 * s10
        if (det == 0.0) return // singular — skip the update rather than divide by zero

        val invS00 = s11 / det
        val invS01 = -s01 / det
        val invS10 = -s10 / det
        val invS11 = s00 / det

        // K = P H^T S^-1 — P H^T is just the first two columns of P (4x2).
        val k = Array(4) { row ->
            doubleArrayOf(
                covariance[row][0] * invS00 + covariance[row][1] * invS10,
                covariance[row][0] * invS01 + covariance[row][1] * invS11,
            )
        }

        val innovationX = measuredX - state[0]
        val innovationY = measuredY - state[1]
        for (i in 0 until 4) {
            state[i] += k[i][0] * innovationX + k[i][1] * innovationY
        }

        // P = P - K H P — H P is just the first two rows of P (2x4).
        val hp = arrayOf(covariance[0].copyOf(), covariance[1].copyOf())
        val newCovariance = Array(4) { row -> DoubleArray(4) }
        for (row in 0 until 4) {
            for (col in 0 until 4) {
                newCovariance[row][col] = covariance[row][col] - (k[row][0] * hp[0][col] + k[row][1] * hp[1][col])
            }
        }
        covariance = newCovariance
    }

    /** Mahalanobis-free, plain Euclidean distance from the current predicted position — good enough to gate outliers. */
    fun gateDistance(measuredX: Double, measuredY: Double): Double =
        kotlin.math.hypot(measuredX - state[0], measuredY - state[1])

    private fun transition(dt: Double): Array<DoubleArray> = arrayOf(
        doubleArrayOf(1.0, 0.0, dt, 0.0),
        doubleArrayOf(0.0, 1.0, 0.0, dt),
        doubleArrayOf(0.0, 0.0, 1.0, 0.0),
        doubleArrayOf(0.0, 0.0, 0.0, 1.0),
    )

    private fun processNoiseMatrix(dt: Double): Array<DoubleArray> {
        val positionVariance = processNoise * dt * dt
        val velocityVariance = processNoise
        return arrayOf(
            doubleArrayOf(positionVariance, 0.0, 0.0, 0.0),
            doubleArrayOf(0.0, positionVariance, 0.0, 0.0),
            doubleArrayOf(0.0, 0.0, velocityVariance, 0.0),
            doubleArrayOf(0.0, 0.0, 0.0, velocityVariance),
        )
    }

    private fun identity4(scale: Double): Array<DoubleArray> = Array(4) { i -> DoubleArray(4) { j -> if (i == j) scale else 0.0 } }

    private fun matMul(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        val rows = a.size
        val inner = b.size
        val cols = b[0].size
        return Array(rows) { i ->
            DoubleArray(cols) { j ->
                var sum = 0.0
                for (k in 0 until inner) sum += a[i][k] * b[k][j]
                sum
            }
        }
    }

    private fun matVec(a: Array<DoubleArray>, v: DoubleArray): DoubleArray =
        DoubleArray(a.size) { i -> a[i].indices.sumOf { j -> a[i][j] * v[j] } }

    private fun transpose(a: Array<DoubleArray>): Array<DoubleArray> =
        Array(a[0].size) { i -> DoubleArray(a.size) { j -> a[j][i] } }

    private fun addMat(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> =
        Array(a.size) { i -> DoubleArray(a[i].size) { j -> a[i][j] + b[i][j] } }
}
