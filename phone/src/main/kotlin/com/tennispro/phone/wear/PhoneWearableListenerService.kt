package com.tennispro.phone.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.tennispro.core.protocol.WearCodec
import com.tennispro.core.protocol.WearPaths

/**
 * Receives watch messages even when no activity is running, so a score tap made
 * with the phone strapped to a fence and its screen off still lands.
 *
 * Play Services calls [onMessageReceived] on a background thread. Keep it cheap
 * and non-blocking — this is the hot path for score input.
 */
class PhoneWearableListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearPaths.WATCH_TO_PHONE) {
            super.onMessageReceived(event)
            return
        }

        val message = WearCodec.decodeWatchToPhone(event.data)
        if (message == null) {
            // Most likely an older/newer watch build sending a case we do not know.
            // Dropping one message beats crashing the listener for the whole match.
            Log.w(TAG, "Undecodable watch payload (${event.data.size} bytes) from ${event.sourceNodeId}")
            return
        }

        Log.d(TAG, "watch -> phone: $message")
        WearEventBus.publish(message)
    }

    private companion object {
        const val TAG = "PhoneWearListener"
    }
}
