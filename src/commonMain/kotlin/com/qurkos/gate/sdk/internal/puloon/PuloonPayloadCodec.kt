package com.qurkos.gate.sdk.internal.puloon

import com.qurkos.gate.sdk.GateClock
import com.qurkos.gate.sdk.GateDirection
import com.qurkos.gate.sdk.GateDoorTiming
import com.qurkos.gate.sdk.GateEmergencyState
import com.qurkos.gate.sdk.GateHardwareProfile
import com.qurkos.gate.sdk.GateLampColor
import com.qurkos.gate.sdk.GateMechanism
import com.qurkos.gate.sdk.GateModule
import com.qurkos.gate.sdk.GatePassMode
import com.qurkos.gate.sdk.GatePassageRequest
import com.qurkos.gate.sdk.GatePowerStatus
import com.qurkos.gate.sdk.GateSensorId
import com.qurkos.gate.sdk.GateSensorStatus
import com.qurkos.gate.sdk.GateSite
import com.qurkos.gate.sdk.GateStandbyPolicy
import com.qurkos.gate.sdk.GateStatus
import kotlinx.datetime.LocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Converts Puloon command payloads to and from vendor-neutral SDK models. */
internal object PuloonPayloadCodec {
    /** Encodes basic or India-profile passage authorization fields. */
    fun encodePassage(
        request: GatePassageRequest,
        hardware: GateHardwareProfile,
    ): ByteArray {
        val direction = if (request.direction == GateDirection.ENTRY) '0' else '1'
        if (!hardware.isIndia()) return byteArrayOf(ascii(direction))
        val color = request.lampColor.wireValue()
        val lamp = (if (request.invalidTicket) INVALID_TICKET_PREFIX else VALID_TICKET_PREFIX) + color
        val count = if (request.invalidTicket) 0 else request.passengerCount
        return byteArrayOf(
            ascii(direction),
            lamp.toByte(),
            (ascii('0') + count / DECIMAL_BASE).toByte(),
            (ascii('0') + count % DECIMAL_BASE).toByte(),
        )
    }

    /** Validates and normalizes the fixed GCU status block and optional UPS suffix. */
    fun decodeStatus(
        bytes: ByteArray,
        hardware: GateHardwareProfile,
    ): GateStatus {
        require(bytes.size >= STATUS_BASE_LENGTH) { "Puloon status payload is too short" }
        val emergency =
            when (bytes[EMERGENCY_OFFSET].toInt().toChar()) {
                '0' -> GateEmergencyState.INACTIVE
                '1' -> GateEmergencyState.LOCAL
                '2' -> GateEmergencyState.REMOTE
                else -> GateEmergencyState.UNKNOWN
            }
        val power =
            if (GateModule.UPS in hardware.modules && bytes.size >= STATUS_BASE_LENGTH + UPS_STATUS_LENGTH) {
                GatePowerStatus(
                    upsPresent = true,
                    summary = bytes.copyOfRange(STATUS_BASE_LENGTH, STATUS_BASE_LENGTH + UPS_STATUS_LENGTH).decodeToString(),
                )
            } else {
                null
            }
        return GateStatus(
            passMode = decodePassMode(bytes[0]),
            entryCount = decodeDecimal(bytes, 1, 2),
            exitCount = decodeDecimal(bytes, 3, 2),
            emergency = emergency,
            sensors = GateSensorStatus(emptySet(), bytes[SENSOR_FAULT_OFFSET] != ascii('0')),
            power = power,
            observedAt = Clock.System.now(),
        )
    }

    /** Decodes the sensor bitmap plus documented mechanism/site-specific derived sensors. */
    fun decodeSensors(
        bytes: ByteArray,
        hardware: GateHardwareProfile,
    ): GateSensorStatus {
        require(bytes.size >= SENSOR_TEXT_LENGTH) { "Puloon sensor payload is too short" }
        val active = mutableSetOf<GateSensorId>()
        bytes.take(SENSOR_TEXT_LENGTH).forEachIndexed { index, byte ->
            val nibble =
                byte.toInt().toChar().digitToIntOrNull(HEX_BASE)
                    ?: throw IllegalArgumentException("Invalid Puloon sensor value")
            repeat(BITS_PER_NIBBLE) { bit ->
                if (nibble and (1 shl bit) != 0) active += GateSensorId(index * BITS_PER_NIBBLE + bit + 1)
            }
        }
        val text = bytes.copyOfRange(0, SENSOR_TEXT_LENGTH).decodeToString()
        if (hardware.mechanism == GateMechanism.SWING && text.startsWith("00208")) {
            active += GateSensorId(23)
            active += GateSensorId(24)
        }
        val hasChinaChildSensors =
            hardware.site == GateSite.CHINA && GateModule.CHILD_SENSORS in hardware.modules
        if (hasChinaChildSensors && text.startsWith("000008")) {
            active += GateSensorId(22)
        }
        return GateSensorStatus(active.toSet(), hasFault = false)
    }

    /** Encodes a controller-local clock as exactly `yyMMddHHmmss`. */
    fun encodeClock(clock: GateClock): ByteArray =
        clock.dateTime.run {
            buildString(CLOCK_TEXT_LENGTH) {
                appendPadded(year % 100)
                appendPadded(month.ordinal + 1)
                appendPadded(day)
                appendPadded(hour)
                appendPadded(minute)
                appendPadded(second)
            }.encodeToByteArray()
        }

    /** Decodes a strict clock response, accepting only the optional documented selector prefix. */
    fun decodeClock(bytes: ByteArray): GateClock {
        val text = bytes.decodeToString()
        require(text.all(Char::isDigit)) { "Puloon clock payload must contain only decimal digits" }
        val value = if (text.length == CLOCK_TEXT_LENGTH + 1) text.drop(1) else text
        require(value.length == CLOCK_TEXT_LENGTH) { "Puloon clock payload must contain exactly 12 clock digits" }
        return GateClock(
            LocalDateTime(
                year = 2000 + value.substring(0, 2).toInt(),
                month = value.substring(2, 4).toInt(),
                day = value.substring(4, 6).toInt(),
                hour = value.substring(6, 8).toInt(),
                minute = value.substring(8, 10).toInt(),
                second = value.substring(10, 12).toInt(),
            ),
        )
    }

    /** Decodes the selected extension block for standby timeout and target mode. */
    fun decodeStandby(bytes: ByteArray): GateStandbyPolicy {
        require(bytes.size >= STANDBY_RESPONSE_LENGTH) { "Puloon standby payload is too short" }
        return GateStandbyPolicy(
            timeout = decodeOffsetHexByte(bytes[1], bytes[2]).seconds,
            passMode = decodePassMode(bytes[4]),
        )
    }

    /** Decodes the selected extension block for door opening/closing timing. */
    fun decodeDoorTiming(bytes: ByteArray): GateDoorTiming {
        require(bytes.size >= DOOR_TIMING_RESPONSE_LENGTH) { "Puloon door timing payload is too short" }
        return GateDoorTiming(
            openingDelay = (decodeOffsetHexByte(bytes[1], bytes[2]) * DELAY_STEP_MILLISECONDS).milliseconds,
            closingDelay = (decodeOffsetHexByte(bytes[3], bytes[4]) * DELAY_STEP_MILLISECONDS).milliseconds,
        )
    }

    /** Encodes the GCU's offset-hex byte representation (`'0' + nibble`). */
    fun encodeOffsetHexByte(value: Int): ByteArray {
        require(value in 0..MAX_UNSIGNED_BYTE)
        return byteArrayOf(
            (ascii('0') + value / HEX_BASE).toByte(),
            (ascii('0') + value % HEX_BASE).toByte(),
        )
    }

    /** Maps normalized lamp colors to their documented Puloon nibble. */
    private fun GateLampColor.wireValue(): Int =
        when (this) {
            GateLampColor.OFF -> 0
            GateLampColor.GREEN -> 1
            GateLampColor.BLUE -> 2
            GateLampColor.RED -> 4
            GateLampColor.YELLOW -> 5
        }

    /** Resolves one hexadecimal mode character to the ordinal-stable common mode. */
    private fun decodePassMode(byte: Byte): GatePassMode =
        PASS_MODES.getOrNull(byte.toInt().toChar().digitToIntOrNull(HEX_BASE) ?: -1)
            ?: throw IllegalArgumentException("Unknown Puloon pass mode")

    /** Strictly decodes a fixed-width decimal slice. */
    private fun decodeDecimal(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Int =
        bytes
            .copyOfRange(offset, offset + length)
            .decodeToString()
            .toIntOrNull()
            ?: throw IllegalArgumentException("Invalid Puloon decimal value")

    /** Strictly decodes two offset-hex nibbles. */
    private fun decodeOffsetHexByte(
        high: Byte,
        low: Byte,
    ): Int {
        val highNibble = high - ascii('0')
        val lowNibble = low - ascii('0')
        require(highNibble in 0 until HEX_BASE && lowNibble in 0 until HEX_BASE) {
            "Invalid Puloon offset-hexadecimal value"
        }
        return highNibble * HEX_BASE + lowNibble
    }

    /** Returns whether India-specific payload fields are enabled. */
    private fun GateHardwareProfile.isIndia(): Boolean = site == GateSite.INDIA || site == GateSite.KOLKATA_INDIA

    /** Appends a two-character zero-padded decimal component. */
    private fun StringBuilder.appendPadded(value: Int) {
        append(value.toString().padStart(2, '0'))
    }

    private val PASS_MODES = GatePassMode.entries
    private const val VALID_TICKET_PREFIX = 0x30
    private const val INVALID_TICKET_PREFIX = 0x40
    private const val STATUS_BASE_LENGTH = 23
    private const val EMERGENCY_OFFSET = 21
    private const val SENSOR_FAULT_OFFSET = 22
    private const val UPS_STATUS_LENGTH = 4
    private const val SENSOR_TEXT_LENGTH = 12
    private const val CLOCK_TEXT_LENGTH = 12
    private const val STANDBY_RESPONSE_LENGTH = 5
    private const val DOOR_TIMING_RESPONSE_LENGTH = 5
    private const val DELAY_STEP_MILLISECONDS = 100
    private const val DECIMAL_BASE = 10
    private const val HEX_BASE = 16
    private const val BITS_PER_NIBBLE = 4
    private const val MAX_UNSIGNED_BYTE = 0xFF
}
