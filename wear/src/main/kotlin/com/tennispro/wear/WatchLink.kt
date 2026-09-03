package com.tennispro.wear

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import com.tennispro.core.protocol.Gesture
import com.tennispro.core.protocol.WatchInfo
import com.tennispro.core.protocol.WatchToPhone
import com.tennispro.core.protocol.WearCodec
import com.tennispro.core.protocol.WearPaths
import kotlinx.coroutines.tasks.await

/** Watch-side wrapper over the Data Layer. Mirror image of the phone's `WearLink`. */
class WatchLink(context: Context) {

    private val appContext = context.applicationContext
    private val messageClient = Wearable.getMessageClient(appContext)
    private val capabilityClient = Wearable.getCapabilityClient(appContext)

    val watchInfo: WatchInfo = WatchInfo(
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        apiLevel = Build.VERSION.SDK_INT,
        appVersion = BuildConfig.VERSION_NAME,
    )

    suspend fun sendGesture(gesture: Gesture): Boolean =
        send(WatchToPhone.Input(gesture))

    suspend fun sendPong(nonce: Long, phoneSentAtElapsedMs: Long): Boolean =
        send(
            WatchToPhone.Pong(
                nonce = nonce,
                // Echoed back untouched. Never substitute the watch's own clock here:
                // the phone measures the round trip against the value it sent.
                phoneSentAtElapsedMs = phoneSentAtElapsedMs,
                watch = watchInfo,
            ),
        )

    private suspend fun send(message: WatchToPhone): Boolean {
        val nodes = runCatching {
            capabilityClient
                .getCapability(WearPaths.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE)
                .await()
                .nodes
        }.getOrElse {
            Log.w(TAG, "Capability lookup failed", it)
            return false
        }

        if (nodes.isEmpty()) {
            Log.w(TAG, "No phone reachable")
            return false
        }

        val payload = WearCodec.encode(message)
        var delivered = false
        for (node in nodes) {
            runCatching {
                messageClient.sendMessage(node.id, WearPaths.WATCH_TO_PHONE, payload).await()
            }.onSuccess { delivered = true }
                .onFailure { Log.w(TAG, "sendMessage to ${node.displayName} failed", it) }
        }
        return delivered
    }

    private companion object {
        const val TAG = "WatchLink"
    }
}
