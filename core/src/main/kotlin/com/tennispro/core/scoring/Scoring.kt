package com.tennispro.core.scoring

import kotlinx.serialization.Serializable

/** One of the two players/teams being scored. The watch wearer is [Side.A] by convention. */
@Serializable
enum class Side { A, B }

fun Side.other(): Side = if (this == Side.A) Side.B else Side.A

/**
 * The two rule variants clubs actually mix and match. Everything else about
 * scoring — deuce/advantage, tiebreak at 6-6, best-of-N sets — is fixed by the
 * game, so it is not configurable.
 */
@Serializable
data class MatchConfig(
    val setsToWin: Int = 2,
    val gamesToWinSet: Int = 6,
    val tiebreakPointsToWin: Int = 7,
    /** First to 4 points wins a game outright — no advantage past 3-3. */
    val noAd: Boolean = false,
    /** If false, the deciding set is played out to a 2-game lead with no tiebreak. */
    val finalSetTiebreak: Boolean = true,
    /**
     * The match *is* a single tiebreak — no games, no sets. A quick decider or a
     * practice-session format. When true, [setsToWin], [gamesToWinSet], [noAd] and
     * [finalSetTiebreak] are all ignored: the match starts already in a tiebreak
     * and ends the instant that tiebreak is won.
     */
    val tiebreakOnlyMatch: Boolean = false,
)

@Serializable
data class PointCount(val a: Int = 0, val b: Int = 0) {
    fun increment(side: Side): PointCount = if (side == Side.A) copy(a = a + 1) else copy(b = b + 1)
}

/** A finished set's game count. [tiebreak] is the breaker's final point score, kept for display (e.g. "7-6(4)"). */
@Serializable
data class SetResult(val gamesA: Int, val gamesB: Int, val tiebreak: PointCount? = null) {
    fun winner(): Side = if (gamesA > gamesB) Side.A else Side.B
}

/**
 * The authoritative record of a match: rules plus the point-by-point log.
 *
 * Every displayable field — game score, set score, whose serve it is, who has
 * won — is a pure fold over [history] (see [project]). That makes undo trivial
 * and always correct: drop the last entry and refold, rather than trying to
 * invert a deuce/tiebreak/set-boundary transition by hand.
 */
@Serializable
data class MatchState(
    val config: MatchConfig = MatchConfig(),
    val history: List<Side> = emptyList(),
)

fun MatchState.projection(): MatchProjection = project(config, history)

/** Records a point for [side]. A no-op once the match is already decided. */
fun MatchState.pointWon(side: Side): MatchState =
    if (projection().winner != null) this else copy(history = history + side)

/** Drops the last point. A no-op on a match with no history yet. */
fun MatchState.undoLast(): MatchState =
    if (history.isEmpty()) this else copy(history = history.dropLast(1))

/**
 * Compact, display-ready snapshot of a [MatchState]. This — not the full point
 * history — is what gets pushed to the watch: the watch only ever displays and
 * generates points, it never needs to undo one itself.
 */
@Serializable
data class MatchProjection(
    val config: MatchConfig = MatchConfig(),
    val completedSets: List<SetResult> = emptyList(),
    val currentSetGamesA: Int = 0,
    val currentSetGamesB: Int = 0,
    val currentGamePoints: PointCount = PointCount(),
    val inTiebreak: Boolean = false,
    val tiebreakPoints: PointCount = PointCount(),
    val server: Side = Side.A,
    val winner: Side? = null,
)

private fun project(config: MatchConfig, history: List<Side>): MatchProjection {
    val working = WorkingMatch(config)
    for (point in history) {
        if (working.winner != null) break
        working.apply(point)
    }
    return working.toProjection()
}

/**
 * Mutable scratch pad used only inside [project]. The point-by-point rules —
 * game win, deuce/advantage, tiebreak entry and win, set win, match win, and
 * server rotation — all live here because they read far more clearly as
 * sequential mutation than as a chain of `copy()`s.
 */
private class WorkingMatch(private val config: MatchConfig) {
    val completedSets = mutableListOf<SetResult>()
    var gamesA = 0
    var gamesB = 0
    var gamePoints = PointCount()
    var inTiebreak = config.tiebreakOnlyMatch
    var tiebreakPoints = PointCount()
    var server = Side.A
    var winner: Side? = null

    fun apply(side: Side) {
        if (inTiebreak) applyTiebreakPoint(side) else applyGamePoint(side)
    }

    private fun applyGamePoint(side: Side) {
        gamePoints = gamePoints.increment(side)
        val a = gamePoints.a
        val b = gamePoints.b
        val gameWinner = if (config.noAd) {
            when {
                a >= 4 && a > b -> Side.A
                b >= 4 && b > a -> Side.B
                else -> null
            }
        } else {
            when {
                a >= 4 && a - b >= 2 -> Side.A
                b >= 4 && b - a >= 2 -> Side.B
                else -> null
            }
        }
        if (gameWinner != null) gameWon(gameWinner)
    }

    private fun gameWon(winner: Side) {
        if (winner == Side.A) gamesA++ else gamesB++
        gamePoints = PointCount()
        server = server.other()
        checkSetState()
    }

    private fun checkSetState() {
        if (gamesA >= config.gamesToWinSet && gamesA - gamesB >= 2) {
            setWon(Side.A, tiebreak = null)
            return
        }
        if (gamesB >= config.gamesToWinSet && gamesB - gamesA >= 2) {
            setWon(Side.B, tiebreak = null)
            return
        }
        val isDecidingSet = completedSets.count { it.winner() == Side.A } == config.setsToWin - 1 &&
            completedSets.count { it.winner() == Side.B } == config.setsToWin - 1
        val tiebreakDisabledHere = isDecidingSet && !config.finalSetTiebreak
        if (!tiebreakDisabledHere && gamesA == config.gamesToWinSet && gamesB == config.gamesToWinSet) {
            inTiebreak = true
            tiebreakPoints = PointCount()
        }
    }

    private fun applyTiebreakPoint(side: Side) {
        tiebreakPoints = tiebreakPoints.increment(side)
        // First server plays one point, then service alternates every two points —
        // i.e. the change happens right after an odd number of points played.
        if ((tiebreakPoints.a + tiebreakPoints.b) % 2 == 1) server = server.other()

        val a = tiebreakPoints.a
        val b = tiebreakPoints.b
        val tbWinner = when {
            a >= config.tiebreakPointsToWin && a - b >= 2 -> Side.A
            b >= config.tiebreakPointsToWin && b - a >= 2 -> Side.B
            else -> null
        } ?: return

        if (config.tiebreakOnlyMatch) {
            // The breaker IS the match: declare the winner directly, with no games
            // or sets to update. Leaving inTiebreak/tiebreakPoints as they are means
            // the final score (e.g. 7-5) stays on screen rather than resetting to 0-0.
            winner = tbWinner
            return
        }

        if (tbWinner == Side.A) gamesA++ else gamesB++
        inTiebreak = false
        val finishedTiebreak = tiebreakPoints
        tiebreakPoints = PointCount()
        setWon(tbWinner, tiebreak = finishedTiebreak)
    }

    private fun setWon(winner: Side, tiebreak: PointCount?) {
        completedSets += SetResult(gamesA, gamesB, tiebreak)
        gamesA = 0
        gamesB = 0
        val setsWonA = completedSets.count { it.winner() == Side.A }
        val setsWonB = completedSets.count { it.winner() == Side.B }
        if (setsWonA == config.setsToWin) this.winner = Side.A
        if (setsWonB == config.setsToWin) this.winner = Side.B
    }

    fun toProjection() = MatchProjection(
        config = config,
        completedSets = completedSets.toList(),
        currentSetGamesA = gamesA,
        currentSetGamesB = gamesB,
        currentGamePoints = gamePoints,
        inTiebreak = inTiebreak,
        tiebreakPoints = tiebreakPoints,
        server = server,
        winner = winner,
    )
}

/** Turns a [MatchProjection] into the strings a scoreboard actually shows. */
object ScoreFormat {
    private val POINT_NAMES = listOf("0", "15", "30", "40")

    /** Point score as a (sideA, sideB) label pair — "40"/"AD" in ad scoring, raw counts in a tiebreak. */
    fun pointLabels(p: MatchProjection): Pair<String, String> {
        if (p.inTiebreak) return p.tiebreakPoints.a.toString() to p.tiebreakPoints.b.toString()

        val a = p.currentGamePoints.a
        val b = p.currentGamePoints.b
        if (!p.config.noAd && a >= 3 && b >= 3) {
            return when {
                a == b -> "40" to "40"
                a > b -> "AD" to "-"
                else -> "-" to "AD"
            }
        }
        return POINT_NAMES.getOrElse(a) { "40" } to POINT_NAMES.getOrElse(b) { "40" }
    }

    /** e.g. "6-4, 3-6, 4-2" — completed sets followed by the set in progress. */
    fun setsSummary(p: MatchProjection): String {
        val done = p.completedSets.joinToString(", ") { "${it.gamesA}-${it.gamesB}" }
        val current = "${p.currentSetGamesA}-${p.currentSetGamesB}"
        return if (done.isEmpty()) current else "$done, $current"
    }

    /** One-line summary for a haptic alert's detail text. */
    fun summary(p: MatchProjection): String {
        val (pa, pb) = pointLabels(p)
        // A tiebreak-only match has no games/sets to report — the tiebreak score
        // (already what pointLabels returns while inTiebreak) is the whole story.
        if (p.config.tiebreakOnlyMatch) return "$pa-$pb"
        return "${setsSummary(p)} ($pa-$pb)"
    }
}
