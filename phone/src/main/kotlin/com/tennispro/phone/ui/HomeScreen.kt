package com.tennispro.phone.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 *
 * The controls are ranked, not laid out as a wall of equal buttons (user
 * feedback, same day): the one thing done every session — Record — is the big
 * button; the other everyday screens are a list, each showing its own state;
 * setup that's touched rarely (watch check, camera facing, refresh) lives in
 * the ⋮ menu.
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
    onAbout: () -> Unit,
) {
    val activeMatch by matchController.match.collectAsState()
    var sessions by remember { mutableStateOf<List<MatchSession>>(emptyList()) }
    var totalBytes by remember { mutableStateOf(0L) }
    var freeBytes by remember { mutableStateOf(0L) }
    var reloadToken by remember { mutableStateOf(0) }
    var calibrated by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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
            // Home lists recordings too, so it squares up with the gallery on the
            // same terms Replay does — otherwise a recording deleted from the
            // gallery would linger here until Replay was opened.
            storage.reconcileWithGallery()
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
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("TennisReplay", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                Box {
                    TextButton(onClick = { menuOpen = true }) { Text("⋮", fontSize = 24.sp) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Watch check") },
                            onClick = {
                                menuOpen = false
                                onWatchCheck()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("About & updates") },
                            onClick = {
                                menuOpen = false
                                onAbout()
                            },
                        )
                        HorizontalDivider()
                        CameraFacingMenuItem("Camera: back", CameraFacing.BACK, facing, enabled = !recording) {
                            menuOpen = false
                            service?.switchCamera(CameraFacing.BACK)
                        }
                        CameraFacingMenuItem("Camera: front", CameraFacing.FRONT, facing, enabled = !recording) {
                            menuOpen = false
                            service?.switchCamera(CameraFacing.FRONT)
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Refresh") },
                            onClick = {
                                menuOpen = false
                                reloadToken++
                                scope.launch { diagnostics.refresh() }
                            },
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                val watchConnected = diagnostics.watches.isNotEmpty()
                Chip(
                    text = if (watchConnected) "Watch connected" else "No watch",
                    tint = if (watchConnected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(8.dp))
                Chip(
                    text = if (calibrated) "Calibrated" else "Not calibrated",
                    tint = if (calibrated) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                )
                if (facing == CameraFacing.FRONT) {
                    Spacer(Modifier.width(8.dp))
                    Chip(text = "Front camera", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (facing == CameraFacing.FRONT) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Front camera: check a recording isn't mirrored before trusting it — " +
                        "calibrating from Replay's \"Calibrate from this frame\" uses the actual recorded file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(14.dp))

            Button(
                onClick = onRecord,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text(if (recording) "●  Recording — open" else "●  Record a match", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(Modifier.height(6.dp))

            HomeRow(
                title = if (activeMatch != null) "Resume score" else "Score match",
                detail = activeMatch?.let { ScoreFormat.summary(it.projection()) } ?: "Score from your watch",
                onClick = onScore,
            )
            HorizontalDivider()
            HomeRow(
                title = "Replay",
                detail = "Watch recordings, serves and line calls",
                onClick = onReplay,
            )
            HorizontalDivider()
            HomeRow(
                title = if (calibrated) "Recalibrate court" else "Calibrate court",
                detail = if (calibrated) "Court saved for this mount" else "Needed for line calls",
                onClick = onCalibrate,
            )
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

/** One everyday destination: its name, a line of its current state, and a chevron. */
@Composable
private fun HomeRow(title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", fontSize = 26.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CameraFacingMenuItem(label: String, value: CameraFacing, current: CameraFacing, enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(if (value == current) "✓  $label" else "     $label") },
        onClick = onClick,
        enabled = enabled && value != current,
    )
}
