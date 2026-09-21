package com.tennispro.phone.ui

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.tennispro.core.court.CalibrationPoints
import com.tennispro.core.court.CourtDimensions
import com.tennispro.core.court.Homography
import com.tennispro.phone.calibration.CalibrationStorage
import com.tennispro.phone.replay.VideoFrameSource
import com.tennispro.phone.storage.DetectedServe
import com.tennispro.phone.storage.MatchSession
import com.tennispro.phone.storage.MatchStorage
import com.tennispro.phone.storage.SessionServes
import com.tennispro.phone.vision.ServeScanService
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** Scrubbing (frame-accurate, for calibration QA) vs. actually watching the match back. */
private enum class ReplayMode { SCRUB, PLAY }

/**
 * The video replay harness: pick a recorded session and scrub through its
 * frames. Shows the current calibration's grid overlay (if any) so the user
 * can eyeball accuracy against real match footage at multiple points, not
 * just the moment they calibrated — the manual side of "drift detection"
 * until real re-detection exists (see [com.tennispro.phone.calibration.DriftDetector]).
 */
@Composable
fun ReplayScreen(
    matchStorage: MatchStorage,
    calibrationStorage: CalibrationStorage,
    recordingSessionId: String?,
    onBack: () -> Unit,
) {
    var sessions by remember { mutableStateOf<List<MatchSession>>(emptyList()) }
    var selected by remember { mutableStateOf<MatchSession?>(null) }
    // Hoisted out of SessionReplay so it survives SessionReplay leaving
    // composition while the full-screen calibration flow is up.
    var positionMs by remember(selected) { mutableLongStateOf(0L) }
    var calibrationFrame by remember { mutableStateOf<Bitmap?>(null) }
    var calibrationVersion by remember { mutableIntStateOf(0) }
    var reloadToken by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadToken) { sessions = withContext(Dispatchers.IO) { matchStorage.listSessions() } }

    val frameToCalibrate = calibrationFrame
    if (frameToCalibrate != null) {
        // Full screen, not under the Replay header: the first field test's
        // complaint was a frame too small to place corners on.
        CalibrationTapFlow(
            bitmap = frameToCalibrate,
            calibrationStorage = calibrationStorage,
            onSaved = {
                calibrationFrame = null
                calibrationVersion++
            },
            onCancel = { calibrationFrame = null },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = if (selected != null) { { selected = null } } else onBack) {
                Text(if (selected != null) "< Sessions" else "< Back")
            }
            Text("Replay", style = MaterialTheme.typography.headlineSmall)
        }

        val session = selected
        if (session == null) {
            RecordingList(
                sessions = sessions,
                storage = matchStorage,
                recordingSessionId = recordingSessionId,
                onChanged = { reloadToken++ },
                emptyText = "No recordings yet. Record a match first, then come back here to step " +
                    "through its frames.",
                onOpen = { selected = it },
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Bottom))
                    .padding(horizontal = 16.dp),
            )
        } else {
            SessionReplay(
                session = session,
                matchStorage = matchStorage,
                calibrationStorage = calibrationStorage,
                positionMs = positionMs,
                onPositionChange = { positionMs = it },
                calibrationVersion = calibrationVersion,
                onCalibrate = { calibrationFrame = it },
                recordingSessionId = recordingSessionId,
                onDeleted = {
                    selected = null
                    reloadToken++
                },
            )
        }
    }
}

@Composable
private fun SessionReplay(
    session: MatchSession,
    matchStorage: MatchStorage,
    calibrationStorage: CalibrationStorage,
    positionMs: Long,
    onPositionChange: (Long) -> Unit,
    calibrationVersion: Int,
    onCalibrate: (Bitmap) -> Unit,
    recordingSessionId: String?,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val videoUri = remember(session) { matchStorage.videoUriFor(session) }
    val frameSource = remember(session) {
        runCatching { VideoFrameSource(context, videoUri) }.getOrNull()
    }
    DisposableEffect(frameSource) { onDispose { frameSource?.close() } }

    var mode by remember { mutableStateOf(ReplayMode.SCRUB) }
    var frame by remember(frameSource) { mutableStateOf<Bitmap?>(null) }
    // Where Play starts: a moment before the last serve tapped, or the beginning.
    var playFromMs by remember(session) { mutableLongStateOf(0L) }
    // Bumped by every serve tap, so tapping another serve while playing restarts the player there.
    var playRequest by remember(session) { mutableIntStateOf(0) }

    val scanState by ServeScanService.state.collectAsState()
    val scanning = scanState.isScanning(session.meta.id)
    var serves by remember(session) { mutableStateOf(session.serves) }

    // Pick up the result file once this session's scan finishes.
    LaunchedEffect(scanning) {
        if (!scanning) {
            serves = withContext(Dispatchers.IO) { matchStorage.findSession(session.meta.id)?.serves }
        }
    }

    // Re-read whenever a calibration is saved, so saving one from this very
    // screen (via "Calibrate from this frame" below) updates the overlay
    // immediately rather than only after leaving and returning.
    val calibration = remember(calibrationVersion) { calibrationStorage.load() }
    val overlay = remember(serves, positionMs, calibration) { overlayCourt(serves, positionMs, calibration) }
    val homography = remember(overlay) { overlay?.points?.let { Homography.fromCalibration(it) } }
    val courtWidthM = remember(overlay) {
        (overlay?.points?.format?.let { CourtDimensions.widthFor(it) } ?: CourtDimensions.SINGLES_WIDTH_M).toFloat()
    }

    LaunchedEffect(frameSource, positionMs) {
        val source = frameSource ?: return@LaunchedEffect
        frame = withContext(Dispatchers.Default) { source.frameAt(positionMs) }
    }

    if (frameSource == null) {
        // Still deletable: an unreadable file is exactly the recording someone wants gone.
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Could not open this recording's video", style = MaterialTheme.typography.bodyMedium)
            if (session.meta.id != recordingSessionId) DeleteRecordingButton(session, matchStorage, onDeleted)
        }
        return
    }

    val currentFrame = frame

    // Video on the left at full height, everything else in a side panel: stacked
    // vertically in landscape, the serve list squeezed the frame to nothing.
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            when (mode) {
                ReplayMode.PLAY -> key(playRequest) {
                    VideoPlayer(
                        videoUri = videoUri,
                        startAtMs = playFromMs,
                        // Bottom padding: without it the video area runs flush to the
                        // screen edge, so MediaController's floating play/pause/seek bar
                        // has nowhere to sit but on top of the video itself.
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(bottom = 42.dp),
                    )
                }

                ReplayMode.SCRUB -> {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (currentFrame != null) {
                            CourtOverlayCanvas(
                                bitmap = currentFrame,
                                tappedPoints = emptyList(),
                                homography = homography,
                                courtWidthM = courtWidthM,
                                onTap = {},
                            )
                        } else {
                            CircularProgressIndicator()
                        }
                    }

                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Slider(
                            value = positionMs.toFloat(),
                            onValueChange = { onPositionChange(it.toLong()) },
                            valueRange = 0f..frameSource.durationMs.toFloat().coerceAtLeast(1f),
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(formatElapsed(positionMs), style = MaterialTheme.typography.bodySmall)
                            Text(formatElapsed(frameSource.durationMs), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        Column(
            Modifier
                .width(REPLAY_PANEL_WIDTH)
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Bottom))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReplayModeChip("Scrub", selected = mode == ReplayMode.SCRUB) { mode = ReplayMode.SCRUB }
                ReplayModeChip("Play", selected = mode == ReplayMode.PLAY) { mode = ReplayMode.PLAY }
            }

            ServesSection(
                session = session,
                serves = serves,
                matchStorage = matchStorage,
                recordingSessionId = recordingSessionId,
                onPlay = { serve ->
                    playFromMs = (serve.contactMs - PLAY_LEAD_MS).coerceAtLeast(0)
                    playRequest++
                    mode = ReplayMode.PLAY
                    // So Scrub afterwards shows this serve, with the court it was measured against.
                    onPositionChange((serve.contactMs - SEEK_LEAD_MS).coerceAtLeast(0))
                },
                onDeleted = onDeleted,
            )

            if (mode == ReplayMode.SCRUB) {
                OutlinedButton(
                    onClick = { currentFrame?.let(onCalibrate) },
                    enabled = currentFrame != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Calibrate from this frame") }
                overlay?.let {
                    Text(it.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private val REPLAY_PANEL_WIDTH = 280.dp

/**
 * The serves [com.tennispro.phone.vision.ServeScanner] found in this
 * recording, with speeds; tapping one plays that serve. Scanning starts on its
 * own when a recording finishes; "Find serves" is for recordings made before
 * that, or a scan that didn't get to run.
 */
@Composable
private fun ServesSection(
    session: MatchSession,
    serves: SessionServes?,
    matchStorage: MatchStorage,
    recordingSessionId: String?,
    onPlay: (DetectedServe) -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val scanState by ServeScanService.state.collectAsState()
    val scanning = scanState.isScanning(session.meta.id)

    Column {
        val result = serves
        when {
            scanning -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                val percent = if (scanState.activeSessionId == session.meta.id) " ${(scanState.progress * 100).roundToInt()}%" else " (queued)"
                Text("Finding serves…$percent", style = MaterialTheme.typography.bodySmall)
            }

            result == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { ServeScanService.enqueue(context, session.meta.id) }) {
                    Text("Find serves")
                }
                Spacer(Modifier.width(8.dp))
                ShareRecordingButton(session, matchStorage)
                if (session.meta.id != recordingSessionId) DeleteRecordingButton(session, matchStorage, onDeleted)
            }

            else -> {
                Text(servesSummary(result), style = MaterialTheme.typography.labelLarge)
                result.error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (result.serves.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        result.serves.forEach { serve ->
                            val label = when {
                                serve.netFault -> "net"
                                serve.speedKmh != null -> "${serve.speedKmh.roundToInt()} km/h" + (callLabel(serve)?.let { " · $it" } ?: "")
                                else -> "serve"
                            }
                            OutlinedButton(
                                onClick = { onPlay(serve) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("▶ ${formatElapsed(serve.contactMs)} · $label")
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { ServeScanService.enqueue(context, session.meta.id) }) { Text("Scan again") }
                    ShareRecordingButton(session, matchStorage)
                    if (session.meta.id != recordingSessionId) DeleteRecordingButton(session, matchStorage, onDeleted)
                }
            }
        }
    }
}

/** Where Scrub lands after a serve is tapped: a moment before contact, so the frame shows the swing. */
private const val SEEK_LEAD_MS = 300L

/** Where playback of a tapped serve starts: before the toss, so the whole serve plays. */
private const val PLAY_LEAD_MS = 2_000L

/** The court lines Replay draws, and where they came from. */
private class OverlayCourt(val points: CalibrationPoints, val label: String)

/**
 * The court the scan measured the nearest serve against, found in the video
 * itself; failing that, the one found near the recording's start; failing that
 * (a recording never scanned), the saved calibration. The saved one belongs to
 * wherever the phone was when it was made: on 2026-09-11 that was before the
 * phone was hung on the fence, and its grid landed far off the court.
 */
private fun overlayCourt(serves: SessionServes?, positionMs: Long, saved: CalibrationPoints?): OverlayCourt? {
    val nearest = serves?.serves
        ?.filter { it.court != null }
        ?.minByOrNull { abs(it.contactMs - positionMs) }
    val sessionCourt = serves?.court
    return when {
        nearest?.court != null -> OverlayCourt(nearest.court, "Court lines as found at the ${formatElapsed(nearest.contactMs)} serve")
        sessionCourt != null -> OverlayCourt(sessionCourt, "Court lines as found in this recording")
        saved != null -> OverlayCourt(saved, "Court lines from the saved calibration")
        else -> null
    }
}

/**
 * Hands the video to WhatsApp, Telegram or anything else that takes a video,
 * through the system share sheet.
 *
 * Shares the gallery URI rather than a file: a `file://` URI thrown at another
 * app is a `FileUriExposedException`, and the receiving app has no rights to the
 * app's own storage anyway. A recording made before gallery export existed is
 * published first, which is also what gives the other app something it can read.
 *
 * These files are large — hundreds of megabytes for a few minutes — and most
 * messaging apps will re-compress or refuse them. Nothing here can change that;
 * the share sheet reports it in its own words.
 */
@Composable
private fun ShareRecordingButton(session: MatchSession, matchStorage: MatchStorage) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preparing by remember { mutableStateOf(false) }

    TextButton(
        enabled = !preparing,
        onClick = {
            preparing = true
            scope.launch {
                val uri = withContext(Dispatchers.IO) {
                    matchStorage.exportToGallery(session).galleryUri
                }
                preparing = false
                if (uri == null) {
                    Toast.makeText(context, "Could not prepare the video to share", Toast.LENGTH_LONG).show()
                    return@launch
                }
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "TennisReplay — " + session.meta.id)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { context.startActivity(Intent.createChooser(send, "Share recording")) }
                    .onFailure { Toast.makeText(context, "No app to share a video with", Toast.LENGTH_LONG).show() }
            }
        },
    ) { Text(if (preparing) "Preparing…" else "Share") }
}

/**
 * Deletes the recording that's open, after confirming. Not offered while a
 * scan is reading the file — [ServesSection] shows scan progress instead of
 * any buttons then — or while it's still being recorded.
 */
@Composable
private fun DeleteRecordingButton(session: MatchSession, matchStorage: MatchStorage, onDeleted: () -> Unit) {
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var sizeBytes by remember { mutableLongStateOf(0L) }

    TextButton(onClick = {
        confirming = true
        scope.launch { sizeBytes = withContext(Dispatchers.IO) { session.sizeBytes } }
    }) { Text("Delete", color = MaterialTheme.colorScheme.error) }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { if (!deleting) confirming = false },
            title = { Text("Delete this recording?") },
            text = {
                Text("${startedLabel(session)} — frees ${formatBytes(sizeBytes)}, with any serves and marked moments found in it. This cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        deleting = true
                        scope.launch {
                            withContext(Dispatchers.IO) { matchStorage.deleteSession(session) }
                            deleting = false
                            confirming = false
                            onDeleted()
                        }
                    },
                ) { Text(if (deleting) "Deleting…" else "Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(enabled = !deleting, onClick = { confirming = false }) { Text("Keep") }
            },
        )
    }
}

@Composable
private fun ReplayModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

/**
 * Actual playback — play/pause, seek, normal speed — as opposed to [ReplayMode.SCRUB]'s
 * frame-accurate stepping. `VideoView` + `MediaController` rather than a new
 * dependency: this project has no media-player library, and the built-in
 * widgets are enough for "watch the match back."
 */
@Composable
private fun VideoPlayer(videoUri: Uri, startAtMs: Long, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            // VideoView measures itself to the video's own aspect ratio rather
            // than stretching to fill whatever it's given, so when that aspect
            // ratio doesn't match the available space, *something* has to
            // decide where the leftover space goes. Wrapping it in our own
            // FrameLayout with an explicit Gravity.CENTER child is the
            // standard, reliable way to make that "leftover space" split
            // evenly instead of collecting on one edge — a plain AndroidView
            // hosting VideoView directly does not do this on its own.
            val container = FrameLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
            val videoView = VideoView(context)
            container.addView(
                videoView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
            videoView.setVideoURI(videoUri)
            // VideoView anchors its MediaController to its parent, this container,
            // so the bar spans the container. The picture used to be drawn at 70%
            // of its fitted size, leaving the bar sticking out past both edges and
            // over half of it (2026-09-12); at its fitted size it fills that width.
            //
            // No controller until the video is touched: VideoView shows one for
            // three seconds every time playback starts, right over the serve that
            // was just tapped to watch. Once attached, VideoView's own touch
            // handling shows and hides it on each tap.
            var controllerAttached = false
            videoView.setOnTouchListener { _, event ->
                if (!controllerAttached && event.action == MotionEvent.ACTION_DOWN) {
                    videoView.setMediaController(MediaController(context))
                    controllerAttached = true
                }
                false
            }
            videoView.setOnPreparedListener { player ->
                // The closest frame, not the keyframe before it: a phone
                // recording's keyframes can be a second or more apart.
                if (startAtMs > 0) player.seekTo(startAtMs, MediaPlayer.SEEK_CLOSEST)
                videoView.start()
            }
            container
        },
    )
}
