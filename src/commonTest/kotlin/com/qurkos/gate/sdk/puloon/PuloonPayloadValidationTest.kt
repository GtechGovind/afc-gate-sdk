package com.qurkos.gate.sdk.puloon

import com.qurkos.gate.sdk.GateHardwareProfile
import com.qurkos.gate.sdk.GateMechanism
import com.qurkos.gate.sdk.GateModule
import com.qurkos.gate.sdk.GateSetting
import com.qurkos.gate.sdk.GateSite
import com.qurkos.gate.sdk.internal.puloon.PuloonPayloadCodec
import com.qurkos.gate.sdk.internal.puloon.PuloonSettingsCodec
import com.qurkos.gate.sdk.internal.puloon.ascii
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PuloonPayloadValidationTest {
    @Test
    fun statusRejectsBadLengthCountersAndMode() {
        assertFailsWith<IllegalArgumentException> {
            PuloonPayloadCodec.decodeStatus(ByteArray(22), GateHardwareProfile())
        }
        val badCounter = baseStatus().also { it[1] = ascii('X') }
        assertFailsWith<IllegalArgumentException> {
            PuloonPayloadCodec.decodeStatus(badCounter, GateHardwareProfile())
        }
        val badMode = baseStatus().also { it[0] = ascii('Z') }
        assertFailsWith<IllegalArgumentException> {
            PuloonPayloadCodec.decodeStatus(badMode, GateHardwareProfile())
        }
    }

    @Test
    fun sensorPayloadRejectsInvalidHexAndDecodesSpecialProfiles() {
        assertFailsWith<IllegalArgumentException> {
            PuloonPayloadCodec.decodeSensors("G00000000000".encodeToByteArray(), GateHardwareProfile())
        }
        val swing =
            PuloonPayloadCodec.decodeSensors(
                "002080000000".encodeToByteArray(),
                GateHardwareProfile(mechanism = GateMechanism.SWING),
            )
        assertTrue(swing.active.any { it.number == 23 })
        assertTrue(swing.active.any { it.number == 24 })
        val child =
            PuloonPayloadCodec.decodeSensors(
                "000008000000".encodeToByteArray(),
                GateHardwareProfile(site = GateSite.CHINA, modules = setOf(GateModule.CHILD_SENSORS)),
            )
        assertTrue(child.active.any { it.number == 22 })
    }

    @Test
    fun clockRequiresExactDigitsAndValidCalendarValues() {
        assertFailsWith<IllegalArgumentException> { PuloonPayloadCodec.decodeClock("260230123456".encodeToByteArray()) }
        assertFailsWith<IllegalArgumentException> { PuloonPayloadCodec.decodeClock("26010112345".encodeToByteArray()) }
        assertFailsWith<IllegalArgumentException> { PuloonPayloadCodec.decodeClock("2601011234567".encodeToByteArray()) }
        assertFailsWith<IllegalArgumentException> { PuloonPayloadCodec.decodeClock("26010112X456".encodeToByteArray()) }
    }

    @Test
    fun extensionPayloadsValidateLengthAndOffsetNibbles() {
        assertFailsWith<IllegalArgumentException> { PuloonPayloadCodec.decodeStandby(ByteArray(4)) }
        assertFailsWith<IllegalArgumentException> { PuloonPayloadCodec.decodeDoorTiming(ByteArray(4)) }
        assertFailsWith<IllegalArgumentException> {
            PuloonPayloadCodec.decodeDoorTiming(byteArrayOf(ascii('1'), ascii('/'), ascii('0'), ascii('0'), ascii('0')))
        }
        assertFailsWith<IllegalArgumentException> { PuloonPayloadCodec.encodeOffsetHexByte(-1) }
        assertFailsWith<IllegalArgumentException> { PuloonPayloadCodec.encodeOffsetHexByte(256) }
    }

    @Test
    fun settingsAcceptBoundariesAndRejectEveryMalformedBoolean() {
        val boundaries =
            setOf(
                GateSetting.SensorSensitivity(255),
                GateSetting.NormalOpenMode(true),
                GateSetting.HurryUpLevel(2),
                GateSetting.TagTimeoutFromLastTag(false),
                GateSetting.TailingSensitivity(0),
                GateSetting.PassageTimeout(255.seconds),
                GateSetting.ChildHeight(255),
                GateSetting.ChildDetection(true),
            )
        assertEquals(boundaries, PuloonSettingsCodec.decode(PuloonSettingsCodec.encode(boundaries)))
        listOf(2, 4, 10).forEach { offset ->
            val malformed = PuloonSettingsCodec.encode(boundaries).also { it[offset] = ascii('X') }
            assertFailsWith<IllegalArgumentException> { PuloonSettingsCodec.decode(malformed) }
        }
    }

    @Test
    fun incompleteAndOutOfRangeSettingsAreRejected() {
        assertFailsWith<IllegalArgumentException> { PuloonSettingsCodec.encode(emptySet()) }
        assertFailsWith<IllegalArgumentException> {
            PuloonSettingsCodec.encode(completeSettings().filterNot { it is GateSetting.ChildHeight }.toSet())
        }
        assertFailsWith<IllegalArgumentException> {
            PuloonSettingsCodec.encode(
                completeSettings().filterNot { it is GateSetting.HurryUpLevel }.toSet() + GateSetting.HurryUpLevel(3),
            )
        }
    }

    private fun baseStatus(): ByteArray =
        byteArrayOf(ascii('0')) + "0000".encodeToByteArray() +
            byteArrayOf(ascii('0'), ascii('0'), ascii('0'), 0x40, 0x80.toByte()) +
            "@@@00000000".encodeToByteArray() + byteArrayOf(ascii('0'), ascii('0'))

    private fun completeSettings(): Set<GateSetting> =
        setOf(
            GateSetting.SensorSensitivity(15),
            GateSetting.NormalOpenMode(false),
            GateSetting.HurryUpLevel(2),
            GateSetting.TagTimeoutFromLastTag(true),
            GateSetting.TailingSensitivity(1),
            GateSetting.PassageTimeout(100.seconds),
            GateSetting.ChildHeight(null),
            GateSetting.ChildDetection(false),
        )
}
