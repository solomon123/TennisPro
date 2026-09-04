package com.tennispro.phone.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.tennispro.core.court.CourtDimensions
import com.tennispro.core.court.CourtPoint
import com.tennispro.core.court.Homography
import com.tennispro.core.court.PixelPoint
import kotlin.math.min

/**
 * Displays [bitmap] letterboxed to fit the available space, reports taps in
 * the *bitmap's own* pixel space rather than the composable's layout space
 * (what [com.tennispro.core.court.CalibrationPoints] needs), draws markers for
 * [tappedPoints], and — once [homography] resolves — overlays the projected
 * court lines so the user can visually confirm calibration before saving.
 */
@Composable
fun CourtOverlayCanvas(
    bitmap: Bitmap,
    tappedPoints: List<PixelPoint>,
    homography: Homography?,
    courtWidthM: Float,
    onTap: (PixelPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(bitmap) {
                detectTapGestures { offset ->
                    val fit = fitRect(bitmap.width.toFloat(), bitmap.height.toFloat(), size.width.toFloat(), size.height.toFloat())
                    val bx = (offset.x - fit.offsetX) / fit.scale
                    val by = (offset.y - fit.offsetY) / fit.scale
                    if (bx in 0f..bitmap.width.toFloat() && by in 0f..bitmap.height.toFloat()) {
                        onTap(PixelPoint(bx, by))
                    }
                }
            },
    ) {
        val fit = fitRect(bitmap.width.toFloat(), bitmap.height.toFloat(), size.width, size.height)
        drawImage(
            image = imageBitmap,
            dstOffset = IntOffset(fit.offsetX.toInt(), fit.offsetY.toInt()),
            dstSize = IntSize((bitmap.width * fit.scale).toInt(), (bitmap.height * fit.scale).toInt()),
        )

        fun toCanvas(p: PixelPoint) = Offset(fit.offsetX + p.x * fit.scale, fit.offsetY + p.y * fit.scale)

        tappedPoints.forEach { p ->
            val c = toCanvas(p)
            drawCircle(color = Color.Yellow, radius = 14f, center = c, style = Stroke(width = 4f))
            drawCircle(color = Color.Yellow, radius = 4f, center = c)
        }

        if (homography != null) {
            courtOverlaySegments(courtWidthM).forEach { (a, b) ->
                drawLine(
                    color = Color.Cyan.copy(alpha = 0.85f),
                    start = toCanvas(homography.mapToPixel(a)),
                    end = toCanvas(homography.mapToPixel(b)),
                    strokeWidth = 3f,
                )
            }
        }
    }
}

/** Outer court outline plus net and both service lines — enough to eyeball calibration by. */
private fun courtOverlaySegments(courtWidthM: Float): List<Pair<CourtPoint, CourtPoint>> {
    val length = CourtDimensions.LENGTH_M.toFloat()
    val netY = length / 2f
    val nearServiceY = netY - CourtDimensions.SERVICE_LINE_FROM_NET_M.toFloat()
    val farServiceY = netY + CourtDimensions.SERVICE_LINE_FROM_NET_M.toFloat()
    val halfWidth = courtWidthM / 2f

    return listOf(
        CourtPoint(0f, 0f) to CourtPoint(courtWidthM, 0f),
        CourtPoint(courtWidthM, 0f) to CourtPoint(courtWidthM, length),
        CourtPoint(courtWidthM, length) to CourtPoint(0f, length),
        CourtPoint(0f, length) to CourtPoint(0f, 0f),
        CourtPoint(0f, netY) to CourtPoint(courtWidthM, netY),
        CourtPoint(0f, nearServiceY) to CourtPoint(courtWidthM, nearServiceY),
        CourtPoint(0f, farServiceY) to CourtPoint(courtWidthM, farServiceY),
        CourtPoint(halfWidth, nearServiceY) to CourtPoint(halfWidth, farServiceY),
    )
}

private data class FitRect(val scale: Float, val offsetX: Float, val offsetY: Float)

/** How a [srcW]x[srcH] image gets letterboxed ("fit center") into a [dstW]x[dstH] box. */
private fun fitRect(srcW: Float, srcH: Float, dstW: Float, dstH: Float): FitRect {
    val scale = min(dstW / srcW, dstH / srcH)
    val drawnW = srcW * scale
    val drawnH = srcH * scale
    return FitRect(scale, (dstW - drawnW) / 2f, (dstH - drawnH) / 2f)
}
