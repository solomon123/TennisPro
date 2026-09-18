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

    /** A game was just won. Phase 1 scoring. */
    GAME_WON,

    /** A set was just won. Phase 1 scoring. */
    SET_WON,

    /** The match was just won. Phase 1 scoring. */
    MATCH_WON,

    /** The last point was undone from the watch or phone. Phase 1 scoring. */
    UNDO,

    /** The phone started writing a recording, however it was started. */
    RECORDING_STARTED,

    /** The phone finished a recording, or was asked to stop while not recording. */
    RECORDING_STOPPED,

    /** The phone could not act on the watch's Record button; the headline says why. */
    RECORDING_REFUSED,

    /** A match was started, however it was started. Distinct from [MATCH_WON]. */
    MATCH_STARTED,

    /** The match was ended and cleared, or End was pressed with no match running. */
    MATCH_ENDED,

    /** The phone would not start or end a match; the headline says why. */
    MATCH_REFUSED,
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

    /**
     * The watch's Record/Stop button, for a phone hung out of reach. Its own
     * message rather than another [Gesture]: while a match is scored every gesture
     * already means a point or an undo. Explicit start/stop rather than a toggle,
     * so a watch showing stale state can never stop a recording it meant to start.
     */
    @Serializable
    @SerialName("record")
    data class RecordControl(
        val start: Boolean,
    ) : WatchToPhone

    /**
     * The watch's Match/End button. Separate from [RecordControl] because the two
     * are genuinely independent: a practice session is recorded without a score,
     * and a match can be scored with nothing recording.
     *
     * Explicit start/end for the same reason [RecordControl] is explicit — a watch
     * showing stale state must never end a match it meant to start. The format is
     * not carried here: the phone reuses the last format it was given, so the
     * wrist never has to express a MatchConfig.
     */
    @Serializable
    @SerialName("match")
    data class MatchControl(
        val start: Boolean,
    ) : WatchToPhone
}
