package com.tennispro.phone.camera

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Display
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
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
import com.tennispro.core.protocol.WatchToPhone
import com.tennispro.phone.MainActivity
import com.tennispro.phone.R
import com.tennispro.phone.TennisProApp
import com.tennispro.phone.storage.Bookmark
import com.tennispro.phone.storage.MatchSession
import com.tennispro.phone.storage.MatchStorage
import com.tennispro.phone.vision.ServeScanService
import com.tennispro.phone.wear.WearEventBus
import kotlinx.coroutines.Dispatchers
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
    private lateinit var cameraPreferences: CameraPreferences

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var activeSession: MatchSession? = null
    private var recordingStartedAtElapsedMs: Long = 0L
    private var audioEnabled: Boolean = false

    private val _state = MutableStateFlow<CaptureState>(CaptureState.Initialising)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    private val _facing = MutableStateFlow(CameraFacing.BACK)
    val facing: StateFlow<CameraFacing> = _facing.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        storage = MatchStorage(this)
        cameraPreferences = CameraPreferences(this)
        createNotificationChannel()
        lifecycleScope.launch { bindCameraUseCases() }

        // Stop from the watch is handled here, not in RecordScreen: a recording
        // outlives that screen, and the phone may be locked in its mount by the
        // time the watch asks. Starting stays with RecordScreen — see there.
        lifecycleScope.launch {
            WearEventBus.events.collect { received ->
                val control = received.message as? WatchToPhone.RecordControl ?: return@collect
                if (!control.start) stopFromWatch()
            }
        }
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

    /**
     * Turns a `PreviewView.getBitmap()` snapshot into a frame in the recorded
     * video's pixel space (see [PreviewFrames] for why the two differ), or
     * null until the camera is bound.
     */
    fun previewSnapshotToVideoFrame(viewSnapshot: Bitmap): Bitmap? {
        val info = preview?.resolutionInfo ?: return null
        val crop = info.cropRect
        val previewSize = if (info.rotationDegrees % 180 == 0) {
            Size(crop.width(), crop.height())
        } else {
            Size(crop.height(), crop.width())
        }
        val recorded = videoCapture?.attachedSurfaceResolution ?: return null
        // Same orientation as the preview as displayed; recordings carry no
        // rotation hint in this app's landscape mount (checked via the mp4's tkhd matrix).
        val videoSize = if ((recorded.width >= recorded.height) == (previewSize.width >= previewSize.height)) {
            recorded
        } else {
            Size(recorded.height, recorded.width)
        }
        Log.i(
            TAG,
            "Preview snapshot ${viewSnapshot.width}x${viewSnapshot.height} (stream ${previewSize.width}x${previewSize.height}) " +
                "-> video frame ${videoSize.width}x${videoSize.height}",
        )
        return PreviewFrames.toVideoFrame(viewSnapshot, previewSize, videoSize)
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
                    // From the event, not the button: the buzz means the file is really being written.
                    tellWatch(recording = true, headline = "Recording")
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
                        tellWatch(recording = false, headline = "Recording error")
                    } else {
                        Log.i(TAG, "Recording saved: ${event.outputResults.outputUri}")
                        _state.value = CaptureState.Ready(currentResolution(), requestedFrameRate)
                        tellWatch(recording = false, headline = "Stopped")

                        // See the note on newVideoCapture in tryBind(): the front
                        // camera's recorded file comes out of CameraX with the
                        // wrong rotation baked into its container regardless of
                        // what was requested at capture time, confirmed via
                        // ffprobe. Patched after the fact rather than at capture
                        // time since there is no capture-time knob that reaches it.
                        lifecycleScope.launch(Dispatchers.IO) {
                            if (_facing.value == CameraFacing.FRONT) {
                                runCatching { Mp4Rotation.stripVideoRotation(storage.videoFileFor(session)) }
                                    .onFailure { Log.w(TAG, "Could not fix front-camera recording rotation", it) }
                            }
                            // Into the gallery before the scan, and only once the
                            // file is final — the rotation fix above rewrites it,
                            // and publishing moves it out of the session directory.
                            // The scan then reads it back through its new URI.
                            storage.exportToGallery(session)
                            ServeScanService.enqueue(this@RecordingService, session.meta.id)
                        }
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

    /** The watch always gets an answer: the Finalize event's, or this one. */
    private fun stopFromWatch() {
        val recording = activeRecording
        if (recording == null) {
            tellWatch(recording = false, headline = "Not recording")
        } else {
            recording.stop()
        }
    }

    /**
     * On the app's scope, not [lifecycleScope]: Finalize calls [stopSelf] right
     * after this, which would cancel the send when no screen is bound.
     */
    private fun tellWatch(recording: Boolean, headline: String) {
        val app = application as TennisProApp
        app.appScope.launch { app.wearLink.sendRecordingState(recording, headline) }
    }

    /**
     * Rebinds the camera use cases with the other physical camera. Some
     * fence/clamp mounts hold the phone screen-out, which puts the front
     * camera facing the court instead of the back one.
     *
     * A no-op while recording — rebinding would finalize it, the same
     * invariant [tryBind] already relies on everywhere else.
     */
    fun switchCamera(newFacing: CameraFacing) {
        if (activeRecording != null || newFacing == _facing.value) return
        cameraPreferences.save(newFacing)
        _state.value = CaptureState.Initialising
        lifecycleScope.launch { bindCameraUseCases() }
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

        val facing = cameraPreferences.load()

        // Try for 60 fps first. Frame rate is the single biggest lever on serve-speed
        // and bounce-location error, so it is worth a retry rather than silently
        // accepting 30. Some devices reject a hard 60-60 range for this combination
        // of use cases, hence the unconstrained fallback.
        val bound = tryBind(provider, newRecorder(), Range(60, 60), facing) ||
            tryBind(provider, newRecorder(), null, facing)
        if (!bound) {
            _state.value = CaptureState.Error("Could not bind camera use cases")
            return
        }

        _facing.value = facing
        _state.value = CaptureState.Ready(currentResolution(), requestedFrameRate)
        Log.i(TAG, "Camera ready ($facing) at ${currentResolution()} @ ${requestedFrameRate ?: "device default"} fps")
    }

    private var requestedFrameRate: Int? = null

    private fun tryBind(
        provider: ProcessCameraProvider,
        recorder: Recorder,
        frameRate: Range<Int>?,
        facing: CameraFacing,
    ): Boolean = runCatching {
        provider.unbindAll()

        // Read fresh via DisplayManager, not a possibly-stale Activity window,
        // since this binds from a Service that outlives any one activity.
        val baseRotation = currentDisplayRotation()

        // Confirmed via logcat's PreviewView/PreviewTransform lines on-device
        // (Galaxy S25 Ultra): with targetRotation = ROTATION_90, CameraX itself
        // computes TransformationInfo{getRotationDegrees=0, isMirroring=false}
        // for the *front* camera preview — i.e. CameraX's own rotation math,
        // not just our input, is producing a value that's empirically upside
        // down on this hardware. Feeding the opposite target rotation for the
        // front camera's *preview* is a direct, verified-in-logs correction to
        // that computed value, not a guess about camera mounting angles.
        val previewRotation = if (facing == CameraFacing.FRONT) rotate180(baseRotation) else baseRotation

        val newPreview = Preview.Builder()
            // 16:9 to match the FHD recording. CameraX defaults Preview to 4:3,
            // which made the recording a vertical crop of what the preview (and
            // so the live calibration freeze) showed — see PreviewFrames.
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .build(),
            )
            .setTargetRotation(previewRotation)
            // Never mirror, front or back: the calibration UI taps court
            // corners on this surface, and a mirrored display would silently
            // swap left/right relative to what the sensor actually captured.
            .setMirrorMode(MirrorMode.MIRROR_MODE_OFF)
            .build()

        // Deliberately *not* the same rotation as the preview above. Confirmed
        // via ffprobe on-device: VideoCapture's own written rotation hint does
        // not track setTargetRotation the way Preview's does — front and back
        // recordings came out with the identical container rotation regardless
        // of what was requested here, and that value is correct for the back
        // camera but 180 degrees wrong for the front. baseRotation (unmodified,
        // matching what was in place before the preview fix above) is what's
        // actually confirmed correct for VideoCapture; the front camera's
        // recorded-file rotation is fixed after the fact instead, in
        // fixFrontCameraRotation, once it's known whether the finished file is
        // actually wrong.
        val newVideoCapture = VideoCapture.Builder(recorder)
            .setTargetRotation(baseRotation)
            .setMirrorMode(MirrorMode.MIRROR_MODE_OFF)
            .apply { frameRate?.let { setTargetFrameRate(it) } }
            .build()

        val selector = if (facing == CameraFacing.FRONT) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        provider.bindToLifecycle(this, selector, newPreview, newVideoCapture)

        preview = newPreview
        videoCapture = newVideoCapture
        requestedFrameRate = frameRate?.upper
        true
    }.getOrElse {
        Log.w(TAG, "Bind failed for frameRate=$frameRate facing=$facing", it)
        false
    }

    /**
     * Queried via [DisplayManager], not `Context.getDisplay()` / the activity's
     * window: this service binds the camera independently of any one activity
     * (see the class doc), so it needs a display-rotation source that does not
     * depend on one being currently attached.
     */
    private fun currentDisplayRotation(): Int {
        val displayManager = getSystemService(DisplayManager::class.java)
        return displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
    }

    /** The opposite `Surface.ROTATION_*` constant, 180 degrees around. */
    private fun rotate180(rotation: Int): Int = when (rotation) {
        Surface.ROTATION_0 -> Surface.ROTATION_180
        Surface.ROTATION_90 -> Surface.ROTATION_270
        Surface.ROTATION_180 -> Surface.ROTATION_0
        Surface.ROTATION_270 -> Surface.ROTATION_90
        else -> rotation
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
