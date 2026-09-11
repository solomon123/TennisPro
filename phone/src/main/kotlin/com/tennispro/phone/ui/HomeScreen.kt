package com.tennispro.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tennispro.core.scoring.ScoreFormat
import com.tennispro.core.scoring.projection
import com.tennispro.phone.calibration.CalibrationStorage
import com.tennispro.phone.camera.CameraFacing
import com.tennispro.phone.camera.CaptureState
import com.tennispro.phone.camera.RecordingService
import com.tennispro.phone.score.MatchController
import com.tennispro.phone.storage.MatchSession
import com.tennispro.phone.storage.MatchStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * Two panes, because the app is landscape-only: controls on the left, the
 * recordings list filling the right. Stacked in one column, the list started
 * below the bottom of a landscape phone screen — per-recording delete existed
 * but was never visible (field-test feedback, 2026-09-10).
 */
@Composable
fun HomeScreen(
    storage: MatchStorage,
    diagnostics: WatchDiagnostics,
    matchController: MatchController,
    calibrationStorage: CalibrationStorage,
    service: RecordingService?,
    onRecord: () -> Unit,
    onWatchCheck: () -> Unit,
    onScore: () -> Unit,
    onCalibrate: () -> Unit,
    onReplay: () -> Unit,
) {
    val activeMatch by matchController.match.collectAsState()
    var sessions by remember { mutableStateOf<List<MatchSession>>(emptyList()) }
    var totalBytes by remember { mutableStateOf(0L) }
    var freeBytes by remember { mutableStateOf(0L) }
    var reloadToken by remember { mutableStateOf(0) }
    var calibrated by remember { mutableStateOf(false) }

    // Same fallback-flow pattern as RecordScreen: a plain val, not `by`, and a
    // remembered flow so collectAsState always has something to observe even
    // before the service is bound.
    val fallbackFacing = remember { MutableStateFlow(CameraFacing.BACK) }
    val fallbackCaptureState = remember { MutableStateFlow<CaptureState>(CaptureState.Initialising) }
    val facing = (service?.facing ?: fallbackFacing).collectAsState().value
    val captureState = (service?.state ?: fallbackCaptureState).collectAsState().value
    val recording = captureState is CaptureState.Recording

    LaunchedEffect(reloadToken) {
        withContext(Dispatchers.IO) {
            sessions = storage.listSessions()
            totalBytes = storage.totalBytes()
            freeBytes = storage.freeBytes()
            // Cheap presence check only — a real drift check needs a live camera
            // frame to compare against, which Home does not bind one for. That
            // check runs on RecordScreen instead, where the preview is already up.
            calibrated = calibrationStorage.load() != null
        }
    }

    LaunchedEffect(Unit) { diagnostics.refresh() }

    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Text("TennisPro", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Recording, scoring, and a watch link",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                val watchConnected = diagnostics.watches.isNotEmpty()
                Chip(
                    text = if (watchConnected) "Watch connected" else "No watch",
                    tint = if (watchConnected) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                Spacer(Modifier.width(8.dp))
                Chip(
                    text = if (calibrated) "Calibrated" else "Not calibrated",
                    tint = if (calibrated) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                )
            }
            activeMatch?.let { match ->
                Spacer(Modifier.height(8.dp))
                Chip(
                    text = "Scoring: ${ScoreFormat.summary(match.projection())}",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Camera facing the court:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                CameraFacingChip("Back", selected = facing == CameraFacing.BACK, enabled = !recording) {
                    service?.switchCamera(CameraFacing.BACK)
                }
                Spacer(Modifier.width(8.dp))
                CameraFacingChip("Front", selected = facing == CameraFacing.FRONT, enabled = !recording) {
                    service?.switchCamera(CameraFacing.FRONT)
                }
            }
            if (facing == CameraFacing.FRONT) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Front camera: verify recorded video isn't mirrored on this device before " +
                        "trusting it — calibrating from Replay's \"Calibrate from this frame\" " +
                        "(the actual recorded file) is the safer choice here over a live freeze.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRecord) { Text("Record a match") }
                OutlinedButton(onClick = onScore) {
                    Text(if (activeMatch != null) "Resume score" else "Score match")
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onCalibrate) {
                    Text(if (calibrated) "Recalibrate court" else "Calibrate court")
                }
                OutlinedButton(onClick = onReplay) { Text("Replay") }
            }

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onWatchCheck) { Text("Watch check") }
                OutlinedButton(onClick = { reloadToken++ }) { Text("Refresh") }
            }
        }

        RecordingList(
            sessions = sessions,
            storage = storage,
            recordingSessionId = (captureState as? CaptureState.Recording)?.session?.meta?.id,
            onChanged = { reloadToken++ },
            subtitle = "${formatBytes(totalBytes)} used · ${formatBytes(freeBytes)} free",
            emptyText = "Nothing recorded yet. Raw footage is kept in full — clear it here " +
                "before a match if space is tight.",
            onOpen = { onReplay() },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Vertical))
                .padding(top = 20.dp, end = 16.dp, start = 4.dp),
        )
    }
}

@Composable
private fun CameraFacingChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, enabled = enabled) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled) { Text(label) }
    }
}
