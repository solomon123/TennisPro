package com.tennispro.phone.wear

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.tennispro.core.protocol.AlertKind
import com.tennispro.core.protocol.PhoneToWatch
import com.tennispro.core.protocol.WearCodec
import com.tennispro.core.protocol.WearPaths
import com.tennispro.core.scoring.MatchProjection
import com.tennispro.core.scoring.MatchStateCodec
import kotlinx.coroutines.tasks.await
import kotlin.random.Random

/** A reachable watch running our APK. */
data class WatchNode(val id: String, val displayName: String)

/** Result of trying to push a message to the wrist. */
sealed interface SendOutcome {
    /** No paired node advertises our watch capability — app not installed, or watch off. */
    data object NoWatch : SendOutcome

    data class Sent(val nodeCount: Int) : SendOutcome

    data class Failed(val reason: String) : SendOutcome
}

/**
 * Phone-side wrapper over the Wearable Data Layer.
 *
 * Node discovery goes through [CapabilityClient] rather than [Wearable.getNodeClient],
 * because "every connected node" can include a paired tablet or a second watch that
 * has never had our APK installed. Filtering on the capability declared in
 * `res/values/wear.xml` means we only ever talk to a wrist that can answer.
 */
class WearLink(context: Context) {

    private val appContext = context.applicationContext
    private val messageClient = Wearable.getMessageClient(appContext)
    private val capabilityClient = Wearable.getCapabilityClient(appContext)
    private val dataClient = Wearable.getDataClient(appContext)

    suspend fun reachableWatches(): List<WatchNode> = runCatching {
        capabilityClient
            .getCapability(WearPaths.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE)
            .await()
            .nodes
            .map { WatchNode(it.id, it.displayName) }
    }.getOrElse {
        Log.w(TAG, "Capability lookup failed", it)
        emptyList()
    }

    /**
     * Sends to every reachable watch. Broadcasting rather than picking one node is
     * intentional: with a single watch it is identical, and if the user ever pairs a
     * second one we would otherwise silently buzz the wrong wrist.
     */
    suspend fun send(message: PhoneToWatch): SendOutcome {
        val nodes = reachableWatches()
        if (nodes.isEmpty()) return SendOutcome.NoWatch

        val payload = WearCodec.encode(message)
        var delivered = 0
        var lastError: Throwable? = null

        for (node in nodes) {
            runCatching {
                messageClient.sendMessage(node.id, WearPaths.PHONE_TO_WATCH, payload).await()
            }.onSuccess {
                delivered++
            }.onFailure {
                lastError = it
                Log.w(TAG, "sendMessage to ${node.displayName} failed", it)
            }
        }

        return when {
            delivered > 0 -> SendOutcome.Sent(delivered)
            else -> SendOutcome.Failed(lastError?.message ?: "unknown Data Layer error")
        }
    }

    /**
     * Fires a latency probe. The returned nonce identifies the reply; the caller
     * measures the round trip by subtracting the [PhoneToWatch.Ping.sentAtElapsedMs]
     * that comes back in the Pong from the phone's current `elapsedRealtime()`.
     *
     * Doing it this way — echoing our own timestamp rather than reading the watch's —
     * keeps the whole measurement on one monotonic clock. The two devices' clock
     * bases are unrelated, so a direct phone-minus-watch subtraction is meaningless.
     */
    suspend fun ping(): Pair<Long, SendOutcome> {
        val nonce = Random.nextLong()
        val outcome = send(PhoneToWatch.Ping(nonce = nonce, sentAtElapsedMs = SystemClock.elapsedRealtime()))
        return nonce to outcome
    }

    suspend fun sendTestBuzz(): SendOutcome = send(
        PhoneToWatch.Alert(
            kind = AlertKind.TEST,
            headline = "Test",
            detail = "Phone -> watch OK",
            detectedAtElapsedMs = SystemClock.elapsedRealtime(),
        ),
    )

    /** Fires the exact alert an in/out call will use, so the haptic can be judged on court. */
    suspend fun sendSimulatedOutCall(): SendOutcome = send(
        PhoneToWatch.Alert(
            kind = AlertKind.OUT_CALL,
            headline = "OUT",
            detail = "simulated",
            detectedAtElapsedMs = SystemClock.elapsedRealtime(),
        ),
    )

    suspend fun sendStatus(recording: Boolean, text: String): SendOutcome =
        send(PhoneToWatch.Status(recording = recording, text = text))

    /**
     * Tells the watch whether the phone is recording, with a buzz. Every Record or
     * Stop tap on the watch gets one of these (or [sendRecordingRefused]) back —
     * the phone is usually hung out of reach, so the wrist is the only place the
     * player can see that it worked.
     */
    suspend fun sendRecordingState(recording: Boolean, headline: String) {
        sendStatus(recording, headline)
        send(
            PhoneToWatch.Alert(
                kind = if (recording) AlertKind.RECORDING_STARTED else AlertKind.RECORDING_STOPPED,
                headline = headline,
            ),
        )
    }

    suspend fun sendRecordingRefused(headline: String): SendOutcome =
        send(PhoneToWatch.Alert(kind = AlertKind.RECORDING_REFUSED, headline = headline))

    /**
     * Pushes the live score to the [WearPaths.MATCH_STATE] DataItem, or clears it
     * when [projection] is null (match ended).
     *
     * `DataClient`, not `MessageClient`: this is latched, replayed-on-reconnect
     * state, not a fire-and-forget event — see [WearPaths]. It goes to every node
     * that has ever seen this path, so unlike [send] there is no need to first
     * enumerate reachable watches.
     */
    suspend fun sendMatchState(projection: MatchProjection?): SendOutcome = runCatching {
        if (projection == null) {
            dataClient.deleteDataItems(matchStateUri()).await()
        } else {
            val request = PutDataMapRequest.create(WearPaths.MATCH_STATE).apply {
                dataMap.putString(WearPaths.MATCH_STATE_KEY, MatchStateCodec.encodeProjection(projection))
                dataMap.putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            dataClient.putDataItem(request).await()
        }
        SendOutcome.Sent(1)
    }.getOrElse {
        Log.w(TAG, "Match state sync failed", it)
        SendOutcome.Failed(it.message ?: "unknown Data Layer error")
    }

    private fun matchStateUri(): Uri =
        Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(WearPaths.MATCH_STATE).build()

    private companion object {
        const val KEY_UPDATED_AT = "updatedAtEpochMs"
        const val TAG = "WearLink"
    }
}
