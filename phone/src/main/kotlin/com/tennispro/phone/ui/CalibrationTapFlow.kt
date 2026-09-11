package com.tennispro.phone.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tennispro.core.court.CalibrationPoints
import com.tennispro.core.court.CourtFormat
import com.tennispro.core.court.Homography
import com.tennispro.core.court.PixelPoint
import com.tennispro.core.vision.CourtCorners
import com.tennispro.core.vision.CourtDetection
import com.tennispro.core.vision.CourtLineDetector
import com.tennispro.phone.calibration.CalibrationStorage
import com.tennispro.phone.vision.toGrayscaleFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val PANEL_WIDTH = 176.dp
private val CONTROL_HEIGHT = 36.dp
private val COMPACT_PADDING = PaddingValues(horizontal = 10.dp)

private enum class DetectState { RUNNING, FOUND, NOT_FOUND }

/**
 * Given an already-captured frame — a live-preview freeze from
 * [CalibrateScreen], or a scrubbed frame from [ReplayScreen], in both cases
 * already in the recorded video's pixel space — finds or collects the four
 * court corners, overlays the projected court grid for a visual check, and
 * saves. Both entry points feed this one flow so the calibration logic itself
 * is written once.
 *
 * [CourtLineDetector] runs as soon as the frame arrives and fills in the
 * corners if it finds the court; the user checks the grid and fine-tunes by
 * dragging (see [CalibrationCanvas]) or taps them from scratch. The frame
 * gets the whole screen bar a narrow control panel — the first field test's
 * complaint was that the frame was too small to hit corners on.
 */
@Composable
fun CalibrationTapFlow(
    bitmap: Bitmap,
    calibrationStorage: CalibrationStorage,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
) {
    var format by remember { mutableStateOf(CourtFormat.SINGLES) }
    var points by remember(bitmap) { mutableStateOf<List<PixelPoint>>(emptyList()) }
    var detection by remember(bitmap) { mutableStateOf<CourtDetection?>(null) }
    var detectState by remember(bitmap) { mutableStateOf(DetectState.RUNNING) }
    var detectRequest by remember(bitmap) { mutableIntStateOf(0) }
    val viewport = remember(bitmap) { CalibrationViewport() }

    LaunchedEffect(bitmap, detectRequest) {
        detectState = DetectState.RUNNING
        val found = withContext(Dispatchers.Default) {
            runCatching { CourtLineDetector.detect(bitmap.toGrayscaleFrame()) }.getOrNull()
        }
        detection = found
        detectState = if (found != null) DetectState.FOUND else DetectState.NOT_FOUND
        // The automatic first run doesn't overwrite corners tapped while it was
        // working; an explicit "Detect lines" press does.
        if (found != null && (points.isEmpty() || detectRequest > 0)) {
            points = found.cornersFor(format).asList()
        }
    }

    val calibrationPoints = if (points.size == 4) {
        CalibrationPoints(
            format = format,
            frameWidth = bitmap.width,
            frameHeight = bitmap.height,
            nearLeft = points[0],
            nearRight = points[1],
            farLeft = points[2],
            farRight = points[3],
        )
    } else {
        null
    }
    val homography = calibrationPoints?.let { Homography.fromCalibration(it) }
    val degenerate = calibrationPoints != null && homography == null
    val fromDetection = detection?.cornersFor(format)?.asList() == points && points.size == 4
    val outsideFrame = points.any { it.x < 0 || it.y < 0 || it.x > bitmap.width || it.y > bitmap.height }

    fun switchFormat(newFormat: CourtFormat) {
        // Detected corners follow the format (the fit found both sets of
        // sidelines); hand-placed ones are left where the user put them.
        val found = detection
        if (found != null && fromDetection) points = found.cornersFor(newFormat).asList()
        format = newFormat
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val landscape = maxWidth > maxHeight

        val canvas: @Composable (Modifier) -> Unit = { modifier ->
            CalibrationCanvas(
                bitmap = bitmap,
                points = points,
                format = format,
                viewport = viewport,
                onPointsChange = { points = it },
                modifier = modifier,
            )
        }

        val controls: @Composable (Modifier) -> Unit = { modifier ->
            Column(
                modifier
                    .background(MaterialTheme.colorScheme.surface)
                    // Clear of the navigation bar, which sits on the panel's side
                    // in landscape — on-device it covered half of every button.
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            if (landscape) {
                                WindowInsetsSides.End + WindowInsetsSides.Vertical
                            } else {
                                WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal
                            },
                        ),
                    )
                    // Width applied after the inset, so the bar adds to the panel
                    // rather than squeezing its buttons until their labels wrap.
                    .then(if (landscape) Modifier.width(PANEL_WIDTH) else Modifier.fillMaxWidth())
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    guidance(points.size, format, degenerate, detectState, fromDetection),
                    style = MaterialTheme.typography.bodySmall,
                )

                // Save first: the panel can scroll, and the one button every
                // calibration ends with shouldn't be the one below the fold.
                Button(
                    enabled = homography != null,
                    onClick = {
                        calibrationStorage.save(calibrationPoints!!, bitmap)
                        onSaved()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CONTROL_HEIGHT),
                ) { Text("Save") }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { points = emptyList() },
                        enabled = points.isNotEmpty(),
                        contentPadding = COMPACT_PADDING,
                        modifier = Modifier
                            .weight(1f)
                            .height(CONTROL_HEIGHT),
                    ) { Text("Retap") }
                    OutlinedButton(
                        onClick = onCancel,
                        contentPadding = COMPACT_PADDING,
                        modifier = Modifier
                            .weight(1f)
                            .height(CONTROL_HEIGHT),
                    ) { Text("Cancel") }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FormatChip("Singles", format == CourtFormat.SINGLES, Modifier.weight(1f)) {
                        switchFormat(CourtFormat.SINGLES)
                    }
                    FormatChip("Doubles", format == CourtFormat.DOUBLES, Modifier.weight(1f)) {
                        switchFormat(CourtFormat.DOUBLES)
                    }
                }

                if (detectState == DetectState.RUNNING) {
                    Row(
                        Modifier.height(CONTROL_HEIGHT),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Finding lines…", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    OutlinedButton(
                        onClick = { detectRequest++ },
                        contentPadding = COMPACT_PADDING,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(CONTROL_HEIGHT),
                    ) { Text("Detect lines") }
                }

                if (viewport.isZoomed) {
                    OutlinedButton(
                        onClick = { viewport.reset() },
                        contentPadding = COMPACT_PADDING,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(CONTROL_HEIGHT),
                    ) { Text("Fit view") }
                }

                if (outsideFrame) {
                    Text(
                        "A corner is outside the frame — placed from where its lines meet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFB300),
                    )
                }
            }
        }

        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                canvas(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                controls(Modifier.fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                canvas(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
                controls(Modifier.fillMaxWidth())
            }
        }
    }
}

private fun CourtCorners.asList(): List<PixelPoint> = listOf(nearLeft, nearRight, farLeft, farRight)

private fun guidance(
    tapped: Int,
    format: CourtFormat,
    degenerate: Boolean,
    detectState: DetectState,
    fromDetection: Boolean,
): String {
    val court = if (format == CourtFormat.SINGLES) "singles" else "doubles"
    return when {
        degenerate -> "Corners too close together or in a line. Retap."
        fromDetection -> "Court found. Check the blue grid is on the lines; drag a corner to adjust."
        tapped == 0 && detectState == DetectState.RUNNING -> "Looking for the court lines…"
        tapped == 0 && detectState == DetectState.NOT_FOUND ->
            "Lines not found. Tap the near-left $court corner (pinch to zoom)."
        tapped == 0 -> "Tap the near-left $court corner (pinch to zoom)."
        tapped == 1 -> "Now the near-right $court corner."
        tapped == 2 -> "Now the far-left $court corner."
        tapped == 3 -> "Now the far-right $court corner."
        else -> "Check the blue grid is on the lines; drag a corner to adjust."
    }
}

@Composable
private fun FormatChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val sized = modifier.height(CONTROL_HEIGHT)
    if (selected) {
        Button(onClick = onClick, contentPadding = COMPACT_PADDING, modifier = sized) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, contentPadding = COMPACT_PADDING, modifier = sized) { Text(label) }
    }
}
