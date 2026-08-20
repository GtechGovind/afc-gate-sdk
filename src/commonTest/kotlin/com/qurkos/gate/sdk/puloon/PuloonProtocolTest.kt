package com.qurkos.gate.sdk.puloon

import com.qurkos.gate.sdk.GateClock
import com.qurkos.gate.sdk.GateSetting
import com.qurkos.gate.sdk.internal.FrameDecodeResult
import com.qurkos.gate.sdk.internal.puloon.PuloonFrame
import com.qurkos.gate.sdk.internal.puloon.PuloonFrameCodec
import com.qurkos.gate.sdk.internal.puloon.PuloonFrameDecoder
import com.qurkos.gate.sdk.internal.puloon.PuloonPayloadCodec
import com.qurkos.gate.sdk.internal.puloon.PuloonSettingsCodec
import com.qurkos.gate.sdk.internal.puloon.ascii
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PuloonProtocolTest {
    @Test
    fun crcMatchesSpecificationVector() {
        val protected = byteArrayOf(0xF2.toByte(), 0x00, 0x03, 0x43, 0x30, 0x30)
        assertEquals(0xF61B, PuloonFrameCodec.crc16Xmodem(protected))
    }

    @Test
    fun frameMatchesSpecificationExample() {
        val actual = PuloonFrameCodec.encode(PuloonFrame(0x00F2, 3, "C00".encodeToByteArray()))
        val expected =
            byteArrayOf(
                0x0A,
                0xF2.toByte(),
                0x00,
                0x03,
                0x43,
                0x30,
                0x30,
                0x46,
                0x36,
                0x31,
                0x42,
                0x0D,
            )
        assertContentEquals(expected, actual)
    }

    @Test
    fun decoderHandlesNoiseFragmentationAndCoalescedFrames() {
        val first = PuloonFrameCodec.encode(PuloonFrame(0x000D, 0, "V00".encodeToByteArray()))
        val second = PuloonFrameCodec.encode(PuloonFrame(2, 0, "H00".encodeToByteArray()))
        val decoder = PuloonFrameDecoder()

        assertTrue(decoder.feed(byteArrayOf(0x55, 0x66) + first.copyOfRange(0, 4)).isEmpty())
        val results = decoder.feed(first.copyOfRange(4, first.size) + second)

        assertEquals(2, results.size)
        val firstFrame = assertIs<PuloonFrame>(assertIs<FrameDecodeResult.Frame>(results[0]).value)
        val secondFrame = assertIs<PuloonFrame>(assertIs<FrameDecodeResult.Frame>(results[1]).value)
        assertEquals(0x000D, firstFrame.sequence)
        assertEquals('H'.code.toByte(), secondFrame.command)
    }

    @Test
    fun corruptCrcProducesDecoderError() {
        val bytes = PuloonFrameCodec.encode(PuloonFrame(7, 0, "S00".encodeToByteArray()))
        bytes[bytes.lastIndex - 1] = if (bytes[bytes.lastIndex - 1] == 0x30.toByte()) 0x31 else 0x30

        val result = PuloonFrameDecoder().feed(bytes).single()

        assertIs<FrameDecodeResult.Error>(result)
        assertTrue(result.message.contains("CRC"))
    }

    @Test
    fun clockAndSettingsCodecsRoundTrip() {
        val clock = GateClock(LocalDateTime(2028, 2, 29, 12, 34, 56))
        assertEquals(clock, PuloonPayloadCodec.decodeClock(PuloonPayloadCodec.encodeClock(clock)))
        val settings =
            setOf(
                GateSetting.NoEntryTimeout(100.seconds),
                GateSetting.NormalOpenMode(false),
                GateSetting.HurryUpLevel(2),
                GateSetting.TagTimeoutFromLastTag(true),
                GateSetting.TailingSensitivity(1),
                GateSetting.BuzzerTimeoutUnits(15),
                GateSetting.SafetyRegionTimeout(100.seconds),
                GateSetting.ChildDetection(0),
            )
        assertEquals(settings, PuloonSettingsCodec.decode(PuloonSettingsCodec.encode(settings)))
    }

    @Test
    fun frameDefensivelyCopiesPayloadAtBothBoundaries() {
        val source = "S00".encodeToByteArray()
        val frame = PuloonFrame(1, 0, source)
        source[0] = ascii('X')
        val exposed = frame.payload
        exposed[0] = ascii('Y')

        assertEquals(ascii('S'), frame.command)
        assertEquals(ascii('S'), frame.payload.first())
    }

    @Test
    fun oversizedUnterminatedInputIsBoundedAndReported() {
        val result = PuloonFrameDecoder().feed(ByteArray(9_000) { ascii('A') }).single()

        assertIs<FrameDecodeResult.Error>(result)
        assertTrue(result.message.contains("exceeded"))
    }

    @Test
    fun malformedSemanticPayloadsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            PuloonPayloadCodec.decodeClock("28-02-29123456".encodeToByteArray())
        }
        assertFailsWith<IllegalArgumentException> {
            PuloonSettingsCodec.decode("1001X110F640".encodeToByteArray())
        }
    }

    @Test
    fun duplicateSettingTypesAreRejectedDeterministically() {
        val settings =
            completeSettings() +
                setOf(
                    GateSetting.BuzzerTimeoutUnits(1),
                    GateSetting.BuzzerTimeoutUnits(2),
                )

        assertFailsWith<IllegalArgumentException> { PuloonSettingsCodec.encode(settings) }
    }

    private fun completeSettings(): Set<GateSetting> =
        setOf(
            GateSetting.NoEntryTimeout(100.seconds),
            GateSetting.NormalOpenMode(false),
            GateSetting.HurryUpLevel(2),
            GateSetting.TagTimeoutFromLastTag(true),
            GateSetting.TailingSensitivity(1),
            GateSetting.BuzzerTimeoutUnits(15),
            GateSetting.SafetyRegionTimeout(100.seconds),
            GateSetting.ChildDetection(0),
        )
}
