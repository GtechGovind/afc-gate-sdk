package com.qurkos.gate.sdk.internal.puloon

import com.qurkos.gate.sdk.GateClock
import com.qurkos.gate.sdk.GateDirection
import com.qurkos.gate.sdk.GateDoorFault
import com.qurkos.gate.sdk.GateDoorTiming
import com.qurkos.gate.sdk.GateEmergencyState
import com.qurkos.gate.sdk.GateHardwareProfile
import com.qurkos.gate.sdk.GateLampColor
import com.qurkos.gate.sdk.GateMechanism
import com.qurkos.gate.sdk.GateModule
import com.qurkos.gate.sdk.GateOccupancyZone
import com.qurkos.gate.sdk.GatePassMode
import com.qurkos.gate.sdk.GatePassageError
import com.qurkos.gate.sdk.GatePassageRequest
import com.qurkos.gate.sdk.GatePassageResult
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
    @Suppress("CyclomaticComplexMethod") // A fixed status record is decoded atomically before publication.
    fun decodeStatus(
        bytes: ByteArray,
        hardware: GateHardwareProfile,
    ): GateStatus {
        require(bytes.size >= STATUS_BASE_LENGTH) { "Puloon status payload is too short" }
        val emergency =
            when (bytes[EMERGENCY_OFFSET].toInt().toChar()) {
                '0' -> GateEmergencyState.INACTIVE
                '1' -> GateEmergencyState.ECU
                '2' -> GateEmergencyState.TWENTY_FOUR_VOLT
                '3' -> GateEmergencyState.BOTH
                else -> GateEmergencyState.UNKNOWN
            }
        val power =
            if (GateModule.UPS in hardware.modules && bytes.size >= STATUS_BASE_LENGTH + UPS_STATUS_LENGTH) {
                val chargeText = bytes.copyOfRange(STATUS_BASE_LENGTH + 2, STATUS_BASE_LENGTH + 4).decodeToString()
                val charge = if (chargeText == "FF") null else chargeText.toIntOrNull()?.also { require(it in 0..99) }
                GatePowerStatus(
                    upsPresent = true,
                    online = bytes[STATUS_BASE_LENGTH].toInt() and UPS_ONLINE_MASK != 0,
                    onBattery = bytes[STATUS_BASE_LENGTH + 1].toInt() and UPS_BATTERY_MASK != 0,
                    chargePercent = charge,
                    summary = charge?.let { "$it%" } ?: "Unknown charge",
                )
            } else {
                null
            }
        val tokenOffset = STATUS_BASE_LENGTH + if (power == null) 0 else UPS_STATUS_LENGTH
        val hasTokenData = GateModule.TOKEN_CONTROL_UNIT in hardware.modules && bytes.size >= tokenOffset + TOKEN_STATUS_LENGTH
        return GateStatus(
            passMode = decodePassMode(bytes[0]),
            entryCount = decodeDecimal(bytes, 1, 2),
            exitCount = decodeDecimal(bytes, 3, 2),
            emergency = emergency,
            sensors = GateSensorStatus(emptySet(), bytes[SENSOR_FAULT_OFFSET] != ascii('0')),
            power = power,
            passageResult = decodePassageResult(bytes[5]),
            entryError = decodePassageError(bytes[6]),
            exitError = decodePassageError(bytes[7]),
            doorFaults = decodeDoorFaults(bytes[8]),
            occupiedZones = decodeOccupancy(bytes[9]),
            switches = decodeBitFields(bytes, offset = 10, byteCount = 3, base = 0x40),
            inputs = decodeBitFields(bytes, offset = 13, byteCount = 8, base = 0x30),
            tokenPathACount = if (hasTokenData) decodeDecimal(bytes, tokenOffset, 2) else null,
            tokenPathBCount = if (hasTokenData) decodeDecimal(bytes, tokenOffset + 2, 2) else null,
            returnCupOccupied = if (hasTokenData) decodeDecimal(bytes, tokenOffset + 4, 2) == 1 else null,
            observedAt = Clock.System.now(),
        )
    }

    /** Decodes the sensor bitmap plus documented mechanism/site-specific derived sensors. */
    fun decodeSensors(
        bytes: ByteArray,
        hardware: GateHardwareProfile,
    ): GateSensorStatus {
        require(bytes.size == SENSOR_TEXT_LENGTH) { "Puloon sensor payload must contain exactly $SENSOR_TEXT_LENGTH bytes" }
        val active = mutableSetOf<GateSensorId>()
        val faulted = mutableSetOf<GateSensorId>()
        bytes.take(SENSOR_BITMAP_LENGTH).forEachIndexed { index, byte ->
            val nibble = decodeOffsetNibble(byte, 0x30, "sensor")
            val childSensorsDisabled =
                index == 5 && hardware.site == GateSite.CHINA && byte == 0x3F.toByte()
            if (!childSensorsDisabled) {
                repeat(BITS_PER_NIBBLE) { bit ->
                    if (nibble and (1 shl bit) != 0) sensorId(index, bit, hardware)?.let(active::add)
                }
            }
        }
        bytes.copyOfRange(SENSOR_BITMAP_LENGTH, SENSOR_TEXT_LENGTH).forEachIndexed { index, byte ->
            val nibble = decodeOffsetNibble(byte, 0x30, "sensor error")
            repeat(BITS_PER_NIBBLE) { bit ->
                if (nibble and (1 shl bit) != 0) sensorId(index, bit, hardware)?.let(faulted::add)
            }
        }
        return GateSensorStatus(active.toSet(), hasFault = faulted.isNotEmpty(), faulted = faulted.toSet())
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

    /** Maps one status/error bitmap position to its profile-specific physical or optional sensor. */
    @Suppress("CyclomaticComplexMethod") // Physical bit positions vary by documented mechanism and module profile.
    private fun sensorId(
        byteIndex: Int,
        bit: Int,
        hardware: GateHardwareProfile,
    ): GateSensorId? {
        val number =
            when (byteIndex) {
                0 -> bit + 1
                1 -> bit + 5
                2 ->
                    when (bit) {
                        0 -> 9
                        1 ->
                            when {
                                hardware.mechanism == GateMechanism.SWING -> 23
                                GateModule.TOKEN_CONTROL_UNIT in hardware.modules -> TOKEN_PATH_A_SENSOR_ID
                                else -> null
                            }
                        2 -> 11
                        else -> 12
                    }
                3 -> bit + 13
                4 ->
                    when (bit) {
                        0 -> 17
                        1 -> 18
                        2 -> 19
                        else ->
                            when {
                                hardware.mechanism == GateMechanism.SWING -> 24
                                GateModule.TOKEN_CONTROL_UNIT in hardware.modules -> TOKEN_PATH_B_SENSOR_ID
                                else -> null
                            }
                    }
                5 -> sixthByteSensor(bit, hardware)
                else -> null
            }
        return number?.let(::GateSensorId)
    }

    private fun sixthByteSensor(
        bit: Int,
        hardware: GateHardwareProfile,
    ): Int? =
        when {
            hardware.site == GateSite.CHINA && GateModule.CHILD_SENSORS in hardware.modules ->
                intArrayOf(10, 20, 21, 22)[bit]
            hardware.isIndia() && GateModule.TOKEN_CONTROL_UNIT in hardware.modules ->
                when (bit) {
                    2 -> 21
                    3 -> 22
                    else -> null
                }
            else -> null
        }

    private fun decodePassageResult(byte: Byte): GatePassageResult =
        when (byte.toInt().toChar()) {
            '0' -> GatePassageResult.IDLE
            '1' -> GatePassageResult.ENTRY_COMPLETED
            '2' -> GatePassageResult.EXIT_COMPLETED
            '3' -> GatePassageResult.ENTRY_TIMEOUT
            '4' -> GatePassageResult.EXIT_TIMEOUT
            '5' -> GatePassageResult.ENTRY_PRE_DOOR_TIMEOUT
            '6' -> GatePassageResult.EXIT_PRE_DOOR_TIMEOUT
            '7' -> GatePassageResult.ENTRY_TAILGATE_USED
            '8' -> GatePassageResult.EXIT_TAILGATE_USED
            '9' -> GatePassageResult.ENTRY_WRONG_WAY_USED
            '@' -> GatePassageResult.EXIT_WRONG_WAY_USED
            else -> GatePassageResult.UNKNOWN
        }

    private fun decodePassageError(byte: Byte): GatePassageError =
        when (byte.toInt().toChar()) {
            '0' -> GatePassageError.NORMAL
            '3' -> GatePassageError.PIGGY_TAILING
            '5' -> GatePassageError.INTRUSION
            '9' -> GatePassageError.WRONG_WAY_FRAUD
            else -> GatePassageError.UNKNOWN
        }

    private fun decodeDoorFaults(byte: Byte): Set<GateDoorFault> {
        val mask = decodeOffsetNibble(byte, 0x40, "door error")
        return buildSet {
            if (mask and 0x01 != 0) add(GateDoorFault.DOOR_1_OPEN)
            if (mask and 0x02 != 0) add(GateDoorFault.DOOR_1_CLOSE)
            if (mask and 0x04 != 0) add(GateDoorFault.DOOR_2_OPEN)
            if (mask and 0x08 != 0) add(GateDoorFault.DOOR_2_CLOSE)
        }
    }

    private fun decodeOccupancy(byte: Byte): Set<GateOccupancyZone> {
        val mask = (byte.toInt() and MAX_UNSIGNED_BYTE) - 0x80
        require(mask in 0..0x7F) { "Invalid Puloon gate inner-state value" }
        return GateOccupancyZone.entries.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
    }

    private fun decodeBitFields(
        bytes: ByteArray,
        offset: Int,
        byteCount: Int,
        base: Int,
    ): Map<Int, Boolean> =
        buildMap {
            repeat(byteCount) { byteIndex ->
                val mask = decodeOffsetNibble(bytes[offset + byteIndex], base, "status bitmap")
                repeat(BITS_PER_NIBBLE) { bit ->
                    put(byteIndex * BITS_PER_NIBBLE + bit + 1, mask and (1 shl bit) != 0)
                }
            }
        }

    private fun decodeOffsetNibble(
        byte: Byte,
        base: Int,
        name: String,
    ): Int {
        val value = byte.toInt() and MAX_UNSIGNED_BYTE
        val decoded = value - base
        require(decoded in 0 until HEX_BASE) { "Invalid Puloon $name value" }
        return decoded
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
    private const val TOKEN_STATUS_LENGTH = 6
    private const val SENSOR_TEXT_LENGTH = 12
    private const val SENSOR_BITMAP_LENGTH = 6
    private const val CLOCK_TEXT_LENGTH = 12
    private const val STANDBY_RESPONSE_LENGTH = 5
    private const val DOOR_TIMING_RESPONSE_LENGTH = 5
    private const val DELAY_STEP_MILLISECONDS = 100
    private const val DECIMAL_BASE = 10
    private const val HEX_BASE = 16
    private const val BITS_PER_NIBBLE = 4
    private const val MAX_UNSIGNED_BYTE = 0xFF
    private const val UPS_ONLINE_MASK = 0x08
    private const val UPS_BATTERY_MASK = 0x01
    private const val TOKEN_PATH_A_SENSOR_ID = 25
    private const val TOKEN_PATH_B_SENSOR_ID = 26
}
