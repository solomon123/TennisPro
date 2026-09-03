package com.tennispro.phone

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.tennispro.phone.camera.RecordingService
import com.tennispro.phone.ui.AppRoot
import com.tennispro.phone.ui.theme.TennisProTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val service = MutableStateFlow<RecordingService?>(null)
    private val cameraGranted = MutableStateFlow(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service.value = (binder as? RecordingService.LocalBinder)?.service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service.value = null
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        cameraGranted.value = result[Manifest.permission.CAMERA] ?: hasCamera()
        if (cameraGranted.value) bindRecordingService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The phone spends the match clamped to a fence. Keeping the screen on while
        // the app is in front makes framing and the REC indicator usable; recording
        // itself does not depend on it, because the camera lives in the service.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        cameraGranted.value = hasCamera()

        setContent {
            TennisProTheme {
                val boundService by service.collectAsState()
                val granted by cameraGranted.collectAsState()

                AppRoot(
                    app = application as TennisProApp,
                    service = boundService,
                    cameraGranted = granted,
                    onRequestPermissions = ::requestPermissions,
                    onStartRecording = ::startRecording,
                )
            }
        }

        if (!granted()) requestPermissions()
    }

    override fun onStart() {
        super.onStart()
        if (hasCamera()) bindRecordingService()
    }

    override fun onStop() {
        // Detach the surface but leave the binding alone: unbinding is what lets the
        // service be reclaimed when we are *not* recording, and a promoted foreground
        // service survives this when we are.
        service.value?.detachPreview()
        runCatching { unbindService(connection) }
        service.value = null
        super.onStop()
    }

    /**
     * Promotes the service to the foreground *before* asking it to record. From
     * Android 14 a `camera`-typed foreground service may only be started while the
     * app is visible, which is exactly here.
     */
    private fun startRecording() {
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_PROMOTE_FOREGROUND
        }
        ContextCompat.startForegroundService(this, intent)
        service.value?.startRecording()
    }

    private fun bindRecordingService() {
        bindService(
            Intent(this, RecordingService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    private fun requestPermissions() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
        )
    }

    private fun granted() = cameraGranted.value

    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
}
