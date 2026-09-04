package com.tennispro.phone.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.tennispro.phone.TennisProApp
import com.tennispro.phone.camera.RecordingService

/** A handful of screens; a navigation library would be more moving parts than routes. */
enum class Screen { HOME, RECORD, WATCH_CHECK, SCORE, CALIBRATE, REPLAY }

@Composable
fun AppRoot(
    app: TennisProApp,
    service: RecordingService?,
    cameraGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onStartRecording: () -> Unit,
) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    val diagnostics = remember { WatchDiagnostics(app.wearLink) }
    // Referencing this eagerly (rather than only from ScoreScreen) is what starts
    // MatchController listening for watch gestures and restores any in-progress
    // match as soon as the app is up, not on first visit to the Score screen.
    val matchController = app.matchController

    Surface(color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            when (screen) {
                Screen.HOME -> HomeScreen(
                    storage = app.storage,
                    diagnostics = diagnostics,
                    matchController = matchController,
                    calibrationStorage = app.calibrationStorage,
                    service = service,
                    onRecord = { screen = Screen.RECORD },
                    onWatchCheck = { screen = Screen.WATCH_CHECK },
                    onScore = { screen = Screen.SCORE },
                    onCalibrate = { screen = Screen.CALIBRATE },
                    onReplay = { screen = Screen.REPLAY },
                )

                Screen.RECORD -> RecordScreen(
                    service = service,
                    wearLink = app.wearLink,
                    calibrationStorage = app.calibrationStorage,
                    cameraGranted = cameraGranted,
                    onRequestPermissions = onRequestPermissions,
                    onStartRecording = onStartRecording,
                    onBack = { screen = Screen.HOME },
                )

                Screen.WATCH_CHECK -> WatchCheckScreen(
                    diagnostics = diagnostics,
                    onBack = { screen = Screen.HOME },
                )

                Screen.SCORE -> ScoreScreen(
                    controller = matchController,
                    onBack = { screen = Screen.HOME },
                )

                Screen.CALIBRATE -> CalibrateScreen(
                    service = service,
                    calibrationStorage = app.calibrationStorage,
                    onBack = { screen = Screen.HOME },
                )

                Screen.REPLAY -> ReplayScreen(
                    matchStorage = app.storage,
                    calibrationStorage = app.calibrationStorage,
                    onBack = { screen = Screen.HOME },
                )
            }
        }
    }
}
