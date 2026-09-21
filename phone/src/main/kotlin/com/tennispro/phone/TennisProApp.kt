package com.tennispro.phone

import android.app.Application
import com.tennispro.phone.calibration.CalibrationStorage
import com.tennispro.phone.score.MatchController
import com.tennispro.phone.score.ScoreStorage
import com.tennispro.phone.storage.MatchStorage
import com.tennispro.phone.storage.SpeedPreferences
import com.tennispro.phone.wear.WearLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
    val speedPreferences: SpeedPreferences by lazy { SpeedPreferences(this) }
    private val scoreStorage: ScoreStorage by lazy { ScoreStorage(this) }

    /** For work that must outlive the screen or service that started it, like a last message to the watch. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Reads any in-progress match from disk and starts listening for watch
    // gestures on first access — see AppRoot, which touches this eagerly so
    // that happens as soon as the UI comes up, not on first visit to the score screen.
    val matchController: MatchController by lazy { MatchController(scoreStorage, wearLink) }
}
