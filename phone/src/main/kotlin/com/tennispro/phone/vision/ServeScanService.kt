package com.tennispro.phone.vision

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tennispro.phone.MainActivity
import com.tennispro.phone.R
import com.tennispro.phone.TennisProApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** What the serve scanner is doing, app-wide. */
data class ServeScanState(
    val activeSessionId: String? = null,
    val progress: Float = 0f,
    val queued: List<String> = emptyList(),
) {
    fun isScanning(sessionId: String) = activeSessionId == sessionId || sessionId in queued
}

/**
 * Runs [ServeScanner] over recordings in the background, one at a time, as a
 * foreground service — a long match takes minutes to scan, and the user will
 * put the phone away or switch apps long before that.
 *
 * Started automatically when a recording finishes (see [RecordingService]),
 * and from Replay's "Find serves" for recordings made before this existed.
 * [stop] ends one recording's scan — from Replay or the notification — and
 * leaves the rest of the queue running and that recording's earlier results
 * in place: a night recording took several minutes to scan on 2026-09-23,
 * and there was no way out but killing the app.
 * Service type `mediaProcessing` on Android 15+, which is what it is;
 * `dataSync` below that, where `mediaProcessing` doesn't exist.
 */
class ServeScanService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var worker: Job? = null
    private val queue = ArrayDeque<String>()
    private var lastNotifiedPercent = -1

    /** The recording whose scan the user asked to stop; checked between frames. */
    @Volatile
    private var stopRequested: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
        if (intent?.action == ACTION_STOP) {
            if (sessionId != null) stopScan(sessionId)
            // Started only to deliver the stop, with nothing to scan: go away again.
            if (worker?.isActive != true) stopSelf(startId)
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, notification(null, 0), foregroundType())

        synchronized(queue) {
            if (sessionId != null && sessionId != _state.value.activeSessionId && sessionId !in queue) queue.addLast(sessionId)
            _state.value = _state.value.copy(queued = queue.toList())
        }
        if (worker?.isActive != true) worker = scope.launch { drain() }
        return START_NOT_STICKY
    }

    private suspend fun drain() {
        val app = application as TennisProApp
        val scanner = ServeScanner(this, app.storage, app.calibrationStorage)
        while (true) {
            val sessionId = synchronized(queue) {
                queue.removeFirstOrNull().also { _state.value = ServeScanState(activeSessionId = it, queued = queue.toList()) }
            } ?: break
            val session = app.storage.findSession(sessionId)
            if (session == null) {
                Log.w(TAG, "Session $sessionId no longer exists")
                continue
            }
            lastNotifiedPercent = -1
            try {
                val result = scanner.scan(
                    session,
                    onProgress = { progress -> onProgress(sessionId, progress) },
                    isCancelled = { !scope.isActive || stopRequested == sessionId },
                )
                app.storage.writeServes(session, result)
                Log.i(TAG, "$sessionId: ${result.serves.size} serves${result.error?.let { " ($it)" } ?: ""}")
            } catch (e: CancellationException) {
                // The service is going away: stop the whole queue. A stop the user asked
                // for ends only this recording, and nothing is written, so a stopped
                // rescan leaves the previous results as they were.
                if (!scope.isActive || stopRequested != sessionId) throw e
                Log.i(TAG, "$sessionId: scan stopped")
            } catch (e: Exception) {
                // One bad recording (unreadable file, model failure) shouldn't stop the queue.
                Log.e(TAG, "Serve scan failed for $sessionId", e)
            } finally {
                if (stopRequested == sessionId) stopRequested = null
            }
        }
        _state.value = ServeScanState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Stops [sessionId]'s scan if it is the one running, or takes it off the queue. */
    private fun stopScan(sessionId: String) {
        synchronized(queue) {
            if (_state.value.activeSessionId == sessionId) {
                stopRequested = sessionId
            } else if (queue.remove(sessionId)) {
                _state.value = _state.value.copy(queued = queue.toList())
            }
        }
    }

    private fun onProgress(sessionId: String, progress: Float) {
        _state.value = _state.value.copy(activeSessionId = sessionId, progress = progress)
        val percent = (progress * 100).toInt()
        if (percent != lastNotifiedPercent) {
            lastNotifiedPercent = percent
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(sessionId, percent))
        }
    }

    /** Android 15's six-hour-a-day `mediaProcessing` limit: stop rather than be killed. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service time limit reached; stopping serve scan")
        scope.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        _state.value = ServeScanState()
        super.onDestroy()
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }

    private fun notification(sessionId: String?, percent: Int): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(getString(R.string.serve_scan_title))
            .setContentText(sessionId ?: getString(R.string.serve_scan_starting))
            .setProgress(100, percent, sessionId == null)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (sessionId != null) {
            val stop = PendingIntent.getService(
                this,
                0,
                stopIntent(this, sessionId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, getString(R.string.serve_scan_stop), stop)
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.serve_scan_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.serve_scan_channel_description) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "ServeScanService"
        private const val CHANNEL_ID = "serve_scan"
        private const val NOTIFICATION_ID = 1002
        private const val EXTRA_SESSION_ID = "com.tennispro.phone.extra.SESSION_ID"
        private const val ACTION_STOP = "com.tennispro.phone.action.STOP_SERVE_SCAN"

        private val _state = MutableStateFlow(ServeScanState())
        val state: StateFlow<ServeScanState> = _state.asStateFlow()

        /**
         * Queues [sessionId] for scanning. False if Android refused to start the
         * service — from Android 12 a foreground service can't be started while the
         * app is in the background — in which case Replay's "Find serves" still works.
         */
        fun enqueue(context: Context, sessionId: String): Boolean = runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ServeScanService::class.java).putExtra(EXTRA_SESSION_ID, sessionId),
            )
        }.onFailure { Log.w(TAG, "Could not start serve scan for $sessionId", it) }.isSuccess

        /**
         * Stops [sessionId]'s scan, or takes it off the queue. A plain start, not a
         * foreground one: it only ever reaches a service that is already running
         * in the foreground, from Replay or the service's own notification.
         */
        fun stop(context: Context, sessionId: String) {
            runCatching { context.startService(stopIntent(context, sessionId)) }
                .onFailure { Log.w(TAG, "Could not stop serve scan for $sessionId", it) }
        }

        private fun stopIntent(context: Context, sessionId: String) =
            Intent(context, ServeScanService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_SESSION_ID, sessionId)
    }
}
