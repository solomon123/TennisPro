package com.tennispro.core.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun MatchState.play(vararg sides: Side): MatchState = sides.fold(this) { s, side -> s.pointWon(side) }

class ScoringTest {

    @Test
    fun `love game point sequence`() {
        var state = MatchState()
        assertEquals("0" to "0", ScoreFormat.pointLabels(state.projection()))

        state = state.pointWon(Side.A)
        assertEquals("15" to "0", ScoreFormat.pointLabels(state.projection()))

        state = state.pointWon(Side.A)
        assertEquals("30" to "0", ScoreFormat.pointLabels(state.projection()))

        state = state.pointWon(Side.A)
        assertEquals("40" to "0", ScoreFormat.pointLabels(state.projection()))

        state = state.pointWon(Side.A)
        val p = state.projection()
        assertEquals(1, p.currentSetGamesA)
        assertEquals("0" to "0", ScoreFormat.pointLabels(p)) // fresh game
    }

    @Test
    fun `deuce and advantage`() {
        val state = MatchState().play(Side.A, Side.B, Side.A, Side.B, Side.A, Side.B) // 3-3
        assertEquals("40" to "40", ScoreFormat.pointLabels(state.projection()))

        val advA = state.pointWon(Side.A)
        assertEquals("AD" to "-", ScoreFormat.pointLabels(advA.projection()))

        // Back to deuce if the other side answers.
        val backToDeuce = advA.pointWon(Side.B)
        assertEquals("40" to "40", ScoreFormat.pointLabels(backToDeuce.projection()))
        assertEquals(0, backToDeuce.projection().currentSetGamesA)

        val gameWon = advA.pointWon(Side.A)
        assertEquals(1, gameWon.projection().currentSetGamesA)
    }

    @Test
    fun `no-ad game is decided by the first point past 3-3`() {
        val config = MatchConfig(noAd = true)
        val deuce = MatchState(config).play(Side.A, Side.B, Side.A, Side.B, Side.A, Side.B)
        val decided = deuce.pointWon(Side.B)
        val p = decided.projection()
        assertEquals(1, p.currentSetGamesB)
        assertEquals(0, p.currentSetGamesA)
    }

    @Test
    fun `game requires winning by two`() {
        // A gets to 40-15 (3-1) then B claws to 40-40, decisive point still needs a 2-clear margin.
        var state = MatchState()
        repeat(3) { state = state.pointWon(Side.A) } // 40-0
        repeat(3) { state = state.pointWon(Side.B) } // 40-40 -> deuce
        assertEquals(0, state.projection().currentSetGamesA)
        state = state.pointWon(Side.A) // advantage A
        assertEquals(0, state.projection().currentSetGamesA)
        state = state.pointWon(Side.A) // A wins by two clear points
        assertEquals(1, state.projection().currentSetGamesA)
    }

    @Test
    fun `winning six games by two clear wins the set outright`() {
        var state = MatchState()
        repeat(6) { state = winGame(state, Side.A) }
        val p = state.projection()
        assertEquals(1, p.completedSets.size)
        assertEquals(SetResult(6, 0), p.completedSets[0])
        assertEquals(0, p.currentSetGamesA)
        assertEquals(0, p.currentSetGamesB)
    }

    @Test
    fun `six games all triggers a tiebreak instead of an outright win`() {
        val state = playToGamesAll(MatchState(), 6)
        val p = state.projection()
        assertTrue(p.inTiebreak)
        assertEquals(0, p.completedSets.size)
        assertEquals(6, p.currentSetGamesA)
        assertEquals(6, p.currentSetGamesB)
    }

    @Test
    fun `tiebreak is won by two clear points from seven`() {
        var state = playToGamesAll(MatchState(), 6)
        // Run the breaker to 6-6, then A must win by two.
        state = state.play(*Array(6) { Side.A })
        state = state.play(*Array(6) { Side.B })
        assertTrue(state.projection().inTiebreak)
        state = state.pointWon(Side.A) // 7-6, not yet 2 clear
        assertTrue(state.projection().inTiebreak)
        state = state.pointWon(Side.A) // 8-6
        val p = state.projection()
        assertTrue(!p.inTiebreak)
        assertEquals(1, p.completedSets.size)
        assertEquals(SetResult(7, 6, PointCount(8, 6)), p.completedSets[0])
    }

    @Test
    fun `tiebreak server rotates after the first point then every two`() {
        var state = playToGamesAll(MatchState(), 6)
        val serverAtStart = state.projection().server

        state = state.pointWon(Side.A) // point 1
        assertEquals(serverAtStart.other(), state.projection().server)

        state = state.pointWon(Side.A) // point 2 - no rotation yet
        assertEquals(serverAtStart.other(), state.projection().server)

        state = state.pointWon(Side.A) // point 3 - rotate back
        assertEquals(serverAtStart, state.projection().server)
    }

    @Test
    fun `server alternates every ordinary game`() {
        var state = MatchState()
        val first = state.projection().server
        state = winGame(state, Side.A)
        assertEquals(first.other(), state.projection().server)
        state = winGame(state, Side.B)
        assertEquals(first, state.projection().server)
    }

    @Test
    fun `best of three match ends after either side takes two sets`() {
        var state = MatchState(MatchConfig(setsToWin = 2))
        state = winSet(state, Side.A, 6, 2)
        assertNull(state.projection().winner)
        state = winSet(state, Side.A, 6, 3)
        assertEquals(Side.A, state.projection().winner)
        assertEquals(2, state.projection().completedSets.size)
    }

    @Test
    fun `points after the match is decided are ignored`() {
        var state = MatchState(MatchConfig(setsToWin = 1))
        state = winSet(state, Side.A, 6, 0)
        assertEquals(Side.A, state.projection().winner)
        val history = state.history

        val after = state.pointWon(Side.B)
        assertEquals(history, after.history)
        assertEquals(Side.A, after.projection().winner)
    }

    @Test
    fun `final set without a tiebreak is played out to a two game lead`() {
        val config = MatchConfig(setsToWin = 2, finalSetTiebreak = false)
        var state = MatchState(config)
        state = winSet(state, Side.A, 6, 3)
        state = winSet(state, Side.B, 6, 4)
        // Deciding set: run it to 6-6, then keep playing past the usual tiebreak trigger.
        state = playToGamesAll(state, 6)
        assertTrue(!state.projection().inTiebreak)
        state = winGame(state, Side.A) // 7-6, not decided
        assertNull(state.projection().winner)
        state = winGame(state, Side.A) // 8-6, decided
        assertEquals(Side.A, state.projection().winner)
    }

    @Test
    fun `undo restores the exact previous projection across a game boundary`() {
        var state = MatchState()
        repeat(3) { state = state.pointWon(Side.A) } // 40-0
        val before = state.projection()

        state = state.pointWon(Side.A) // game won, 1-0
        assertEquals(1, state.projection().currentSetGamesA)

        state = state.undoLast()
        assertEquals(before, state.projection())
    }

    @Test
    fun `undo restores the exact previous projection across a set boundary`() {
        var state = MatchState()
        repeat(5) { state = winGame(state, Side.A) } // 5-0
        repeat(3) { state = state.pointWon(Side.A) } // 40-0 in the 6th game
        val before = state.projection()

        state = state.pointWon(Side.A) // set won
        assertEquals(1, state.projection().completedSets.size)

        state = state.undoLast()
        assertEquals(before, state.projection())
    }

    @Test
    fun `undo on an empty history is a no-op`() {
        val state = MatchState()
        assertEquals(state, state.undoLast())
    }

    @Test
    fun `undo after the match is decided un-decides it`() {
        var state = MatchState(MatchConfig(setsToWin = 1))
        state = winSet(state, Side.A, 6, 0)
        assertEquals(Side.A, state.projection().winner)

        state = state.undoLast()
        assertNull(state.projection().winner)
        assertEquals(5, state.projection().currentSetGamesA)
    }

    @Test
    fun `tiebreak-only match starts already in a tiebreak`() {
        val state = MatchState(MatchConfig(tiebreakOnlyMatch = true))
        val p = state.projection()
        assertTrue(p.inTiebreak)
        assertEquals(0, p.tiebreakPoints.a)
        assertEquals(0, p.tiebreakPoints.b)
        assertNull(p.winner)
    }

    @Test
    fun `tiebreak-only match ends the instant the breaker is won, no games or sets`() {
        val config = MatchConfig(tiebreakOnlyMatch = true)
        var state = MatchState(config)
        state = state.play(*Array(6) { Side.A }) // 6-0
        assertNull(state.projection().winner)

        state = state.pointWon(Side.A) // 7-0, win by 7 clear
        val p = state.projection()
        assertEquals(Side.A, p.winner)
        assertTrue(p.inTiebreak)
        assertEquals(7, p.tiebreakPoints.a)
        assertEquals(0, p.tiebreakPoints.b)
        assertEquals(0, p.currentSetGamesA)
        assertEquals(0, p.currentSetGamesB)
        assertTrue(p.completedSets.isEmpty())
    }

    @Test
    fun `tiebreak-only match still requires winning by two`() {
        var state = MatchState(MatchConfig(tiebreakOnlyMatch = true))
        state = state.play(*Array(6) { Side.A }) // 6-0
        state = state.play(*Array(6) { Side.B }) // 6-6
        assertNull(state.projection().winner)

        state = state.pointWon(Side.A) // 7-6, not yet 2 clear
        assertNull(state.projection().winner)

        state = state.pointWon(Side.A) // 8-6, decided
        assertEquals(Side.A, state.projection().winner)
    }

    @Test
    fun `undo works through a tiebreak-only match`() {
        var state = MatchState(MatchConfig(tiebreakOnlyMatch = true))
        state = state.play(*Array(6) { Side.A }) // 6-0
        val before = state.projection()

        state = state.pointWon(Side.A) // 7-0, match won
        assertEquals(Side.A, state.projection().winner)

        state = state.undoLast()
        assertEquals(before, state.projection())
    }

    @Test
    fun `tiebreak-only summary omits the redundant games line`() {
        var state = MatchState(MatchConfig(tiebreakOnlyMatch = true))
        state = state.play(Side.A, Side.A, Side.B)
        assertEquals("2-1", ScoreFormat.summary(state.projection()))
    }

    @Test
    fun `projection and codec round trip`() {
        var state = MatchState()
        state = winGame(state, Side.A)
        state = state.play(Side.B, Side.B, Side.A)

        val encodedState = MatchStateCodec.encodeState(state)
        assertEquals(state, MatchStateCodec.decodeState(encodedState))

        val projection = state.projection()
        val encodedProjection = MatchStateCodec.encodeProjection(projection)
        assertEquals(projection, MatchStateCodec.decodeProjection(encodedProjection))
    }

    /**
     * A match started from the watch carries no format, so it replays the last
     * config off disk. Every field has to survive that round trip or the wrist
     * would silently start a different match from the one last played.
     */
    @Test
    fun `match config round trips with every field off its default`() {
        val config = MatchConfig(
            setsToWin = 3,
            gamesToWinSet = 4,
            tiebreakPointsToWin = 10,
            noAd = true,
            finalSetTiebreak = false,
            tiebreakOnlyMatch = true,
        )
        assertEquals(config, MatchStateCodec.decodeConfig(MatchStateCodec.encodeConfig(config)))
    }

    @Test
    fun `an unreadable config decodes to null rather than throwing`() {
        assertNull(MatchStateCodec.decodeConfig("not json"))
    }

    // ---- helpers -----------------------------------------------------------

    /** Plays out one game (4 love points) for [winner] without touching the other side's score. */
    private fun winGame(state: MatchState, winner: Side): MatchState =
        state.play(winner, winner, winner, winner)

    /**
     * Alternates game wins [n] times each so the set reaches n-n without ever
     * being 2 games clear along the way — repeating one side's wins back to
     * back would end the set outright long before the tie is reached.
     */
    private fun playToGamesAll(state: MatchState, n: Int): MatchState {
        var s = state
        repeat(n) {
            s = winGame(s, Side.A)
            s = winGame(s, Side.B)
        }
        return s
    }

    /** Plays enough games for [winner] to take a set at [winnerGames]-[loserGames]. */
    private fun winSet(state: MatchState, winner: Side, winnerGames: Int, loserGames: Int): MatchState {
        var s = state
        repeat(loserGames) { s = winGame(s, winner.other()) }
        repeat(winnerGames) { s = winGame(s, winner) }
        return s
    }
}
