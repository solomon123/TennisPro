package com.tennispro.phone.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tennispro.core.court.CalibrationPoints
import com.tennispro.core.court.CourtDimensions
import com.tennispro.core.court.CourtFormat
import com.tennispro.core.court.Homography
import com.tennispro.core.court.PixelPoint
import com.tennispro.phone.calibration.CalibrationStorage

/**
 * Given an already-captured frame — a live-preview freeze from
 * [CalibrateScreen], or a scrubbed frame from [ReplayScreen] — walks the user
 * through tapping the four court corners, overlays the projected court grid
 * for a visual sanity check, and saves. Both entry points feed this one flow
 * so the calibration logic itself is written once.
 */
@Composable
fun CalibrationTapFlow(
    bitmap: Bitmap,
    calibrationStorage: CalibrationStorage,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
) {
    var format by remember { mutableStateOf(CourtFormat.SINGLES) }
    var points by remember { mutableStateOf<List<PixelPoint>>(emptyList()) }

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

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                guidance(points.size, degenerate),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            FormatChip("Singles", format == CourtFormat.SINGLES) { format = CourtFormat.SINGLES }
            Spacer(Modifier.width(8.dp))
            FormatChip("Doubles", format == CourtFormat.DOUBLES) { format = CourtFormat.DOUBLES }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            CourtOverlayCanvas(
                bitmap = bitmap,
                tappedPoints = points,
                homography = homography,
                courtWidthM = CourtDimensions.widthFor(format).toFloat(),
                onTap = { p -> if (points.size < 4) points = points + p },
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { points = emptyList() }, enabled = points.isNotEmpty()) {
                Text("Retap")
            }
            TextButton(onClick = onCancel) { Text("Cancel") }
            Spacer(Modifier.weight(1f))
            Button(
                enabled = homography != null,
                onClick = {
                    calibrationStorage.save(calibrationPoints!!, bitmap)
                    onSaved()
                },
            ) { Text("Save") }
        }
    }
}

private fun guidance(tapped: Int, degenerate: Boolean): String = when {
    degenerate -> "Points too close together or in a line — Retap and try again"
    tapped == 0 -> "Tap the near-left baseline corner"
    tapped == 1 -> "Tap the near-right baseline corner"
    tapped == 2 -> "Tap the far-left baseline corner"
    tapped == 3 -> "Tap the far-right baseline corner"
    else -> "Check the grid lines up with the court, then Save"
}

@Composable
private fun FormatChip(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}
