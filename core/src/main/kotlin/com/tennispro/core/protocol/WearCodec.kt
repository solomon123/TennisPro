package com.tennispro.core.protocol

import kotlinx.serialization.json.Json

/**
 * Wire codec for Data Layer payloads.
 *
 * JSON rather than protobuf: `MessageClient` allows 100 KB per message and ours
 * are a few dozen bytes, so the encoding cost is irrelevant and being able to
 * read a payload straight out of a logcat dump during on-court debugging is
 * worth more than the bytes.
 *
 * [Json.ignoreUnknownKeys] is the important setting. The phone and watch APKs are
 * installed separately, so an older watch build will regularly receive messages
 * from a newer phone build. Unknown fields must be skipped, not thrown on — a
 * `SerializationException` inside a `WearableListenerService` means a silently
 * dropped out-call.
 */
object WearCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        // Short discriminator, and one that will not collide with a field name.
        classDiscriminator = "_t"
        encodeDefaults = true
    }

    fun encode(message: PhoneToWatch): ByteArray =
        json.encodeToString(PhoneToWatch.serializer(), message).encodeToByteArray()

    fun encode(message: WatchToPhone): ByteArray =
        json.encodeToString(WatchToPhone.serializer(), message).encodeToByteArray()

    /** Returns null on malformed or unrecognised payloads rather than throwing. */
    fun decodePhoneToWatch(bytes: ByteArray): PhoneToWatch? =
        runCatching { json.decodeFromString(PhoneToWatch.serializer(), bytes.decodeToString()) }.getOrNull()

    /** Returns null on malformed or unrecognised payloads rather than throwing. */
    fun decodeWatchToPhone(bytes: ByteArray): WatchToPhone? =
        runCatching { json.decodeFromString(WatchToPhone.serializer(), bytes.decodeToString()) }.getOrNull()
}
