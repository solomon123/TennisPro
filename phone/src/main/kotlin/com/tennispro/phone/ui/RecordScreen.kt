package com.tennispro.phone.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tennispro.core.protocol.Gesture
import com.tennispro.core.protocol.WatchToPhone
import com.tennispro.phone.calibration.CalibrationStorage
import com.tennispro.phone.calibration.DriftDetector
import com.tennispro.phone.calibration.DriftStatus
import com.tennispro.phone.camera.CaptureState
import com.tennispro.phone.camera.RecordingService
import com.tennispro.phone.wear.WearEventBus
import com.tennispro.phone.wear.WearLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import android.os.SystemClock

@Composable
fun RecordScreen(
    service: RecordingService?,
    wearLink: WearLink,
    calibrationStorage: CalibrationStorage,
    cameraGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onStartRecording: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val driftDetector = remember { DriftDetector(calibrationStorage) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var driftStatus by remember { mutableStateOf<DriftStatus?>(null) }
    // Compose requires composable calls to be unconditional, so the fallback flow is
    // remembered up front rather than reached for behind an elvis on collectAsState.
    val fallbackState = remember { MutableStateFlow<CaptureState>(CaptureState.Initialising) }
    // A plain val, not `by`: a delegated property re-invokes its getter on every
    // read, so the compiler cannot smart-cast it inside the `when` branches below.
    val state = (service?.state ?: fallbackState).collectAsState().value
    var toast by remember { mutableStateOf<String?>(null) }

    // Ticks the elapsed clock without recomposing anything else.
    var nowElapsed by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state is CaptureState.Recording) {
        while (state is CaptureState.Recording) {
            nowElapsed = SystemClock.elapsedRealtime()
            delay(250)
        }
    }

    // A long press on the watch marks the moment. Wired here rather than in a
    // service so it only applies while a recording screen is actually up; Phase 1
    // moves gesture routing into the match session itself.
    LaunchedEffect(service) {
        WearEventBus.events.collect { received ->
            val input = received.message as? WatchToPhone.Input ?: return@collect
            if (input.gesture == Gesture.LONG_PRESS) {
                val mark = service?.bookmark("watch long press")
                toast = if (mark != null) {
                    scope.launch { wearLink.send(bookmarkAck()) }
                    "Marked at ${formatElapsed(mark.offsetMs)}"
                } else {
                    "Not recording — nothing to mark"
                }
            }
        }
    }

    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2_500)
            toast = null
        }
    }

    // Checked once the preview is actually up, not on Home: a real drift check
    // needs a live frame to compare against the calibration reference, and this
    // is the first screen with a camera bound. See docs/ARCHITECTURE.md's
    // Calibration section for why this is a rough approximation, not real
    // line re-detection.
    LaunchedEffect(state is CaptureState.Ready, previewView) {
        if (state is CaptureState.Ready) {
            previewView?.bitmap?.let { frame -> driftStatus = driftDetector.check(frame) }
        }
    }

    Box(Modifier.fillMaxSize()) {

        if (cameraGranted) {
            CameraPreview(service, onPreviewViewReady = { previewView = it })
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Camera permission is required to record.")
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRequestPermissions) { Text("Grant permission") }
            }
        }

        // ---- top bar: framing guidance and status -----------------------------
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onBack) { Text("< Back") }

            if (driftStatus is DriftStatus.PossibleDrift) {
                Chip(
                    text = "Camera may have moved",
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(8.dp))
            }

            when (state) {
                is CaptureState.Recording -> Chip(
                    text = "REC  ${formatElapsed(nowElapsed - state.startedAtElapsedMs)}",
                    tint = MaterialTheme.colorScheme.error,
                )

                is CaptureState.Ready -> Chip(
                    text = buildString {
                        append(state.resolution ?: "camera ready")
                        state.frameRate?.let { append(" @ ${it}fps") }
                    },
                    tint = MaterialTheme.colorScheme.secondary,
                )

                is CaptureState.Error -> Chip(
                    text = state.message,
                    tint = MaterialTheme.colorScheme.error,
                )

                CaptureState.Initialising -> Chip(
                    text = "Starting camera…",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- bottom bar: controls --------------------------------------------
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            toast?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
            }

            Text(
                "Mount the phone behind the baseline, as high as you can get it, " +
                    "centred on the centre mark. Do not move it once recording starts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state) {
                    is CaptureState.Recording -> {
                        Button(
                            onClick = {
                                service?.stopRecording()
                                scope.launch { wearLink.sendStatus(false, "Stopped") }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                            ),
                        ) { Text("Stop") }

                        OutlinedButton(onClick = {
                            val mark = service?.bookmark("phone button")
                            toast = mark?.let { "Marked at ${formatElapsed(it.offsetMs)}" }
                        }) { Text("Mark moment (${state.bookmarkCount})") }
                    }

                    else -> {
                        Button(
                            enabled = cameraGranted && state is CaptureState.Ready,
                            onClick = {
                                onStartRecording()
                                scope.launch { wearLink.sendStatus(true, "Recording") }
                            },
                        ) { Text("Start recording") }
                    }
                }

                Spacer(Modifier.width(4.dp))
            }

            if (state is CaptureState.Recording) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Long-press the watch to mark a moment · saved to ${state.session.meta.id}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun bookmarkAck() = com.tennispro.core.protocol.PhoneToWatch.Alert(
    kind = com.tennispro.core.protocol.AlertKind.BOOKMARK_SAVED,
    headline = "Marked",
)
