package com.tennispro.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.tennispro.core.protocol.PhoneToWatch
import com.tennispro.core.protocol.WearCodec
import com.tennispro.core.protocol.WearPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives phone messages whether or not the watch app is open.
 *
 * This is the delivery path that actually matters for line calls: during a point
 * the watch screen is off and the app is not in the foreground, so the alert has
 * to arrive here and vibrate without any UI being alive.
 */
class WatchWearableListenerService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var haptics: Haptics
    private lateinit var link: WatchLink

    override fun onCreate() {
        super.onCreate()
        haptics = Haptics(this)
        link = WatchLink(this)
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearPaths.PHONE_TO_WATCH) {
            super.onMessageReceived(event)
            return
        }

        val message = WearCodec.decodePhoneToWatch(event.data)
        if (message == null) {
            // Almost certainly a newer phone build sending a case this APK predates.
            // Drop the one message rather than take the listener down mid-match.
            Log.w(TAG, "Undecodable phone payload (${event.data.size} bytes)")
            return
        }

        when (message) {
            is PhoneToWatch.Alert -> {
                // Vibrate first, publish second: the buzz is the product, the UI
                // update is a nicety the user may not look at for another 20 seconds.
                haptics.play(message.kind)
                WatchEventBus.publishAlert(message)
            }

            is PhoneToWatch.Ping -> scope.launch {
                link.sendPong(message.nonce, message.sentAtElapsedMs)
            }

            is PhoneToWatch.Status -> WatchEventBus.publishStatus(message)
        }
    }

    private companion object {
        const val TAG = "WatchWearListener"
    }
}
