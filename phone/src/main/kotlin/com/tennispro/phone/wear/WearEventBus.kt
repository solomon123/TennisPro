package com.tennispro.phone.wear

import android.os.SystemClock
import com.tennispro.core.protocol.WatchToPhone
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process hand-off from [PhoneWearableListenerService] to whatever is running.
 *
 * A `WearableListenerService` is instantiated by Play Services in our own process,
 * so a singleton is enough; nothing here crosses a process boundary.
 *
 * [events] deliberately has `replay = 0`. Watch taps are commands — a score tap
 * replayed to a newly-subscribing collector would count the point twice. Anything
 * that just wants to *display* the most recent event reads [lastEvent] instead.
 */
object WearEventBus {

    data class Received(
        val message: WatchToPhone,
        /** Phone's `elapsedRealtime()` at receipt, on the same clock we send Pings with. */
        val receivedAtElapsedMs: Long,
    )

    private val _events = MutableSharedFlow<Received>(replay = 0, extraBufferCapacity = 64)
    val events: SharedFlow<Received> = _events.asSharedFlow()

    private val _lastEvent = MutableStateFlow<Received?>(null)
    val lastEvent: StateFlow<Received?> = _lastEvent.asStateFlow()

    /** True once any watch has spoken to us in this process lifetime. */
    private val _watchSeen = MutableStateFlow(false)
    val watchSeen: StateFlow<Boolean> = _watchSeen.asStateFlow()

    fun publish(message: WatchToPhone) {
        val received = Received(message, SystemClock.elapsedRealtime())
        _lastEvent.value = received
        _watchSeen.value = true
        _events.tryEmit(received)
    }
}
