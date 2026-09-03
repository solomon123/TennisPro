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

/** Phase 0 has three screens; a navigation library would be more moving parts than routes. */
enum class Screen { HOME, RECORD, WATCH_CHECK }

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

    Surface(color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            when (screen) {
                Screen.HOME -> HomeScreen(
                    storage = app.storage,
                    diagnostics = diagnostics,
                    onRecord = { screen = Screen.RECORD },
                    onWatchCheck = { screen = Screen.WATCH_CHECK },
                )

                Screen.RECORD -> RecordScreen(
                    service = service,
                    wearLink = app.wearLink,
                    cameraGranted = cameraGranted,
                    onRequestPermissions = onRequestPermissions,
                    onStartRecording = onStartRecording,
                    onBack = { screen = Screen.HOME },
                )

                Screen.WATCH_CHECK -> WatchCheckScreen(
                    diagnostics = diagnostics,
                    onBack = { screen = Screen.HOME },
                )
            }
        }
    }
}
