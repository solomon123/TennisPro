package com.tennispro.phone.score

import android.util.Log
import com.tennispro.core.protocol.AlertKind
import com.tennispro.core.protocol.Gesture
import com.tennispro.core.protocol.PhoneToWatch
import com.tennispro.core.protocol.WatchToPhone
import com.tennispro.core.scoring.MatchConfig
import com.tennispro.core.scoring.MatchProjection
import com.tennispro.core.scoring.MatchState
import com.tennispro.core.scoring.ScoreFormat
import com.tennispro.core.scoring.Side
import com.tennispro.core.scoring.pointWon
import com.tennispro.core.scoring.projection
import com.tennispro.core.scoring.undoLast
import com.tennispro.phone.wear.WearEventBus
import com.tennispro.phone.wear.WearLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the match currently being scored: applies points, persists every
 * change, and keeps the watch's [com.tennispro.core.protocol.WearPaths.MATCH_STATE]
 * DataItem in sync.
 *
 * A watch gesture and a phone button both end up here through the same two
 * calls ([pointFor] / [undo]), so there is exactly one place that decides what
 * counts as a game/set/match win worth a distinct haptic.
 *
 * [Gesture.LONG_PRESS] is also how [com.tennispro.phone.ui.RecordScreen] marks a
 * moment in a recording. While a match is being scored it means undo only:
 * RecordScreen ignores it then, so one press never both undoes a point and
 * drops a mark. Marking matters less now that serves are found automatically.
 */
class MatchController(
    private val storage: ScoreStorage,
    private val wearLink: WearLink,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _match = MutableStateFlow(storage.load())
    val match: StateFlow<MatchState?> = _match.asStateFlow()

    init {
        // Push whatever we loaded from disk to the watch right away, so a phone
        // restart mid-match does not leave the wrist showing a stale score.
        _match.value?.let { pushToWatch(it.projection()) }

        scope.launch {
            WearEventBus.events.collect { received ->
                val gesture = (received.message as? WatchToPhone.Input)?.gesture ?: return@collect
                when (gesture) {
                    Gesture.SINGLE_TAP -> pointFor(Side.A)
                    Gesture.DOUBLE_TAP -> pointFor(Side.B)
                    Gesture.LONG_PRESS -> undo()
                }
            }
        }
    }

    fun startMatch(config: MatchConfig = MatchConfig()) {
        val fresh = MatchState(config = config)
        _match.value = fresh
        storage.save(fresh)
        // Remembered so a watch-started match, which carries no format, gets this one.
        storage.saveLastConfig(config)
        pushToWatch(fresh.projection())
    }

    fun endMatch() {
        _match.value = null
        storage.clear()
        pushToWatch(null)
    }

    /**
     * The watch's Match/End button, routed here by
     * [com.tennispro.phone.wear.PhoneWearableListenerService] rather than through
     * [WearEventBus]. That service is what wakes this process when a message
     * arrives with the app closed — exactly the case this button exists for, the
     * phone strapped to a fence — and calling straight in avoids racing the bus,
     * whose `replay = 0` would drop the command if this controller's collector had
     * not started subscribing yet.
     *
     * Every branch answers the wrist. The phone is out of reach, so an unanswered
     * tap is indistinguishable from a broken link.
     */
    fun handleWatchMatchControl(start: Boolean) {
        val current = _match.value
        when {
            // A decided match still on screen is finished business — starting the
            // next one over it is what the user means.
            start && current != null && current.projection().winner == null -> {
                scope.launch {
                    wearLink.send(
                        PhoneToWatch.Alert(
                            kind = AlertKind.MATCH_REFUSED,
                            headline = "Match already on",
                            detail = ScoreFormat.summary(current.projection()),
                        ),
                    )
                }
            }

            start -> {
                val config = storage.lastConfig()
                startMatch(config)
                scope.launch {
                    wearLink.send(
                        PhoneToWatch.Alert(
                            kind = AlertKind.MATCH_STARTED,
                            headline = "Match on",
                            detail = if (config.tiebreakOnlyMatch) "Tiebreak" else "Best of ${config.setsToWin * 2 - 1}",
                        ),
                    )
                }
            }

            else -> {
                // Answered even with no match running, so End always confirms
                // something — same contract as Stop when not recording.
                val summary = current?.let { ScoreFormat.summary(it.projection()) }
                endMatch()
                scope.launch {
                    wearLink.send(
                        PhoneToWatch.Alert(
                            kind = AlertKind.MATCH_ENDED,
                            headline = if (summary == null) "No match" else "Match ended",
                            detail = summary,
                        ),
                    )
                }
            }
        }
    }

    /** Records a point for [side]. Called by both a watch gesture and the phone's own buttons. */
    fun pointFor(side: Side) {
        val current = _match.value ?: return
        val before = current.projection()
        val after = current.pointWon(side)
        if (after.history == current.history) return // match already decided

        apply(after)
        scope.launch { wearLink.send(pointAlert(before, after.projection())) }
    }

    /** Drops the last point. Called by both a watch long-press and the phone's Undo button. */
    fun undo() {
        val current = _match.value ?: return
        if (current.history.isEmpty()) return

        val after = current.undoLast()
        apply(after)
        scope.launch {
            wearLink.send(
                PhoneToWatch.Alert(
                    kind = AlertKind.UNDO,
                    headline = "Undo",
                    detail = ScoreFormat.summary(after.projection()),
                ),
            )
        }
    }

    private fun apply(state: MatchState) {
        _match.value = state
        storage.save(state)
        pushToWatch(state.projection())
    }

    private fun pushToWatch(projection: MatchProjection?) {
        scope.launch {
            val outcome = wearLink.sendMatchState(projection)
            Log.d(TAG, "Match state sync: $outcome")
        }
    }

    private fun pointAlert(before: MatchProjection, after: MatchProjection): PhoneToWatch.Alert {
        val kind = when {
            after.winner != null -> AlertKind.MATCH_WON
            after.completedSets.size != before.completedSets.size -> AlertKind.SET_WON
            after.currentSetGamesA != before.currentSetGamesA ||
                after.currentSetGamesB != before.currentSetGamesB -> AlertKind.GAME_WON
            else -> AlertKind.POINT_LOGGED
        }
        val headline = when (kind) {
            AlertKind.MATCH_WON -> if (after.winner == Side.A) "Match! You win" else "Match! Opponent wins"
            AlertKind.SET_WON -> "Set"
            AlertKind.GAME_WON -> "Game"
            else -> "Point"
        }
        return PhoneToWatch.Alert(kind = kind, headline = headline, detail = ScoreFormat.summary(after))
    }

    private companion object {
        const val TAG = "MatchController"
    }
}
