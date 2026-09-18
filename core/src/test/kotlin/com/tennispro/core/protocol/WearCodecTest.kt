package com.tennispro.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearCodecTest {

    @Test
    fun `alert round trips`() {
        val original = PhoneToWatch.Alert(
            kind = AlertKind.OUT_CALL,
            headline = "OUT",
            detail = "long by 24 cm",
            detectedAtElapsedMs = 123_456L,
        )
        assertEquals(original, WearCodec.decodePhoneToWatch(WearCodec.encode(original)))
    }

    @Test
    fun `ping round trips and preserves the phone clock verbatim`() {
        val original = PhoneToWatch.Ping(nonce = -42L, sentAtElapsedMs = 9_876_543_210L)
        val decoded = WearCodec.decodePhoneToWatch(WearCodec.encode(original)) as PhoneToWatch.Ping
        assertEquals(original.nonce, decoded.nonce)
        assertEquals(original.sentAtElapsedMs, decoded.sentAtElapsedMs)
    }

    @Test
    fun `status round trips`() {
        val original = PhoneToWatch.Status(recording = true, text = "REC 01:23")
        assertEquals(original, WearCodec.decodePhoneToWatch(WearCodec.encode(original)))
    }

    @Test
    fun `pong round trips`() {
        val original = WatchToPhone.Pong(
            nonce = 7L,
            phoneSentAtElapsedMs = 555L,
            watch = WatchInfo("samsung", "SM-L305F", 34, "0.1.0"),
        )
        assertEquals(original, WearCodec.decodeWatchToPhone(WearCodec.encode(original)))
    }

    @Test
    fun `every gesture round trips`() {
        for (gesture in Gesture.entries) {
            val original = WatchToPhone.Input(gesture)
            assertEquals(original, WearCodec.decodeWatchToPhone(WearCodec.encode(original)))
        }
    }

    @Test
    fun `record control round trips both ways`() {
        for (start in listOf(true, false)) {
            val original = WatchToPhone.RecordControl(start)
            assertEquals(original, WearCodec.decodeWatchToPhone(WearCodec.encode(original)))
        }
    }

    @Test
    fun `match control round trips both ways`() {
        for (start in listOf(true, false)) {
            val original = WatchToPhone.MatchControl(start)
            assertEquals(original, WearCodec.decodeWatchToPhone(WearCodec.encode(original)))
        }
    }

    /**
     * The two buttons on the watch face send near-identical payloads, so a shared
     * or copy-pasted discriminator would have End silently stopping the recording.
     */
    @Test
    fun `match control and record control are distinct on the wire`() {
        val match = WearCodec.encode(WatchToPhone.MatchControl(start = true)).decodeToString()
        val record = WearCodec.encode(WatchToPhone.RecordControl(start = true)).decodeToString()
        assertNotEquals(match, record)
        assertEquals(
            WatchToPhone.MatchControl(start = true),
            WearCodec.decodeWatchToPhone(match.encodeToByteArray()),
        )
        assertEquals(
            WatchToPhone.RecordControl(start = true),
            WearCodec.decodeWatchToPhone(record.encodeToByteArray()),
        )
    }

    @Test
    fun `every alert kind round trips`() {
        for (kind in AlertKind.entries) {
            val original = PhoneToWatch.Alert(kind = kind, headline = kind.name)
            assertEquals(original, WearCodec.decodePhoneToWatch(WearCodec.encode(original)))
        }
    }

    /**
     * The compatibility guarantee that matters: a newer phone build sending a
     * field an older watch build has never heard of must still be understood.
     */
    @Test
    fun `unknown fields from a newer peer are ignored`() {
        val futurePayload = """
            {"_t":"alert","kind":"OUT_CALL","headline":"OUT","confidenceBand":"HIGH","bounceXcm":412.5}
        """.trimIndent().encodeToByteArray()

        val decoded = WearCodec.decodePhoneToWatch(futurePayload) as? PhoneToWatch.Alert
        assertEquals(AlertKind.OUT_CALL, decoded?.kind)
        assertEquals("OUT", decoded?.headline)
    }

    @Test
    fun `garbage decodes to null instead of throwing`() {
        assertNull(WearCodec.decodePhoneToWatch("not json".encodeToByteArray()))
        assertNull(WearCodec.decodePhoneToWatch(ByteArray(0)))
        assertNull(WearCodec.decodeWatchToPhone("""{"_t":"unknown_case"}""".encodeToByteArray()))
    }

    /** A message that cannot fit in a Data Layer payload would fail silently on device. */
    @Test
    fun `payloads stay far below the 100 KB MessageClient limit`() {
        val biggest = PhoneToWatch.Alert(
            kind = AlertKind.OUT_CALL,
            headline = "OUT",
            detail = "long by 24 cm, confidence high",
            detectedAtElapsedMs = Long.MAX_VALUE,
        )
        assertTrue(WearCodec.encode(biggest).size < 512)
    }
}
