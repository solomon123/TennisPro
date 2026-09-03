package com.tennispro.phone.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tennispro.core.protocol.Gesture
import com.tennispro.core.protocol.WatchInfo
import com.tennispro.core.protocol.WatchToPhone
import com.tennispro.phone.wear.SendOutcome
import com.tennispro.phone.wear.WatchNode
import com.tennispro.phone.wear.WearEventBus
import com.tennispro.phone.wear.WearLink
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

/** Summary of a latency burst, in milliseconds. */
data class LatencyStats(
    val samples: List<Long>,
    val requested: Int,
) {
    val received: Int get() = samples.size
    val lost: Int get() = requested - received
    val min: Long? get() = samples.minOrNull()
    val max: Long? get() = samples.maxOrNull()
    val median: Long?
        get() = samples.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
}

/**
 * Drives the Phase 0 phone/watch connectivity check.
 *
 * The point of the latency burst is not diagnostics for their own sake: an out-call
 * is only worth delivering if it lands while the point is still live. Measuring the
 * real round trip on the actual watch, outdoors, is the cheapest way to find out
 * whether the wrist is a viable output channel before any vision code exists.
 */
class WatchDiagnostics(private val link: WearLink) {

    var watches by mutableStateOf<List<WatchNode>>(emptyList())
        private set

    var status by mutableStateOf("Not checked yet")
        private set

    var busy by mutableStateOf(false)
        private set

    var latency by mutableStateOf<LatencyStats?>(null)
        private set

    var watchInfo by mutableStateOf<WatchInfo?>(null)
        private set

    var lastGesture by mutableStateOf<Gesture?>(null)
        private set

    var gestureCount by mutableStateOf(0)
        private set

    suspend fun refresh() {
        watches = link.reachableWatches()
        status = when {
            watches.isEmpty() ->
                "No watch found. Check the watch app is installed and Bluetooth is connected."
            else -> "Connected: ${watches.joinToString { it.displayName }}"
        }
    }

    /** Collects watch gestures for as long as the screen is on it. */
    suspend fun observeGestures() {
        WearEventBus.events.collect { received ->
            (received.message as? WatchToPhone.Input)?.let {
                lastGesture = it.gesture
                gestureCount++
            }
        }
    }

    /**
     * Sends [count] pings and waits for the matching pongs.
     *
     * Latency is `pongReceivedAt - pingSentAt`, both read from the *phone's*
     * `elapsedRealtime()`; the watch only echoes our timestamp back untouched. This
     * is deliberate — the two devices' monotonic clocks share no epoch, so any
     * direct phone-minus-watch arithmetic would produce a meaningless number.
     */
    suspend fun runLatencyBurst(count: Int = 10) = coroutineScope {
        if (busy) return@coroutineScope
        busy = true
        latency = null

        // Declared outside the try so the finally can always cancel it. It collects a
        // SharedFlow that never completes, and coroutineScope will not return while a
        // child is still running — leaving it alive on an error path would hang here.
        var collector: Job? = null

        try {
            val expected = mutableSetOf<Long>()
            val samples = mutableListOf<Long>()

            collector = launch {
                WearEventBus.events.collect { received ->
                    val pong = received.message as? WatchToPhone.Pong ?: return@collect
                    if (!expected.remove(pong.nonce)) return@collect
                    watchInfo = pong.watch
                    samples += received.receivedAtElapsedMs - pong.phoneSentAtElapsedMs
                }
            }

            var noWatch = false
            repeat(count) {
                val (nonce, outcome) = link.ping()
                when (outcome) {
                    is SendOutcome.Sent -> expected += nonce
                    SendOutcome.NoWatch -> noWatch = true
                    is SendOutcome.Failed -> status = "Send failed: ${outcome.reason}"
                }
                // Spaced out so the pings queue behind each other on the BLE link
                // rather than measuring our own burst congestion.
                delay(250)
            }

            if (noWatch) {
                status = "No watch found. Check the watch app is installed and Bluetooth is connected."
            } else {
                // Generous: a cold Data Layer connection can take a second to wake.
                runCatching {
                    withTimeout(4_000) {
                        while (expected.isNotEmpty()) delay(50)
                    }
                }.onFailure { if (it !is TimeoutCancellationException) throw it }

                latency = LatencyStats(samples.toList(), count)
                status = if (samples.isEmpty()) {
                    "Pings sent but nothing came back — is the watch app running?"
                } else {
                    "Round trip median ${LatencyStats(samples.toList(), count).median} ms"
                }
            }

        } finally {
            collector?.cancel()
            busy = false
        }
    }

    suspend fun testBuzz() {
        status = describe(link.sendTestBuzz(), "Test buzz")
    }

    suspend fun simulateOutCall() {
        status = describe(link.sendSimulatedOutCall(), "Simulated OUT")
    }

    private fun describe(outcome: SendOutcome, label: String): String = when (outcome) {
        is SendOutcome.Sent -> "$label sent to ${outcome.nodeCount} watch(es)"
        SendOutcome.NoWatch -> "$label not sent — no watch reachable"
        is SendOutcome.Failed -> "$label failed: ${outcome.reason}"
    }
}
