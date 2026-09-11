package com.tennispro.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.tennispro.core.protocol.AlertKind
import com.tennispro.core.protocol.Gesture
import com.tennispro.core.protocol.PhoneToWatch
import com.tennispro.core.scoring.MatchProjection
import com.tennispro.core.scoring.ScoreFormat
import com.tennispro.core.scoring.Side
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The whole watch UI: one full-screen tap target, plus a Record button.
 *
 * No list, no scrolling. On court the user is holding a racket and looking at a
 * ball, so the entire face is the control surface and the gesture — not the
 * position of a finger — carries the meaning. Phase 1 hangs the real scoring
 * semantics off these same three gestures.
 *
 * The Record button along the bottom edge is for a phone hung out of reach. It
 * is a sibling drawn over the tap surface, not a child of it, so a press on the
 * button never also reaches the surface and scores a point.
 */
@Composable
fun WatchApp(link: WatchLink, haptics: Haptics) {
    val scope = rememberCoroutineScope()
    val status by WatchEventBus.status.collectAsState()
    val lastAlert by WatchEventBus.lastAlert.collectAsState()
    val matchState by WatchEventBus.matchState.collectAsState()

    var flash by remember { mutableStateOf<AlertKind?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }

    // Which Record command awaits the phone's answer (true = start), if any.
    var pendingStart by remember { mutableStateOf<Boolean?>(null) }
    var confirmStop by remember { mutableStateOf(false) }
    var recordHint by remember { mutableStateOf<String?>(null) }

    // An OUT call takes over the screen briefly, so a glance immediately after the
    // buzz shows the call rather than the idle face.
    LaunchedEffect(lastAlert) {
        val alert = lastAlert ?: return@LaunchedEffect
        flash = alert.kind
        delay(
            when (alert.kind) {
                AlertKind.OUT_CALL -> 4_000L
                // Says what to do on the phone, so it needs reading time.
                AlertKind.RECORDING_REFUSED -> 3_000L
                else -> 1_200L
            },
        )
        flash = null
    }

    LaunchedEffect(feedback) {
        if (feedback != null) {
            delay(1_200)
            feedback = null
        }
    }

    // The phone answers every Record/Stop tap with a recording alert.
    LaunchedEffect(Unit) {
        WatchEventBus.alerts.collect { if (it.kind in RecordingAlertKinds) pendingStart = null }
    }

    // No answer means the phone app is not on its Record screen, or not running.
    LaunchedEffect(pendingStart) {
        val start = pendingStart ?: return@LaunchedEffect
        delay(RECORD_REPLY_TIMEOUT_MS)
        pendingStart = null
        if (start) {
            recordHint = "Open Record on the phone"
        } else {
            // Nothing is alive on the phone to be recording, or it's out of
            // range. Show REC again: if it is recording, REC answers "Already recording".
            WatchEventBus.publishStatus(PhoneToWatch.Status(recording = false))
            recordHint = "No reply from phone"
        }
    }

    LaunchedEffect(confirmStop) {
        if (confirmStop) {
            delay(3_000)
            confirmStop = false
        }
    }

    LaunchedEffect(recordHint) {
        if (recordHint != null) {
            delay(3_000)
            recordHint = null
        }
    }

    fun report(gesture: Gesture, label: String) {
        haptics.tick()
        feedback = label
        scope.launch {
            if (!link.sendGesture(gesture)) feedback = "No phone"
        }
    }

    fun sendRecordControl(start: Boolean) {
        haptics.tick()
        pendingStart = start
        scope.launch {
            if (!link.sendRecordControl(start)) {
                pendingStart = null
                recordHint = "No phone"
            }
        }
    }

    fun onRecordButton() {
        when {
            pendingStart != null -> Unit
            !status.recording -> sendRecordControl(start = true)
            // Stopping by accident mid-match would lose the rest of it, so Stop
            // takes a second tap. Starting by accident costs nothing.
            !confirmStop -> {
                haptics.tick()
                confirmStop = true
            }
            else -> {
                confirmStop = false
                sendRecordControl(start = false)
            }
        }
    }

    val outFlash = flash == AlertKind.OUT_CALL

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (outFlash) OutRed else MaterialTheme.colors.background),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // Note: registering onDoubleTap delays onTap by the platform's
                    // double-tap timeout (~300 ms). That is acceptable for scoring —
                    // the alternative, an immediate single tap, would make a double tap
                    // impossible to express.
                    detectTapGestures(
                        onTap = { report(Gesture.SINGLE_TAP, "Tap") },
                        onDoubleTap = { report(Gesture.DOUBLE_TAP, "Double tap") },
                        // The phone treats a long press as undo while a match is scored, a mark otherwise.
                        onLongPress = { report(Gesture.LONG_PRESS, if (matchState != null) "Undo" else "Marked") },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                // Lifted clear of the Record button along the bottom edge.
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = if (outFlash) 0.dp else 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                when {
                    outFlash -> {
                        Text(
                            text = lastAlert?.headline ?: "OUT",
                            fontSize = 44.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.SansSerif,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                        )
                        lastAlert?.detail?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = it,
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.85f),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    flash != null -> Text(
                        text = lastAlert?.headline.orEmpty(),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )

                    recordHint != null -> Text(
                        text = recordHint.orEmpty(),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )

                    matchState != null -> ScoreFace(matchState!!)

                    else -> {
                        Text(
                            text = "TennisReplay",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = feedback
                                ?: if (status.recording) "Recording" else statusIdleText(status.text),
                            fontSize = 14.sp,
                            color = MaterialTheme.colors.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "tap · double tap · hold",
                            fontSize = 11.sp,
                            color = MaterialTheme.colors.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        if (!outFlash) {
            RecordButton(
                label = when {
                    pendingStart != null -> "…"
                    confirmStop -> "Tap to stop"
                    status.recording -> "■  STOP"
                    else -> "●  REC"
                },
                live = status.recording,
                onClick = { onRecordButton() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 14.dp),
            )
        }
    }
}

// Tells the player what to do next rather than what the watch is doing: nothing
// shows here until a match (or recording) is started from the phone.
private fun statusIdleText(text: String) = text.ifBlank { "Start the match on the phone" }

/** A pill along the bottom edge: grey REC while idle, red STOP while the phone records. */
@Composable
private fun RecordButton(label: String, live: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (live) OutRed else RecordIdleGrey)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

/**
 * The face while a match is in progress: current game score big, games and
 * sets small below it. Gesture handling does not change — the same tap /
 * double-tap / long-press pointer input above keeps sending [Gesture]s; the
 * phone is what decides they mean "point" and "undo" while a match is active.
 */
@Composable
private fun ScoreFace(projection: MatchProjection) {
    val (pointsA, pointsB) = ScoreFormat.pointLabels(projection)

    Text(
        text = "$pointsA – $pointsB",
        fontSize = 40.sp,
        fontWeight = FontWeight.Black,
        textAlign = TextAlign.Center,
    )
    if (projection.inTiebreak) {
        Text(
            text = "tiebreak",
            fontSize = 11.sp,
            color = MaterialTheme.colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }

    // A tiebreak-only match has no games or sets — the point score above is
    // the whole match, so there is nothing to add here.
    if (!projection.config.tiebreakOnlyMatch) {
        Spacer(Modifier.height(6.dp))

        Text(
            text = "Games ${projection.currentSetGamesA}-${projection.currentSetGamesB}",
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )

        if (projection.completedSets.isNotEmpty()) {
            Text(
                text = projection.completedSets.joinToString(" ") { "${it.gamesA}-${it.gamesB}" },
                fontSize = 11.sp,
                color = MaterialTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }

    Spacer(Modifier.height(8.dp))

    val winner = projection.winner
    when {
        winner == Side.A -> Text("You win!", fontSize = 14.sp, color = Color(0xFF4CAF50), textAlign = TextAlign.Center)
        winner == Side.B -> Text("Opponent wins", fontSize = 14.sp, textAlign = TextAlign.Center)
        else -> Text(
            text = if (projection.server == Side.A) "You serve" else "Opponent serves",
            fontSize = 11.sp,
            color = MaterialTheme.colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private val OutRed = Color(0xFFC62828)
private val RecordIdleGrey = Color(0xFF3A3A3A)

private val RecordingAlertKinds = setOf(
    AlertKind.RECORDING_STARTED,
    AlertKind.RECORDING_STOPPED,
    AlertKind.RECORDING_REFUSED,
)

// Recording starts within about a second; Data Layer delivery adds a few hundred ms.
private const val RECORD_REPLY_TIMEOUT_MS = 5_000L
