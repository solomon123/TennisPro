package com.tennispro.phone.camera

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.util.Range
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.tennispro.phone.MainActivity
import com.tennispro.phone.R
import com.tennispro.phone.storage.Bookmark
import com.tennispro.phone.storage.MatchSession
import com.tennispro.phone.storage.MatchStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What the capture pipeline is currently doing. */
sealed interface CaptureState {
    data object Initialising : CaptureState

    /** Camera is open and previewing, nothing being written to disk. */
    data class Ready(val resolution: String?, val frameRate: Int?) : CaptureState

    data class Recording(
        val session: MatchSession,
        val startedAtElapsedMs: Long,
        val resolution: String?,
        val frameRate: Int?,
        val bookmarkCount: Int,
    ) : CaptureState

    data class Error(val message: String) : CaptureState
}

/**
 * Owns the CameraX binding for the whole app.
 *
 * The camera deliberately lives in a [LifecycleService] rather than in the
 * activity. On a fence mount the phone will be recording for two hours with its
 * screen off; if the `Preview`/`VideoCapture` use cases were bound to an
 * activity's lifecycle, capture would stop the moment the screen blanked.
 *
 * The activity attaches and detaches only the preview *surface*
 * ([attachPreview] / [detachPreview]). Rebinding use cases would finalize an
 * in-progress recording, so the binding itself is never touched once recording
 * has begun.
 */
class RecordingService : LifecycleService() {

    inner class LocalBinder : Binder() {
        val service: RecordingService get() = this@RecordingService
    }

    private val binder = LocalBinder()

    private lateinit var storage: MatchStorage

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var activeSession: MatchSession? = null
    private var recordingStartedAtElapsedMs: Long = 0L
    private var audioEnabled: Boolean = false

    private val _state = MutableStateFlow<CaptureState>(CaptureState.Initialising)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        storage = MatchStorage(this)
        createNotificationChannel()
        lifecycleScope.launch { bindCameraUseCases() }
    }

    override fun onBind(intent: Intent): IBinder {
        // LifecycleService needs its super call to advance the lifecycle to STARTED,
        // which is what lets CameraX actually open the camera.
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_PROMOTE_FOREGROUND) {
            promoteToForeground()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        activeRecording?.stop()
        activeRecording = null
        cameraProvider?.unbindAll()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- preview

    fun attachPreview(surfaceProvider: Preview.SurfaceProvider) {
        preview?.setSurfaceProvider(surfaceProvider)
    }

    /** Releases the preview surface without unbinding, so recording keeps running. */
    fun detachPreview() {
        preview?.setSurfaceProvider(null)
    }

    // -------------------------------------------------------------- recording

    /**
     * Starts recording into a fresh session directory.
     *
     * Caller must have already invoked `startForegroundService` with
     * [ACTION_PROMOTE_FOREGROUND] from a visible activity — from Android 14 a
     * `camera`-typed foreground service cannot be started from the background.
     */
    fun startRecording() {
        val capture = videoCapture
        if (capture == null) {
            _state.value = CaptureState.Error("Camera not ready yet")
            return
        }
        if (activeRecording != null) return

        audioEnabled = hasPermission(Manifest.permission.RECORD_AUDIO)
        val session = storage.createSession()
        val outputOptions = FileOutputOptions.Builder(storage.videoFileFor(session)).build()

        val pending = capture.output
            .prepareRecording(this, outputOptions)
            .apply { if (audioEnabled) withAudioEnabled() }

        activeSession = session

        activeRecording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    recordingStartedAtElapsedMs = SystemClock.elapsedRealtime()
                    _state.value = CaptureState.Recording(
                        session = session,
                        startedAtElapsedMs = recordingStartedAtElapsedMs,
                        resolution = currentResolution(),
                        frameRate = requestedFrameRate,
                        bookmarkCount = 0,
                    )
                    Log.i(TAG, "Recording started: ${session.meta.id}")
                }

                is VideoRecordEvent.Finalize -> {
                    val durationMs = SystemClock.elapsedRealtime() - recordingStartedAtElapsedMs
                    storage.finalizeSession(
                        session = session,
                        durationMs = durationMs,
                        resolution = currentResolution(),
                        frameRate = requestedFrameRate,
                    )
                    activeRecording = null
                    activeSession = null

                    if (event.hasError()) {
                        Log.e(TAG, "Recording finalized with error ${event.error}", event.cause)
                        _state.value = CaptureState.Error("Recording error ${event.error}")
                    } else {
                        Log.i(TAG, "Recording saved: ${event.outputResults.outputUri}")
                        _state.value = CaptureState.Ready(currentResolution(), requestedFrameRate)
                    }

                    stopForegroundCompat()
                    // Clears the started state; the service survives while the UI is bound
                    // and is reclaimed once the last client unbinds.
                    stopSelf()
                }

                else -> Unit // Status events fire ~once a second; nothing to do with them yet.
            }
        }
    }

    fun stopRecording() {
        activeRecording?.stop()
    }

    /**
     * Marks the current moment in the recording. Used by the phone's Mark button and
     * by a long press on the watch, so a good serve can be found later without
     * scrubbing two hours of footage.
     */
    fun bookmark(label: String): Bookmark? {
        val session = activeSession ?: return null
        val offsetMs = SystemClock.elapsedRealtime() - recordingStartedAtElapsedMs
        val bookmark = storage.addBookmark(session, offsetMs, label)
        (_state.value as? CaptureState.Recording)?.let {
            _state.value = it.copy(bookmarkCount = it.bookmarkCount + 1)
        }
        return bookmark
    }

    // ------------------------------------------------------------- internals

    private suspend fun bindCameraUseCases() {
        val provider = try {
            awaitCameraProvider()
        } catch (t: Throwable) {
            Log.e(TAG, "Camera provider unavailable", t)
            _state.value = CaptureState.Error("Camera unavailable: ${t.message}")
            return
        }
        cameraProvider = provider

        if (!hasPermission(Manifest.permission.CAMERA)) {
            _state.value = CaptureState.Error("Camera permission not granted")
            return
        }

        // 1080p is the sweet spot: 4K quadruples the storage bill and the per-frame
        // cost of the Phase 3/4 vision work for pixels we cannot exploit at range.
        // A Recorder may only ever back one VideoCapture, so each bind attempt gets a
        // fresh one rather than recycling the instance from a failed attempt.
        val newRecorder = {
            Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.fromOrderedList(
                        listOf(Quality.FHD, Quality.HD),
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.HD),
                    ),
                )
                .build()
        }

        // Try for 60 fps first. Frame rate is the single biggest lever on serve-speed
        // and bounce-location error, so it is worth a retry rather than silently
        // accepting 30. Some devices reject a hard 60-60 range for this combination
        // of use cases, hence the unconstrained fallback.
        val bound = tryBind(provider, newRecorder(), Range(60, 60)) ||
            tryBind(provider, newRecorder(), null)
        if (!bound) {
            _state.value = CaptureState.Error("Could not bind camera use cases")
            return
        }

        _state.value = CaptureState.Ready(currentResolution(), requestedFrameRate)
        Log.i(TAG, "Camera ready at ${currentResolution()} @ ${requestedFrameRate ?: "device default"} fps")
    }

    private var requestedFrameRate: Int? = null

    private fun tryBind(
        provider: ProcessCameraProvider,
        recorder: Recorder,
        frameRate: Range<Int>?,
    ): Boolean = runCatching {
        provider.unbindAll()

        val newPreview = Preview.Builder().build()
        val newVideoCapture = VideoCapture.Builder(recorder)
            .apply { frameRate?.let { setTargetFrameRate(it) } }
            .build()

        provider.bindToLifecycle(
            this,
            CameraSelector.DEFAULT_BACK_CAMERA,
            newPreview,
            newVideoCapture,
        )

        preview = newPreview
        videoCapture = newVideoCapture
        requestedFrameRate = frameRate?.upper
        true
    }.getOrElse {
        Log.w(TAG, "Bind failed for frameRate=$frameRate", it)
        false
    }

    private suspend fun awaitCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(this)
            future.addListener(
                {
                    try {
                        continuation.resume(future.get())
                    } catch (t: Throwable) {
                        continuation.resumeWithException(t)
                    }
                },
                ContextCompat.getMainExecutor(this),
            )
        }

    private fun currentResolution(): String? =
        videoCapture?.attachedSurfaceResolution?.let { "${it.width}x${it.height}" }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun promoteToForeground() {
        // Declaring the microphone type without holding RECORD_AUDIO throws on
        // API 34+, so the declared type has to track the permission we actually have.
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        startForeground(NOTIFICATION_ID, buildNotification(), type)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Recording match")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.recording_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.recording_channel_description) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_PROMOTE_FOREGROUND = "com.tennispro.phone.action.PROMOTE_FOREGROUND"
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1001
    }
}
