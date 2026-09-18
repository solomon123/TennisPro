package com.tennispro.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
 * The whole watch UI: one full-screen tap target, plus the Match and Record pills.
 *
 * No list, no scrolling. On court the user is holding a racket and looking at a
 * ball, so the entire face is the control surface and the gesture — not the
 * position of a finger — carries the meaning. Phase 1 hangs the real scoring
 * semantics off these same three gestures.
 *
 * The two pills along the bottom edge are for a phone hung out of reach: Match
 * starts and ends the score, Record starts and stops the video. They are
 * siblings drawn over the tap surface, not children of it, so a press on a
 * button never also reaches the surface and scores a point.
 *
 * The two are independent on purpose — a practice session is filmed without a
 * score, and a match can be scored with nothing filming.
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

    // The same pair for the Match button. Kept separate from the Record ones so a
    // pending Record never greys out Match, and a Stop confirm never arms End.
    var pendingMatch by remember { mutableStateOf<Boolean?>(null) }
    var confirmEnd by remember { mutableStateOf(false) }

    // One line of explanation for whichever button could not be acted on.
    var hint by remember { mutableStateOf<String?>(null) }

    // An OUT call takes over the screen briefly, so a glance immediately after the
    // buzz shows the call rather than the idle face.
    LaunchedEffect(lastAlert) {
        val alert = lastAlert ?: return@LaunchedEffect
        flash = alert.kind
        delay(
            when (alert.kind) {
                AlertKind.OUT_CALL -> 4_000L
                // Says what to do on the phone, so it needs reading time.
                AlertKind.RECORDING_REFUSED, AlertKind.MATCH_REFUSED -> 3_000L
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

    // The phone answers every Record/Stop and Match/End tap with an alert.
    LaunchedEffect(Unit) {
        WatchEventBus.alerts.collect {
            if (it.kind in RecordingAlertKinds) pendingStart = null
            if (it.kind in MatchAlertKinds) pendingMatch = null
        }
    }

    // No answer means the phone app is not on its Record screen, or not running.
    LaunchedEffect(pendingStart) {
        val start = pendingStart ?: return@LaunchedEffect
        delay(RECORD_REPLY_TIMEOUT_MS)
        pendingStart = null
        if (start) {
            hint = "Open Record on the phone"
        } else {
            // Nothing is alive on the phone to be recording, or it's out of
            // range. Show REC again: if it is recording, REC answers "Already recording".
            WatchEventBus.publishStatus(PhoneToWatch.Status(recording = false))
            hint = "No reply from phone"
        }
    }

    // Match control needs no screen open on the phone — the listener service wakes
    // the app — so silence here means out of range or the app uninstalled, never
    // "wrong screen". Hence a different hint from the Record one above.
    LaunchedEffect(pendingMatch) {
        if (pendingMatch == null) return@LaunchedEffect
        delay(MATCH_REPLY_TIMEOUT_MS)
        pendingMatch = null
        hint = "No reply from phone"
    }

    LaunchedEffect(confirmStop) {
        if (confirmStop) {
            delay(3_000)
            confirmStop = false
        }
    }

    LaunchedEffect(confirmEnd) {
        if (confirmEnd) {
            delay(3_000)
            confirmEnd = false
        }
    }

    LaunchedEffect(hint) {
        if (hint != null) {
            delay(3_000)
            hint = null
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
                hint = "No phone"
            }
        }
    }

    fun sendMatchControl(start: Boolean) {
        haptics.tick()
        pendingMatch = start
        scope.launch {
            if (!link.sendMatchControl(start)) {
                pendingMatch = null
                hint = "No phone"
            }
        }
    }

    fun onMatchButton() {
        when {
            pendingMatch != null -> Unit
            // A decided match is finished business; End on it starts nothing and
            // needs no guarding, but the score is still worth one deliberate tap.
            matchState == null -> sendMatchControl(start = true)
            // Ending by accident loses the score with no undo, so End takes a
            // second tap, exactly as Stop does.
            !confirmEnd -> {
                haptics.tick()
                confirmEnd = true
            }
            else -> {
                confirmEnd = false
                sendMatchControl(start = false)
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
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = if (outFlash) 0.dp else 44.dp),
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

                    hint != null -> Text(
                        text = hint.orEmpty(),
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
            // Two pills sharing the bottom edge. Match sits left, where the thumb
            // lands first, because it is pressed once per match; Record is pressed
            // once per session and can afford the outer position.
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PillButton(
                    label = when {
                        pendingMatch != null -> "…"
                        confirmEnd -> "End?"
                        matchState != null -> "■ END"
                        else -> "▶ MATCH"
                    },
                    live = confirmEnd,
                    onClick = { onMatchButton() },
                )
                PillButton(
                    label = when {
                        pendingStart != null -> "…"
                        confirmStop -> "Stop?"
                        status.recording -> "■ STOP"
                        else -> "● REC"
                    },
                    live = status.recording,
                    onClick = { onRecordButton() },
                )
            }
        }
    }
}

// Tells the player what to do next rather than what the watch is doing. Both
// buttons below are now on the wrist, so this points at them rather than at the phone.
private fun statusIdleText(text: String) = text.ifBlank { "MATCH to score · REC to film" }

/**
 * One of the two bottom-edge pills: grey at rest, red while live (recording, or
 * an End/Stop confirm armed). Drawn as a sibling of the tap surface, never a
 * child, so pressing a button cannot also score a point.
 */
@Composable
private fun PillButton(label: String, live: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (live) OutRed else RecordIdleGrey)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
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

private val MatchAlertKinds = setOf(
    AlertKind.MATCH_STARTED,
    AlertKind.MATCH_ENDED,
    AlertKind.MATCH_REFUSED,
)

// Recording starts within about a second; Data Layer delivery adds a few hundred ms.
private const val RECORD_REPLY_TIMEOUT_MS = 5_000L

// Shorter: no camera to open, but the phone's process may need cold-starting.
private const val MATCH_REPLY_TIMEOUT_MS = 4_000L
