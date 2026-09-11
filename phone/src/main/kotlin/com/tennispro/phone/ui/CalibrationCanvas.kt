package com.tennispro.phone.ui

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tennispro.core.court.CalibrationPoints
import com.tennispro.core.court.CourtDimensions
import com.tennispro.core.court.CourtFormat
import com.tennispro.core.court.Homography
import com.tennispro.core.court.PixelPoint
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private const val MIN_ZOOM = 0.5f // below fit, so a corner just outside the frame can still be placed
private const val MAX_ZOOM = 12f
private const val LOUPE_MAGNIFICATION = 3f
private val LOUPE_RADIUS = 70.dp
private val HIT_RADIUS = 36.dp

private val CORNER_LABELS = listOf("NL", "NR", "FL", "FR")

/**
 * Pan/zoom state for [CalibrationCanvas], hoisted so the surrounding
 * controls can offer a "Fit" button. [scale] maps bitmap pixels to screen
 * pixels and [origin] is where bitmap (0, 0) sits on screen; both are 0/zero
 * until the canvas has been laid out once.
 */
@Stable
class CalibrationViewport {
    var scale by mutableStateOf(0f)
        private set
    var origin by mutableStateOf(Offset.Zero)
        private set

    private var fitScale = 0f
    private var fitOrigin = Offset.Zero

    val isZoomed: Boolean
        get() = fitScale > 0f && (abs(scale - fitScale) > fitScale * 0.01f || (origin - fitOrigin).getDistance() > 2f)

    fun reset() {
        scale = fitScale
        origin = fitOrigin
    }

    internal fun fit(contentWidth: Int, contentHeight: Int, viewWidth: Int, viewHeight: Int) {
        val fit = fitRect(contentWidth.toFloat(), contentHeight.toFloat(), viewWidth.toFloat(), viewHeight.toFloat())
        fitScale = fit.scale
        fitOrigin = Offset(fit.offsetX, fit.offsetY)
        reset()
    }

    internal fun zoomBy(factor: Float, focus: Offset) {
        if (scale <= 0f) return
        val newScale = (scale * factor).coerceIn(fitScale * MIN_ZOOM, fitScale * MAX_ZOOM)
        origin = focus - (focus - origin) * (newScale / scale)
        scale = newScale
    }

    internal fun panBy(delta: Offset) {
        origin += delta
    }

    fun toScreen(p: PixelPoint) = Offset(origin.x + p.x * scale, origin.y + p.y * scale)

    fun toBitmap(o: Offset) = PixelPoint((o.x - origin.x) / scale, (o.y - origin.y) / scale)
}

/** A corner being placed ([index] = -1, a new one) or dragged, not yet committed. */
private data class ActiveCorner(val index: Int, val point: PixelPoint)

private enum class TouchMode { PLACE, MOVE, PAN }

/**
 * The calibration editor's image surface — the fix for the first field
 * test's "the frozen image is too small to hit the corners" feedback:
 *
 * - **Pinch** to zoom (up to 12x) and pan with two fingers; one finger pans
 *   too once all four corners are down.
 * - **Touch, slide, lift** places the next corner, with a magnifier loupe
 *   showing exactly where it will land, since the finger itself covers it.
 * - **Drag** an existing corner to move it, with the same loupe. The court
 *   grid re-projects live while dragging, so the fit can be judged against
 *   every painted line, not just the corners.
 *
 * Corners are reported in the bitmap's own pixel space and may lie outside
 * the bitmap — a baseline corner just out of frame can still be placed where
 * the lines would meet.
 */
@Composable
fun CalibrationCanvas(
    bitmap: Bitmap,
    points: List<PixelPoint>,
    format: CourtFormat,
    viewport: CalibrationViewport,
    onPointsChange: (List<PixelPoint>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    val currentPoints by rememberUpdatedState(points)
    val currentOnPointsChange by rememberUpdatedState(onPointsChange)
    var active by remember { mutableStateOf<ActiveCorner?>(null) }
    val labelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.YELLOW
            setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
        }
    }

    // What's drawn: committed corners with the in-progress one applied on top.
    val displayed = active?.let { a ->
        if (a.index in points.indices) points.toMutableList().also { it[a.index] = a.point } else points + a.point
    } ?: points
    val homography = if (displayed.size == 4) {
        Homography.fromCalibration(
            CalibrationPoints(
                format = format,
                frameWidth = bitmap.width,
                frameHeight = bitmap.height,
                nearLeft = displayed[0],
                nearRight = displayed[1],
                farLeft = displayed[2],
                farRight = displayed[3],
            ),
        )
    } else {
        null
    }
    val courtWidthM = CourtDimensions.widthFor(format).toFloat()

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewport.fit(bitmap.width, bitmap.height, it.width, it.height) }
            .pointerInput(bitmap) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val committed = currentPoints
                    val nearest = committed.indices.minByOrNull { (viewport.toScreen(committed[it]) - down.position).getDistance() }
                    val hitIndex = nearest?.takeIf {
                        (viewport.toScreen(committed[it]) - down.position).getDistance() <= HIT_RADIUS.toPx()
                    } ?: -1
                    val mode = when {
                        hitIndex >= 0 -> TouchMode.MOVE
                        committed.size < 4 -> TouchMode.PLACE
                        else -> TouchMode.PAN
                    }
                    val startPoint = if (hitIndex >= 0) committed[hitIndex] else viewport.toBitmap(down.position)
                    if (mode != TouchMode.PAN) active = ActiveCorner(hitIndex, startPoint)
                    down.consume()

                    var multiTouch = false
                    var pastSlop = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break

                        if (pressed.size >= 2) {
                            // A second finger turns any gesture into pinch/pan and
                            // abandons the corner it started placing or moving.
                            multiTouch = true
                            active = null
                            viewport.zoomBy(event.calculateZoom(), event.calculateCentroid(useCurrent = true))
                            viewport.panBy(event.calculatePan())
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        val change = pressed[0]
                        change.consume()
                        if (multiTouch) continue // finishing a pinch with one finger still down

                        val moved = change.position - down.position
                        if (!pastSlop && moved.getDistance() > viewConfiguration.touchSlop) pastSlop = true
                        when (mode) {
                            TouchMode.PLACE -> active = ActiveCorner(-1, viewport.toBitmap(change.position))
                            // Relative, not "jump under the finger": grabbing a corner
                            // slightly off-centre doesn't snap it to the fingertip.
                            TouchMode.MOVE -> if (pastSlop) {
                                active = ActiveCorner(
                                    hitIndex,
                                    PixelPoint(startPoint.x + moved.x / viewport.scale, startPoint.y + moved.y / viewport.scale),
                                )
                            }
                            TouchMode.PAN -> if (pastSlop) viewport.panBy(change.positionChange())
                        }
                    }

                    val finished = active
                    active = null
                    if (multiTouch || finished == null) return@awaitEachGesture
                    val latest = currentPoints
                    when (mode) {
                        TouchMode.PLACE -> if (latest.size < 4) currentOnPointsChange(latest + finished.point)
                        TouchMode.MOVE -> if (finished.index in latest.indices) {
                            currentOnPointsChange(latest.toMutableList().also { it[finished.index] = finished.point })
                        }
                        TouchMode.PAN -> Unit
                    }
                }
            },
    ) {
        if (viewport.scale <= 0f) return@Canvas
        labelPaint.textSize = 13.sp.toPx()

        val activeIndex = active?.let { if (it.index >= 0) it.index else displayed.lastIndex } ?: -1
        drawScene(
            image = imageBitmap,
            scale = viewport.scale,
            origin = viewport.origin,
            clip = Rect(0f, 0f, size.width, size.height),
            points = displayed,
            activeIndex = activeIndex,
            homography = homography,
            courtWidthM = courtWidthM,
            labelPaint = labelPaint,
            showMarkers = true,
        )

        val focus = active?.point ?: return@Canvas
        val radius = LOUPE_RADIUS.toPx()
        val margin = 16.dp.toPx()
        // Opposite side from the corner being worked on, so the loupe never covers it.
        val onRight = viewport.toScreen(focus).x < size.width / 2
        val center = Offset(if (onRight) size.width - radius - margin else radius + margin, size.height / 2)
        val loupeScale = viewport.scale * LOUPE_MAGNIFICATION
        val loupeBounds = Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius)
        val circle = Path().apply { addOval(loupeBounds) }

        clipPath(circle) {
            drawRect(Color.Black, topLeft = loupeBounds.topLeft, size = loupeBounds.size)
            drawScene(
                image = imageBitmap,
                scale = loupeScale,
                origin = Offset(center.x - focus.x * loupeScale, center.y - focus.y * loupeScale),
                clip = loupeBounds,
                points = displayed,
                activeIndex = activeIndex,
                homography = homography,
                courtWidthM = courtWidthM,
                labelPaint = labelPaint,
                showMarkers = false,
            )
        }
        drawCircle(Color.White, radius = radius, center = center, style = Stroke(width = 2.dp.toPx()))
        val arm = 14.dp.toPx()
        val gap = 3.dp.toPx()
        val crosshair = Color(0xFFFF4081)
        val stroke = 1.5.dp.toPx()
        drawLine(crosshair, Offset(center.x - arm, center.y), Offset(center.x - gap, center.y), stroke)
        drawLine(crosshair, Offset(center.x + gap, center.y), Offset(center.x + arm, center.y), stroke)
        drawLine(crosshair, Offset(center.x, center.y - arm), Offset(center.x, center.y - gap), stroke)
        drawLine(crosshair, Offset(center.x, center.y + gap), Offset(center.x, center.y + arm), stroke)
    }
}

private fun DrawScope.drawScene(
    image: ImageBitmap,
    scale: Float,
    origin: Offset,
    clip: Rect,
    points: List<PixelPoint>,
    activeIndex: Int,
    homography: Homography?,
    courtWidthM: Float,
    labelPaint: Paint,
    showMarkers: Boolean,
) {
    drawVisibleRegion(image, scale, origin, clip)

    fun toScreen(p: PixelPoint) = Offset(origin.x + p.x * scale, origin.y + p.y * scale)

    if (homography != null) {
        courtOverlaySegments(courtWidthM).forEach { (a, b) ->
            drawLine(
                color = Color.Cyan.copy(alpha = 0.85f),
                start = toScreen(homography.mapToPixel(a)),
                end = toScreen(homography.mapToPixel(b)),
                strokeWidth = 1.5.dp.toPx(),
            )
        }
    }

    if (!showMarkers) return
    points.forEachIndexed { i, p ->
        val c = toScreen(p)
        val color = if (i == activeIndex) Color(0xFFFF9800) else Color.Yellow
        drawCircle(color, radius = 10.dp.toPx(), center = c, style = Stroke(width = 2.dp.toPx()))
        drawCircle(color, radius = 1.5.dp.toPx(), center = c)
        drawContext.canvas.nativeCanvas.drawText(
            CORNER_LABELS[i],
            c.x + 12.dp.toPx(),
            c.y - 12.dp.toPx(),
            labelPaint,
        )
    }
}

/**
 * Draws only the part of [image] that falls inside [clip]. At 12x zoom the
 * whole bitmap would be tens of thousands of pixels across; asking the
 * canvas to scale all of that just to clip it away is wasted work.
 */
private fun DrawScope.drawVisibleRegion(image: ImageBitmap, scale: Float, origin: Offset, clip: Rect) {
    val left = floor((clip.left - origin.x) / scale).coerceIn(0f, image.width.toFloat())
    val top = floor((clip.top - origin.y) / scale).coerceIn(0f, image.height.toFloat())
    val right = ceil((clip.right - origin.x) / scale).coerceIn(0f, image.width.toFloat())
    val bottom = ceil((clip.bottom - origin.y) / scale).coerceIn(0f, image.height.toFloat())
    if (right <= left || bottom <= top) return

    val srcSize = IntSize((right - left).toInt(), (bottom - top).toInt())
    drawImage(
        image = image,
        srcOffset = IntOffset(left.toInt(), top.toInt()),
        srcSize = srcSize,
        dstOffset = IntOffset((origin.x + left * scale).roundToInt(), (origin.y + top * scale).roundToInt()),
        dstSize = IntSize((srcSize.width * scale).roundToInt(), (srcSize.height * scale).roundToInt()),
        // Hard pixel edges once zoomed in, so the user aims at real pixels rather than blur.
        filterQuality = if (scale >= 3f) FilterQuality.None else FilterQuality.Low,
    )
}
