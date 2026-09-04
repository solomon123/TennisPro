package com.tennispro.phone.ui

import android.graphics.Bitmap
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.tennispro.core.court.CourtDimensions
import com.tennispro.core.court.Homography
import com.tennispro.phone.calibration.CalibrationStorage
import com.tennispro.phone.replay.VideoFrameSource
import com.tennispro.phone.storage.MatchSession
import com.tennispro.phone.storage.MatchStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
    onBack: () -> Unit,
) {
    var sessions by remember { mutableStateOf<List<MatchSession>>(emptyList()) }
    var selected by remember { mutableStateOf<MatchSession?>(null) }

    LaunchedEffect(Unit) { sessions = matchStorage.listSessions() }

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
            SessionPicker(sessions, onSelect = { selected = it })
        } else {
            SessionReplay(
                session = session,
                matchStorage = matchStorage,
                calibrationStorage = calibrationStorage,
            )
        }
    }
}

@Composable
private fun SessionPicker(sessions: List<MatchSession>, onSelect: (MatchSession) -> Unit) {
    if (sessions.isEmpty()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(32.dp),
        ) {
            Text(
                "No recordings yet. Record a match first, then come back here to step " +
                    "through its frames.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(sessions, key = { it.meta.id }) { session ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                onClick = { onSelect(session) },
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(session.meta.id, style = MaterialTheme.typography.titleSmall)
                    session.meta.durationMs?.let {
                        Text(
                            formatElapsed(it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionReplay(
    session: MatchSession,
    matchStorage: MatchStorage,
    calibrationStorage: CalibrationStorage,
) {
    val frameSource = remember(session) { runCatching { VideoFrameSource(matchStorage.videoFileFor(session)) }.getOrNull() }
    DisposableEffect(frameSource) { onDispose { frameSource?.close() } }

    var mode by remember { mutableStateOf(ReplayMode.SCRUB) }
    var positionMs by remember(frameSource) { mutableStateOf(0L) }
    var frame by remember(frameSource) { mutableStateOf<Bitmap?>(null) }
    var calibrating by remember { mutableStateOf(false) }

    // Re-read on every calibrating -> not-calibrating transition, so saving a new
    // calibration from this very screen (via "Calibrate from this frame" below)
    // updates the overlay immediately rather than only after leaving and returning.
    val calibration = remember(calibrating) { calibrationStorage.load() }
    val homography = remember(calibration) { calibration?.let { Homography.fromCalibration(it) } }
    val courtWidthM = remember(calibration) {
        (calibration?.format?.let { CourtDimensions.widthFor(it) } ?: CourtDimensions.SINGLES_WIDTH_M).toFloat()
    }

    LaunchedEffect(frameSource, positionMs) {
        val source = frameSource ?: return@LaunchedEffect
        frame = withContext(Dispatchers.Default) { source.frameAt(positionMs) }
    }

    if (frameSource == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Could not open this recording's video", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    val currentFrame = frame
    if (calibrating && currentFrame != null) {
        CalibrationTapFlow(
            bitmap = currentFrame,
            calibrationStorage = calibrationStorage,
            onSaved = { calibrating = false },
            onCancel = { calibrating = false },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        when (mode) {
            ReplayMode.PLAY -> VideoPlayer(
                videoFile = matchStorage.videoFileFor(session),
                // Bottom padding, not just the mode-toggle row's top padding:
                // without it the video area runs flush to the screen edge, so
                // MediaController's floating play/pause/seek bar has nowhere to
                // sit but on top of the video itself.
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(bottom = 42.dp),
            )

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

                Column(Modifier.padding(16.dp)) {
                    Slider(
                        value = positionMs.toFloat(),
                        onValueChange = { positionMs = it.toLong() },
                        valueRange = 0f..frameSource.durationMs.toFloat().coerceAtLeast(1f),
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(formatElapsed(positionMs), style = MaterialTheme.typography.bodySmall)
                        Text(formatElapsed(frameSource.durationMs), style = MaterialTheme.typography.bodySmall)
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = { calibrating = true },
                        enabled = currentFrame != null,
                    ) { Text("Calibrate from this frame") }
                }
            }
        }

        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ReplayModeChip("Scrub", selected = mode == ReplayMode.SCRUB) { mode = ReplayMode.SCRUB }
            ReplayModeChip("Play", selected = mode == ReplayMode.PLAY) { mode = ReplayMode.PLAY }
        }
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
private fun VideoPlayer(videoFile: File, modifier: Modifier = Modifier) {
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
                // Default — clips a scaled-up child to these bounds, which is
                // exactly the "fill and crop" effect below relies on.
                clipChildren = true
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
            videoView.setVideoURI(Uri.fromFile(videoFile))
            val controller = MediaController(context)
            controller.setAnchorView(videoView)
            videoView.setMediaController(controller)
            videoView.setOnPreparedListener {
                videoView.start()
                // VideoView measures itself to the video's own aspect ratio
                // rather than stretching to fill whatever it's given (no
                // LayoutParams change fixes this — it recomputes its own fit
                // size regardless), so filling the container edge to edge
                // means scaling the rendered content itself, past its own
                // measured bounds, and letting the container clip the excess
                // — the standard technique for a CENTER_CROP-style fill.
                // Deferred to `post` so this runs after the layout pass that
                // gives `container` and `videoView` their real measured sizes.
                videoView.post {
                    val videoWidth = videoView.measuredWidth.toFloat()
                    val videoHeight = videoView.measuredHeight.toFloat()
                    val targetWidth = container.width.toFloat()
                    val targetHeight = container.height.toFloat()
                    if (videoWidth > 0f && videoHeight > 0f && targetWidth > 0f && targetHeight > 0f) {
                        // Exact edge-to-edge fill measured too large on-device —
                        // scaled back down 30%, evenly on every side since the
                        // scale is applied around the view's own center.
                        val fillScale = maxOf(targetWidth / videoWidth, targetHeight / videoHeight)
                        val scale = fillScale * 0.7f
                        videoView.scaleX = scale
                        videoView.scaleY = scale
                    }
                }
            }
            container
        },
    )
}
