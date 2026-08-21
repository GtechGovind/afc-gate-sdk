package com.qurkos.gate.sdk.puloon

import com.qurkos.gate.sdk.GateHardwareProfile
import com.qurkos.gate.sdk.GateMechanism
import com.qurkos.gate.sdk.GateModule
import com.qurkos.gate.sdk.GatePassMode
import com.qurkos.gate.sdk.GateSetting
import com.qurkos.gate.sdk.GateSite
import com.qurkos.gate.sdk.internal.puloon.PuloonPayloadCodec
import com.qurkos.gate.sdk.internal.puloon.PuloonSettingsCodec
import com.qurkos.gate.sdk.internal.puloon.ascii
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PuloonPayloadValidationTest {
    @Test
    fun statusRejectsBadLengthCountersAndMode() {
        val short =
            assertFailsWith<IllegalArgumentException> {
                PuloonPayloadCodec.decodeStatus(ByteArray(22), GateHardwareProfile())
            }
        assertTrue(short.message.orEmpty().contains("length=22"))
        assertTrue(short.message.orEmpty().contains("payloadHex="))
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
    fun statusErrorsIdentifyTheExactFieldOffsetValueRangeAndPayload() {
        val badSwitch = baseStatus().also { it[11] = ascii('0') }

        val error =
            assertFailsWith<IllegalArgumentException> {
                PuloonPayloadCodec.decodeStatus(badSwitch, GateHardwareProfile())
            }

        val message = error.message.orEmpty()
        assertTrue(message.contains("switch status at payload offset 11"))
        assertTrue(message.contains("actual 0x30"))
        assertTrue(message.contains("expected 0x40..0x4F"))
        assertTrue(message.contains("payloadHex="))
    }

    @Test
    fun statusRequiresConfiguredSuffixesAndDecodesUpsAndTokenFields() {
        val hardware =
            GateHardwareProfile(
                site = GateSite.INDIA,
                modules = setOf(GateModule.UPS, GateModule.TOKEN_CONTROL_UNIT),
            )
        val status =
            baseStatus() +
                byteArrayOf(0x08, 0x01) +
                "99".encodeToByteArray() +
                "123401".encodeToByteArray()

        val decoded = PuloonPayloadCodec.decodeStatus(status, hardware)
        val power = assertNotNull(decoded.power)

        assertEquals(99, power.chargePercent)
        assertEquals(true, power.online)
        assertEquals(true, power.onBattery)
        assertEquals(12, decoded.tokenPathACount)
        assertEquals(34, decoded.tokenPathBCount)
        assertTrue(decoded.returnCupOccupied == true)
        assertFailsWith<IllegalArgumentException> {
            PuloonPayloadCodec.decodeStatus(baseStatus(), hardware)
        }
        val noModules = PuloonPayloadCodec.decodeStatus(baseStatus(), GateHardwareProfile())
        assertNull(noModules.power)
        assertFalse(noModules.sensors.hasFault)
    }

    @Test
    fun statusRejectsUndocumentedEmergencySensorAndReturnCupValues() {
        assertFailsWith<IllegalArgumentException> {
            PuloonPayloadCodec.decodeStatus(baseStatus().also { it[21] = ascii('4') }, GateHardwareProfile())
        }
        assertFailsWith<IllegalArgumentException> {
            PuloonPayloadCodec.decodeStatus(baseStatus().also { it[22] = ascii('3') }, GateHardwareProfile())
        }
        val hardware =
            GateHardwareProfile(
                site = GateSite.INDIA,
                modules = setOf(GateModule.TOKEN_CONTROL_UNIT),
            )
        assertFailsWith<IllegalArgumentException> {
            PuloonPayloadCodec.decodeStatus(baseStatus() + "000002".encodeToByteArray(), hardware)
        }
    }

    @Test
    fun statusDecodesEveryDocumentedOffsetPassModeByte() {
        GatePassMode.entries.forEachIndexed { index, expected ->
            val status = baseStatus().also { it[0] = (0x30 + index).toByte() }

            assertEquals(expected, PuloonPayloadCodec.decodeStatus(status, GateHardwareProfile()).passMode)
        }
    }

    @Test
    fun standbyDecodesOutOfServiceOffsetPassModeByte() {
        val standby = byteArrayOf(ascii('1'), ascii('1'), ascii('4'), ascii('3'), 0x3F)

        assertEquals(GatePassMode.OUT_OF_SERVICE, PuloonPayloadCodec.decodeStandby(standby).passMode)
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
        assertFailsWith<IllegalArgumentException> { PuloonPayloadCodec.decodeClock("0260101123456".encodeToByteArray()) }
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
        assertFailsWith<IllegalArgumentException> {
            PuloonPayloadCodec.decodeStandby(byteArrayOf(ascii('0'), ascii('1'), ascii('4'), ascii('3'), 0x3F))
        }
        assertFailsWith<IllegalArgumentException> {
            PuloonPayloadCodec.decodeDoorTiming(byteArrayOf(ascii('0'), ascii('0'), ascii('0'), ascii('0'), ascii('0')))
        }
    }

    @Test
    fun settingsAcceptDocumentedBoundariesAndRejectMalformedFields() {
        val boundaries =
            setOf(
                GateSetting.NoEntryTimeout(999.seconds),
                GateSetting.NormalOpenMode(true),
                GateSetting.HurryUpLevel(3),
                GateSetting.TagTimeoutFromLastTag(false),
                GateSetting.TailingSensitivity(0),
                GateSetting.BuzzerTimeoutUnits(254),
                GateSetting.SafetyRegionTimeout(null),
                GateSetting.ChildDetection(2),
            )
        assertEquals(boundaries, PuloonSettingsCodec.decode(PuloonSettingsCodec.encode(boundaries)))
        listOf(0, 3, 4, 5, 6, 7, 9, 11).forEach { offset ->
            val malformed = PuloonSettingsCodec.encode(boundaries).also { it[offset] = ascii('X') }
            assertFailsWith<IllegalArgumentException> { PuloonSettingsCodec.decode(malformed) }
        }
    }

    @Test
    fun incompleteAndOutOfRangeSettingsAreRejected() {
        assertFailsWith<IllegalArgumentException> { PuloonSettingsCodec.encode(emptySet()) }
        assertFailsWith<IllegalArgumentException> {
            PuloonSettingsCodec.encode(completeSettings().filterNot { it is GateSetting.ChildDetection }.toSet())
        }
        assertFailsWith<IllegalArgumentException> {
            PuloonSettingsCodec.encode(
                completeSettings().filterNot { it is GateSetting.HurryUpLevel }.toSet() + GateSetting.HurryUpLevel(4),
            )
        }
    }

    private fun baseStatus(): ByteArray =
        byteArrayOf(ascii('0')) + "0000".encodeToByteArray() +
            byteArrayOf(ascii('0'), ascii('0'), ascii('0'), 0x40, 0x80.toByte()) +
            "@@@00000000".encodeToByteArray() + byteArrayOf(ascii('0'), ascii('0'))

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
