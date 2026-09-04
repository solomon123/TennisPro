package com.tennispro.phone

import android.app.Application
import com.tennispro.phone.calibration.CalibrationStorage
import com.tennispro.phone.score.MatchController
import com.tennispro.phone.score.ScoreStorage
import com.tennispro.phone.storage.MatchStorage
import com.tennispro.phone.wear.WearLink

/**
 * Manual dependency wiring.
 *
 * A handful of long-lived collaborators and no graph worth generating, so a DI
 * framework would be pure dependency weight. Revisit if the object count grows
 * much further.
 */
class TennisProApp : Application() {

    val storage: MatchStorage by lazy { MatchStorage(this) }
    val wearLink: WearLink by lazy { WearLink(this) }
    val calibrationStorage: CalibrationStorage by lazy { CalibrationStorage(this) }
    private val scoreStorage: ScoreStorage by lazy { ScoreStorage(this) }

    // Reads any in-progress match from disk and starts listening for watch
    // gestures on first access — see AppRoot, which touches this eagerly so
    // that happens as soon as the UI comes up, not on first visit to the score screen.
    val matchController: MatchController by lazy { MatchController(scoreStorage, wearLink) }
}
