package com.tennispro.core.court

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

enum class CallVerdict { IN, OUT, TOO_CLOSE }

/** The box edge a call was decided by. [NET] only for a ball landing short, on the wrong side of the net line. */
enum class BoxEdge { SERVICE_LINE, CENTRE_LINE, SIDELINE, NET }

/**
 * A service-line call, per docs/ACCURACY.md: never a bare IN/OUT. [marginMeters]
 * is the ball's distance to the edge that decides the call — positive inside
 * the box, negative outside — and when that is within [errorBandMeters] the
 * verdict is [CallVerdict.TOO_CLOSE]: "an honest abstention is worth more than
 * a confident guess."
 */
data class ServiceCall(
    val verdict: CallVerdict,
    val marginMeters: Double,
    val errorBandMeters: Double,
    val edge: BoxEdge,
)

/**
 * Calls a serve's bounce against the service box it had to land in.
 *
 * The target box is diagonally opposite the server: serving from the left of
 * the centre mark (as the camera, behind the server, sees it) means the far
 * right box, and vice versa. Its edges are widened by what still counts as the
 * ball touching a line — the rules call a ball in if any part of it touches
 * the line: court dimensions run to the lines' outer edges, the centre service
 * line (5 cm wide) belongs to both boxes, and the ball's contact patch reaches
 * [BALL_CONTACT_RADIUS_M] beyond the point the bounce is measured at.
 *
 * Coordinates are the calibration's own: the near-left corner of the
 * calibrated [CourtFormat] is the origin (see [CalibrationPoints]).
 */
object ServiceLineCall {

    /**
     * @param errorXMeters / [errorYMeters] uncertainty of the bounce position
     *   across and along the court. Far down the court from a behind-baseline
     *   camera a pixel covers far more court lengthwise than across, so the two
     *   differ a lot and each line is judged against its own.
     */
    fun call(bounce: CourtPoint, serverX: Double, format: CourtFormat, errorXMeters: Double, errorYMeters: Double): ServiceCall {
        val singlesLeft = if (format == CourtFormat.SINGLES) 0.0 else (CourtDimensions.DOUBLES_WIDTH_M - CourtDimensions.SINGLES_WIDTH_M) / 2
        val singlesRight = singlesLeft + CourtDimensions.SINGLES_WIDTH_M
        val centre = CourtDimensions.widthFor(format) / 2
        val net = CourtDimensions.LENGTH_M / 2
        val serviceLine = net + CourtDimensions.SERVICE_LINE_FROM_NET_M

        val servingFromLeft = serverX < centre
        val left: Double
        val right: Double
        val leftEdge: BoxEdge
        val rightEdge: BoxEdge
        if (servingFromLeft) {
            left = centre - CENTRE_LINE_HALF_WIDTH_M - BALL_CONTACT_RADIUS_M
            right = singlesRight + BALL_CONTACT_RADIUS_M
            leftEdge = BoxEdge.CENTRE_LINE
            rightEdge = BoxEdge.SIDELINE
        } else {
            left = singlesLeft - BALL_CONTACT_RADIUS_M
            right = centre + CENTRE_LINE_HALF_WIDTH_M + BALL_CONTACT_RADIUS_M
            leftEdge = BoxEdge.SIDELINE
            rightEdge = BoxEdge.CENTRE_LINE
        }
        val far = serviceLine + BALL_CONTACT_RADIUS_M

        val x = bounce.xMeters.toDouble()
        val y = bounce.yMeters.toDouble()

        val margin: Double
        val band: Double
        val edge: BoxEdge
        if (x in left..right && y in net..far) {
            // Inside: the nearest line decides. The net side isn't a line call.
            val options = listOf(
                Triple(x - left, leftEdge, errorXMeters),
                Triple(right - x, rightEdge, errorXMeters),
                Triple(far - y, BoxEdge.SERVICE_LINE, errorYMeters),
            )
            val nearest = options.minBy { it.first }
            margin = nearest.first
            edge = nearest.second
            band = nearest.third
        } else {
            val dx = max(0.0, max(left - x, x - right))
            val dy = max(0.0, max(y - far, net - y))
            margin = -hypot(dx, dy)
            edge = if (dy >= dx) {
                if (y > far) BoxEdge.SERVICE_LINE else BoxEdge.NET
            } else {
                if (x < left) leftEdge else rightEdge
            }
            // Uncertainty of the distance itself, along the direction to the box.
            band = sqrt((dx * errorXMeters) * (dx * errorXMeters) + (dy * errorYMeters) * (dy * errorYMeters)) / abs(margin)
        }

        val verdict = when {
            abs(margin) <= band -> CallVerdict.TOO_CLOSE
            margin > 0 -> CallVerdict.IN
            else -> CallVerdict.OUT
        }
        return ServiceCall(verdict, margin, band, edge)
    }

    /** Half of the 5 cm centre service line, which belongs to both boxes. */
    const val CENTRE_LINE_HALF_WIDTH_M = 0.025

    /** How far a bouncing ball's contact patch reaches from the point its bounce is measured at. */
    const val BALL_CONTACT_RADIUS_M = 0.02
}
