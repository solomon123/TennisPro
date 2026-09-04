package com.tennispro.phone.ui

import android.graphics.Bitmap
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tennispro.phone.calibration.CalibrationStorage
import com.tennispro.phone.camera.RecordingService
import kotlinx.coroutines.delay

/**
 * Freezes a frame from the live camera preview and hands it to
 * [CalibrationTapFlow]. Reuses [RecordingService]'s existing preview binding
 * via [CameraPreview] — no separate CameraX use case, no change to
 * `RecordingService`'s camera ownership.
 */
@Composable
fun CalibrateScreen(
    service: RecordingService?,
    calibrationStorage: CalibrationStorage,
    onBack: () -> Unit,
) {
    var frozenFrame by remember { mutableStateOf<Bitmap?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var savedNotice by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        val frame = frozenFrame
        if (frame == null) {
            CameraPreview(service, onPreviewViewReady = { previewView = it })

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Mount the phone where it will stay for the match and frame the whole " +
                        "court, then freeze a frame to mark the corners.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { previewView?.bitmap?.let { frozenFrame = it } }) {
                    Text("Freeze frame")
                }
            }
        } else {
            CalibrationTapFlow(
                bitmap = frame,
                calibrationStorage = calibrationStorage,
                onSaved = {
                    frozenFrame = null
                    savedNotice = true
                },
                onCancel = { frozenFrame = null },
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onBack) { Text("< Back") }
        }

        if (savedNotice) {
            LaunchedEffect(savedNotice) {
                delay(1_500)
                savedNotice = false
                onBack()
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .background(Color.Black.copy(alpha = 0.75f))
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                ) {
                    Text("Calibration saved", color = Color.White)
                }
            }
        }
    }
}
