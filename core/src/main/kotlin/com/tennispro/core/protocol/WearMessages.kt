package com.tennispro.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a haptic alert means, so the watch can pick a distinguishable pattern. */
@Serializable
enum class AlertKind {
    /** Ball landed outside the target box. The one alert that must feel unmistakable. */
    OUT_CALL,

    /** Service let (net cord) — Phase 5 at the earliest, reserved here. */
    LET,

    /** Phase 0 connectivity check, fired from the phone's diagnostics screen. */
    TEST,

    /** Confirmation that a watch tap was received and applied. */
    POINT_LOGGED,

    /** Confirmation that a video bookmark was written. */
    BOOKMARK_SAVED,
}

/** Physical button / gesture the user performed on the watch. */
@Serializable
enum class Gesture {
    SINGLE_TAP,
    DOUBLE_TAP,
    LONG_PRESS,
}

/** Identifies the watch, mostly so haptics and API-gated behaviour can be logged. */
@Serializable
data class WatchInfo(
    val manufacturer: String,
    val model: String,
    val apiLevel: Int,
    val appVersion: String,
)

/**
 * Phone -> watch messages.
 *
 * Every case is additive-only: new fields must carry defaults, because the phone
 * and watch APKs are installed separately and will routinely be different
 * versions on the same wrist. See [WearCodec] for the matching leniency.
 */
@Serializable
sealed interface PhoneToWatch {

    /** Buzz the watch and show a headline. */
    @Serializable
    @SerialName("alert")
    data class Alert(
        val kind: AlertKind,
        val headline: String,
        val detail: String? = null,
        /**
         * Phone's `SystemClock.elapsedRealtime()` when the underlying event was
         * detected. The watch echoes nothing here; this exists so the phone can
         * log detection-to-delivery lag once the watch acknowledges.
         */
        val detectedAtElapsedMs: Long = 0L,
    ) : PhoneToWatch

    /**
     * Round-trip latency probe. [sentAtElapsedMs] is on the *phone's* clock and is
     * echoed back verbatim in [WatchToPhone.Pong], so latency is measured entirely
     * against one clock. Never try to diff phone and watch timestamps directly —
     * the two `elapsedRealtime` bases are unrelated and drift.
     */
    @Serializable
    @SerialName("ping")
    data class Ping(
        val nonce: Long,
        val sentAtElapsedMs: Long,
    ) : PhoneToWatch

    /** Ambient state for the watch face: are we recording, and any short status line. */
    @Serializable
    @SerialName("status")
    data class Status(
        val recording: Boolean,
        val text: String = "",
    ) : PhoneToWatch
}

/** Watch -> phone messages. */
@Serializable
sealed interface WatchToPhone {

    /** Reply to [PhoneToWatch.Ping], echoing the phone's own send timestamp. */
    @Serializable
    @SerialName("pong")
    data class Pong(
        val nonce: Long,
        val phoneSentAtElapsedMs: Long,
        val watch: WatchInfo,
    ) : WatchToPhone

    /** A gesture on the watch. The phone decides what each gesture means. */
    @Serializable
    @SerialName("input")
    data class Input(
        val gesture: Gesture,
    ) : WatchToPhone
}
