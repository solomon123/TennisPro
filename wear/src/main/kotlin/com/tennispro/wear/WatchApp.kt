package com.tennispro.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.tennispro.core.scoring.MatchProjection
import com.tennispro.core.scoring.ScoreFormat
import com.tennispro.core.scoring.Side
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The whole watch UI for Phase 0: one full-screen tap target.
 *
 * No buttons, no list, no scrolling. On court the user is holding a racket and
 * looking at a ball, so the entire face is the control surface and the gesture —
 * not the position of a finger — carries the meaning. Phase 1 hangs the real
 * scoring semantics off these same three gestures.
 */
@Composable
fun WatchApp(link: WatchLink, haptics: Haptics) {
    val scope = rememberCoroutineScope()
    val status by WatchEventBus.status.collectAsState()
    val lastAlert by WatchEventBus.lastAlert.collectAsState()
    val matchState by WatchEventBus.matchState.collectAsState()

    var flash by remember { mutableStateOf<AlertKind?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }

    // An OUT call takes over the screen briefly, so a glance immediately after the
    // buzz shows the call rather than the idle face.
    LaunchedEffect(lastAlert) {
        val alert = lastAlert ?: return@LaunchedEffect
        flash = alert.kind
        delay(if (alert.kind == AlertKind.OUT_CALL) 4_000 else 1_200)
        flash = null
    }

    LaunchedEffect(feedback) {
        if (feedback != null) {
            delay(1_200)
            feedback = null
        }
    }

    fun report(gesture: Gesture, label: String) {
        haptics.tick()
        feedback = label
        scope.launch {
            if (!link.sendGesture(gesture)) feedback = "No phone"
        }
    }

    val outFlash = flash == AlertKind.OUT_CALL

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (outFlash) OutRed else MaterialTheme.colors.background)
            .pointerInput(Unit) {
                // Note: registering onDoubleTap delays onTap by the platform's
                // double-tap timeout (~300 ms). That is acceptable for scoring —
                // the alternative, an immediate single tap, would make a double tap
                // impossible to express.
                detectTapGestures(
                    onTap = { report(Gesture.SINGLE_TAP, "Tap") },
                    onDoubleTap = { report(Gesture.DOUBLE_TAP, "Double tap") },
                    onLongPress = { report(Gesture.LONG_PRESS, "Marked") },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp),
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

                matchState != null -> ScoreFace(matchState!!)

                else -> {
                    Text(
                        text = "TennisPro",
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
}

private fun statusIdleText(text: String) = text.ifBlank { "Waiting for phone" }

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
