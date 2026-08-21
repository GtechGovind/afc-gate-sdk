package com.qurkos.gate.sdk.puloon

import com.qurkos.gate.sdk.GateHardwareProfile
import com.qurkos.gate.sdk.GateMechanism
import com.qurkos.gate.sdk.GateModule
import com.qurkos.gate.sdk.GatePassMode
import com.qurkos.gate.sdk.GatePassageResult
import com.qurkos.gate.sdk.GateProtocolRevision
import com.qurkos.gate.sdk.GateSetting
import com.qurkos.gate.sdk.GateSite
import com.qurkos.gate.sdk.internal.puloon.PuloonOffsetHexCodec
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
    fun statusDecodesObservedSuffixesWithoutRequiringConfigurationParity() {
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
        val temporarilyMissingSuffixes = PuloonPayloadCodec.decodeStatus(baseStatus(), hardware)
        assertNull(temporarilyMissingSuffixes.power)
        assertNull(temporarilyMissingSuffixes.tokenPathACount)
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
                byteArrayOf(0x3F, 0x3F, 0x3D, 0x3F, 0x37, 0x3F) + "000000".encodeToByteArray(),
                GateHardwareProfile(mechanism = GateMechanism.SWING),
            )
        assertTrue(swing.active.any { it.number == 23 })
        assertTrue(swing.active.any { it.number == 24 })
        val child =
            PuloonPayloadCodec.decodeSensors(
                byteArrayOf(0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x37) + "000000".encodeToByteArray(),
                GateHardwareProfile(site = GateSite.CHINA, modules = setOf(GateModule.CHILD_SENSORS)),
            )
        assertTrue(child.active.any { it.number == 22 })
    }

    @Test
    fun sensorActivityIsActiveLowWhileFaultBitsAreActiveHigh() {
        val response = byteArrayOf(0x3E, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x31, 0x30, 0x30, 0x30, 0x30, 0x30)

        val decoded = PuloonPayloadCodec.decodeSensors(response, GateHardwareProfile())

        assertEquals(setOf(1), decoded.active.mapTo(mutableSetOf()) { it.number })
        assertEquals(setOf(1), decoded.faulted.mapTo(mutableSetOf()) { it.number })
    }

    @Test
    fun passageResultUsesTheSelectedProtocolRevision() {
        val legacy = GateHardwareProfile(protocolRevision = GateProtocolRevision.V2_5)
        val current = GateHardwareProfile(protocolRevision = GateProtocolRevision.V2_8)
        val resultTwo = baseStatus().also { it[5] = ascii('2') }

        assertEquals(GatePassageResult.NO_ENTRY_TIMEOUT, PuloonPayloadCodec.decodeStatus(resultTwo, legacy).passageResult)
        assertEquals(GatePassageResult.EXIT_COMPLETED, PuloonPayloadCodec.decodeStatus(resultTwo, current).passageResult)
        assertFailsWith<IllegalArgumentException> {
            PuloonPayloadCodec.decodeStatus(baseStatus().also { it[5] = ascii('7') }, legacy)
        }
    }

    @Test
    fun statusAcceptsEveryDocumentedAndDerivedSuffixLength() {
        val hardware = GateHardwareProfile(site = GateSite.INDIA)
        val variants =
            listOf(
                baseStatus(),
                baseStatus() + byteArrayOf(0x00, 0x00) + "FF".encodeToByteArray(),
                baseStatus() + "123401".encodeToByteArray(),
                baseStatus() + byteArrayOf(0x08, 0x01) + "99".encodeToByteArray() + "123401".encodeToByteArray(),
            )

        variants.forEach { PuloonPayloadCodec.decodeStatus(it, hardware) }
        assertFailsWith<IllegalArgumentException> { PuloonPayloadCodec.decodeStatus(baseStatus() + byteArrayOf(0), hardware) }
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
        assertFailsWith<IllegalArgumentException> { PuloonOffsetHexCodec.encode(-1) }
        assertFailsWith<IllegalArgumentException> { PuloonOffsetHexCodec.encode(256) }
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
        val encoded = PuloonSettingsCodec.encode(boundaries)
        assertEquals(0x3F, encoded[7].toInt() and 0xFF)
        assertEquals(0x3E, encoded[8].toInt() and 0xFF)
        assertEquals(0x3F, encoded[9].toInt() and 0xFF)
        assertEquals(0x3F, encoded[10].toInt() and 0xFF)
        assertEquals(boundaries, PuloonSettingsCodec.decode(encoded))
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
