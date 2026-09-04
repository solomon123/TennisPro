package com.tennispro.wear

import com.tennispro.core.protocol.PhoneToWatch
import com.tennispro.core.scoring.MatchProjection
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process hand-off from [WatchWearableListenerService] to the UI.
 *
 * [lastAlert] is a StateFlow so the screen shows the most recent call even if the
 * user raises their wrist several seconds after the buzz — which is the normal
 * case, since they were busy playing the point.
 */
object WatchEventBus {

    private val _alerts = MutableSharedFlow<PhoneToWatch.Alert>(replay = 0, extraBufferCapacity = 16)
    val alerts: SharedFlow<PhoneToWatch.Alert> = _alerts.asSharedFlow()

    private val _lastAlert = MutableStateFlow<PhoneToWatch.Alert?>(null)
    val lastAlert: StateFlow<PhoneToWatch.Alert?> = _lastAlert.asStateFlow()

    private val _status = MutableStateFlow(PhoneToWatch.Status(recording = false, text = ""))
    val status: StateFlow<PhoneToWatch.Status> = _status.asStateFlow()

    /**
     * The live score, or null when no match is in progress. Backed by the
     * [com.tennispro.core.protocol.WearPaths.MATCH_STATE] DataItem, which is
     * latched and replays on reconnect — so unlike [alerts] this is a StateFlow
     * that survives a Bluetooth dropout without the phone resending anything.
     */
    private val _matchState = MutableStateFlow<MatchProjection?>(null)
    val matchState: StateFlow<MatchProjection?> = _matchState.asStateFlow()

    fun publishAlert(alert: PhoneToWatch.Alert) {
        _lastAlert.value = alert
        _alerts.tryEmit(alert)
    }

    fun publishStatus(status: PhoneToWatch.Status) {
        _status.value = status
    }

    fun publishMatchState(projection: MatchProjection?) {
        _matchState.value = projection
    }
}
