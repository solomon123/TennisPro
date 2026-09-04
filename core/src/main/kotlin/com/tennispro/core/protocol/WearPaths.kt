package com.tennispro.core.protocol

/**
 * Addresses used on the Wearable Data Layer.
 *
 * Two transports, chosen deliberately:
 *
 *  - [PHONE_TO_WATCH] / [WATCH_TO_PHONE] are `MessageClient` paths. Messages are
 *    fire-and-forget and are *not* stored, which is what we want for an out-call
 *    buzz or a score tap: a message that arrives four seconds late is worse than
 *    one that never arrives.
 *
 *  - [MATCH_STATE] is a `DataClient` path. DataItems are latched and replayed on
 *    reconnect, which is what we want for "what is the score right now" — the
 *    watch should show the correct score after a Bluetooth dropout without the
 *    phone having to notice and resend. Wired up in Phase 1.
 *
 * Capability names are declared by each APK in `res/values/wear.xml`. We use them
 * for node discovery rather than blasting every connected node, so a paired
 * tablet or a second watch without the app never receives our messages.
 */
object WearPaths {
    const val PHONE_TO_WATCH = "/tennispro/p2w"
    const val WATCH_TO_PHONE = "/tennispro/w2p"
    const val MATCH_STATE = "/tennispro/match_state"

    /** Key of the JSON-encoded [com.tennispro.core.scoring.MatchProjection] inside the [MATCH_STATE] DataMap. */
    const val MATCH_STATE_KEY = "projection"

    const val CAPABILITY_PHONE = "tennispro_phone"
    const val CAPABILITY_WATCH = "tennispro_watch"
}
