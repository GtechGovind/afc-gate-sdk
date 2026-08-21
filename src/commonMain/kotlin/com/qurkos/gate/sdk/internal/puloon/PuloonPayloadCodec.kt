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
import com.qurkos.gate.sdk.GateProtocolRevision
import com.qurkos.gate.sdk.GateSensorFaultCategory
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
    ): GateStatus =
        try {
            decodeStatusPayload(bytes, hardware.protocolRevision)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException(
                "${error.message ?: "Invalid Puloon status payload"}; " +
                    "length=${bytes.size}; payloadHex=${bytes.toHexString()}",
                error,
            )
        }

    @Suppress("CyclomaticComplexMethod") // A fixed status record is decoded atomically before publication.
    private fun decodeStatusPayload(
        bytes: ByteArray,
        revision: GateProtocolRevision,
    ): GateStatus {
        val supportedLengths =
            when (revision) {
                GateProtocolRevision.V2_5 -> V25_STATUS_LENGTHS
                GateProtocolRevision.V2_8 -> V28_STATUS_LENGTHS
            }
        require(bytes.size in supportedLengths) {
            "Puloon status payload length ${bytes.size} is unsupported for $revision; " +
                "expected one of ${supportedLengths.joinToString()}"
        }
        val hasUpsData = bytes.size == STATUS_WITH_UPS_LENGTH || bytes.size == STATUS_WITH_UPS_AND_TOKEN_LENGTH
        val hasTokenData = bytes.size == STATUS_WITH_TOKEN_LENGTH || bytes.size == STATUS_WITH_UPS_AND_TOKEN_LENGTH
        val emergency = decodeEmergency(bytes[EMERGENCY_OFFSET])
        val sensorFaultCategory =
            when (bytes[SENSOR_FAULT_OFFSET].toInt().toChar()) {
                '0' -> null
                '1' -> GateSensorFaultCategory.GENERAL
                '2' -> GateSensorFaultCategory.CHILD
                else -> throw invalidStatusField("sensor error", SENSOR_FAULT_OFFSET, bytes[SENSOR_FAULT_OFFSET], "0x30..0x32")
            }
        val power =
            if (hasUpsData) {
                val onlineByte = bytes[STATUS_BASE_LENGTH].toInt() and MAX_UNSIGNED_BYTE
                val batteryByte = bytes[STATUS_BASE_LENGTH + 1].toInt() and MAX_UNSIGNED_BYTE
                require(onlineByte == 0 || onlineByte == UPS_ONLINE_MASK) {
                    invalidStatusFieldMessage("UPS online", STATUS_BASE_LENGTH, bytes[STATUS_BASE_LENGTH], "0x00 or 0x08")
                }
                require(batteryByte == 0 || batteryByte == UPS_BATTERY_MASK) {
                    invalidStatusFieldMessage("UPS battery", STATUS_BASE_LENGTH + 1, bytes[STATUS_BASE_LENGTH + 1], "0x00 or 0x01")
                }
                val chargeText = bytes.copyOfRange(STATUS_BASE_LENGTH + 2, STATUS_BASE_LENGTH + 4).decodeToString()
                val charge =
                    if (chargeText == "FF") {
                        null
                    } else {
                        chargeText.toIntOrNull()?.also { require(it in 0..99) { "Invalid Puloon UPS charge at offsets 25..26" } }
                            ?: throw IllegalArgumentException("Invalid Puloon UPS charge at offsets 25..26")
                    }
                GatePowerStatus(
                    upsPresent = true,
                    online = onlineByte == UPS_ONLINE_MASK,
                    onBattery = batteryByte == UPS_BATTERY_MASK,
                    chargePercent = charge,
                    summary = charge?.let { "$it%" } ?: "Unknown charge",
                )
            } else {
                null
            }
        val tokenOffset = STATUS_BASE_LENGTH + if (hasUpsData) UPS_STATUS_LENGTH else 0
        val returnCupValue = if (hasTokenData) decodeDecimal(bytes, tokenOffset + 4, 2, "return-cup state") else null
        require(returnCupValue == null || returnCupValue in 0..1) {
            "Puloon return-cup state at offsets ${tokenOffset + 4}..${tokenOffset + 5} must be 00 or 01"
        }
        return GateStatus(
            passMode = decodePassMode(bytes[0]),
            entryCount = decodeDecimal(bytes, 1, 2, "entry count"),
            exitCount = decodeDecimal(bytes, 3, 2, "exit count"),
            emergency = emergency,
            sensors =
                GateSensorStatus(
                    active = emptySet(),
                    hasFault = sensorFaultCategory != null,
                    faultCategory = sensorFaultCategory,
                ),
            power = power,
            passageResult = decodePassageResult(bytes[5], revision),
            entryError = decodePassageError(bytes[6]),
            exitError = decodePassageError(bytes[7]),
            doorFaults = decodeDoorFaults(bytes[8]),
            occupiedZones = decodeOccupancy(bytes[9]),
            switches = decodeBitFields(bytes, offset = 10, byteCount = 3, base = 0x40, name = "switch status"),
            inputs = decodeBitFields(bytes, offset = 13, byteCount = 8, base = 0x30, name = "input status"),
            tokenPathACount = if (hasTokenData) decodeDecimal(bytes, tokenOffset, 2, "token path A count") else null,
            tokenPathBCount = if (hasTokenData) decodeDecimal(bytes, tokenOffset + 2, 2, "token path B count") else null,
            returnCupSignalActive = returnCupValue?.let { it == 1 },
            returnCupOccupied = null,
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
        var anyFaultBit = false
        bytes.take(SENSOR_BITMAP_LENGTH).forEachIndexed { index, byte ->
            val nibble = decodeOffsetNibble(byte, 0x30, "sensor", index)
            val childSensorsDisabled =
                index == 5 && hardware.site == GateSite.CHINA && byte == 0x3F.toByte()
            if (!childSensorsDisabled) {
                repeat(BITS_PER_NIBBLE) { bit ->
                    if (nibble and (1 shl bit) == 0) sensorId(index, bit, hardware)?.let(active::add)
                }
            }
        }
        bytes.copyOfRange(SENSOR_BITMAP_LENGTH, SENSOR_TEXT_LENGTH).forEachIndexed { index, byte ->
            val nibble = decodeOffsetNibble(byte, 0x30, "sensor error", SENSOR_BITMAP_LENGTH + index)
            anyFaultBit = anyFaultBit || nibble != 0
            repeat(BITS_PER_NIBBLE) { bit ->
                if (nibble and (1 shl bit) != 0) sensorId(index, bit, hardware)?.let(faulted::add)
            }
        }
        return GateSensorStatus(active.toSet(), hasFault = anyFaultBit, faulted = faulted.toSet())
    }

    /** Encodes a controller-local clock as exactly `yyMMddHHmmss`. */
    fun encodeClock(clock: GateClock): ByteArray =
        clock.dateTime.run {
            require(year in 2000..2099) { "Puloon clock year must be between 2000 and 2099" }
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
        if (text.length == CLOCK_TEXT_LENGTH + 1) {
            require(text.first() == '1') { "Puloon clock read response must use selector 1" }
        }
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
        require(bytes.size == STANDBY_RESPONSE_LENGTH) {
            "Puloon standby payload must contain exactly $STANDBY_RESPONSE_LENGTH bytes"
        }
        require(bytes[0] == ascii('1') && bytes[3] == ascii('3')) {
            "Puloon standby read response contains invalid selectors"
        }
        return GateStandbyPolicy(
            timeout = PuloonOffsetHexCodec.decode(bytes, 1, "standby timeout").seconds,
            passMode = decodePassMode(bytes[4]),
        )
    }

    /** Decodes the selected extension block for door opening/closing timing. */
    fun decodeDoorTiming(bytes: ByteArray): GateDoorTiming {
        require(bytes.size == DOOR_TIMING_RESPONSE_LENGTH) {
            "Puloon door timing payload must contain exactly $DOOR_TIMING_RESPONSE_LENGTH bytes"
        }
        require(bytes[0] == ascii('1')) { "Puloon door timing read response must use selector 1" }
        val opening = PuloonOffsetHexCodec.decode(bytes, 1, "opening delay")
        val closing = PuloonOffsetHexCodec.decode(bytes, 3, "closing delay")
        require(opening in 0..MAX_DOOR_DELAY_UNITS && closing in 0..MAX_DOOR_DELAY_UNITS) {
            "Puloon door timing values must be between 0 and $MAX_DOOR_DELAY_UNITS units"
        }
        return GateDoorTiming(
            openingDelay = (opening * DELAY_STEP_MILLISECONDS).milliseconds,
            closingDelay = (closing * DELAY_STEP_MILLISECONDS).milliseconds,
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
                                hardware.hasV28TokenControlUnit() -> TOKEN_PATH_A_SENSOR_ID
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
                                hardware.hasV28TokenControlUnit() -> TOKEN_PATH_B_SENSOR_ID
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
            hardware.hasV28TokenControlUnit() ->
                when (bit) {
                    2 -> 21
                    3 -> 22
                    else -> null
                }
            else -> null
        }

    private fun GateHardwareProfile.hasV28TokenControlUnit(): Boolean =
        protocolRevision == GateProtocolRevision.V2_8 &&
            mechanism == GateMechanism.SECTOR &&
            isIndia() &&
            GateModule.TOKEN_CONTROL_UNIT in modules

    private fun decodePassageResult(
        byte: Byte,
        revision: GateProtocolRevision,
    ): GatePassageResult =
        when (revision) {
            GateProtocolRevision.V2_5 -> decodeLegacyPassageResult(byte)
            GateProtocolRevision.V2_8 -> decodeCurrentPassageResult(byte)
        }

    private fun decodeLegacyPassageResult(byte: Byte): GatePassageResult =
        when (byte.toInt().toChar()) {
            '0' -> GatePassageResult.IDLE
            '1' -> GatePassageResult.PASSAGE_COMPLETED
            '2' -> GatePassageResult.NO_ENTRY_TIMEOUT
            '3' -> GatePassageResult.PASSING_TIMEOUT
            '4' -> GatePassageResult.EXIT_TIMEOUT
            '5' -> GatePassageResult.TAILING_USED
            '6' -> GatePassageResult.WRONG_WAY_USED
            else -> throw invalidStatusField("V2.5 passage result", 5, byte, "0x30..0x36")
        }

    private fun decodeCurrentPassageResult(byte: Byte): GatePassageResult =
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
            else -> throw invalidStatusField("passage result", 5, byte, "0x30..0x39 or 0x40")
        }

    private fun decodePassageError(byte: Byte): GatePassageError =
        when (byte.toInt().toChar()) {
            '0' -> GatePassageError.NORMAL
            '3' -> GatePassageError.PIGGY_TAILING
            '5' -> GatePassageError.INTRUSION
            '9' -> GatePassageError.WRONG_WAY_FRAUD
            else -> throw IllegalArgumentException(
                "Invalid Puloon passage error value ${byte.hexByte()}; expected 0x30, 0x33, 0x35, or 0x39",
            )
        }

    private fun decodeDoorFaults(byte: Byte): Set<GateDoorFault> {
        val mask = decodeOffsetNibble(byte, 0x40, "door error", 8)
        return buildSet {
            if (mask and 0x01 != 0) add(GateDoorFault.DOOR_1_OPEN)
            if (mask and 0x02 != 0) add(GateDoorFault.DOOR_1_CLOSE)
            if (mask and 0x04 != 0) add(GateDoorFault.DOOR_2_OPEN)
            if (mask and 0x08 != 0) add(GateDoorFault.DOOR_2_CLOSE)
        }
    }

    private fun decodeOccupancy(byte: Byte): Set<GateOccupancyZone> {
        val mask = (byte.toInt() and MAX_UNSIGNED_BYTE) - 0x80
        require(mask in 0..0x7F) {
            invalidStatusFieldMessage("gate inner state", 9, byte, "0x80..0xFF")
        }
        return GateOccupancyZone.entries.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
    }

    private fun decodeBitFields(
        bytes: ByteArray,
        offset: Int,
        byteCount: Int,
        base: Int,
        name: String,
    ): Map<Int, Boolean> =
        buildMap {
            repeat(byteCount) { byteIndex ->
                val absoluteOffset = offset + byteIndex
                val mask = decodeOffsetNibble(bytes[absoluteOffset], base, name, absoluteOffset)
                repeat(BITS_PER_NIBBLE) { bit ->
                    put(byteIndex * BITS_PER_NIBBLE + bit + 1, mask and (1 shl bit) != 0)
                }
            }
        }

    private fun decodeOffsetNibble(
        byte: Byte,
        base: Int,
        name: String,
        offset: Int? = null,
    ): Int {
        val value = byte.toInt() and MAX_UNSIGNED_BYTE
        val decoded = value - base
        require(decoded in 0 until HEX_BASE) {
            val location = offset?.let { " at payload offset $it" }.orEmpty()
            "Invalid Puloon $name$location: actual ${byte.hexByte()}, expected ${base.hexInt()}..${(base + 0x0F).hexInt()}"
        }
        return decoded
    }

    private fun decodeEmergency(byte: Byte): GateEmergencyState =
        when (byte.toInt().toChar()) {
            '0' -> GateEmergencyState.INACTIVE
            '1' -> GateEmergencyState.ECU
            '2' -> GateEmergencyState.TWENTY_FOUR_VOLT
            '3' -> GateEmergencyState.BOTH
            else -> throw invalidStatusField("emergency state", EMERGENCY_OFFSET, byte, "0x30..0x33")
        }

    /** Resolves the documented offset mode byte (`0x30..0x3F`) to the common mode. */
    private fun decodePassMode(byte: Byte): GatePassMode =
        PASS_MODES.getOrNull((byte.toInt() and MAX_UNSIGNED_BYTE) - PASS_MODE_BASE)
            ?: throw invalidStatusField("pass mode", 0, byte, "0x30..0x3F")

    /** Strictly decodes a fixed-width decimal slice. */
    private fun decodeDecimal(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        name: String = "decimal value",
    ): Int =
        bytes
            .copyOfRange(offset, offset + length)
            .decodeToString()
            .toIntOrNull()
            ?: throw IllegalArgumentException(
                "Invalid Puloon $name at payload offsets $offset..${offset + length - 1}",
            )

    /** Returns whether India-specific payload fields are enabled. */
    private fun GateHardwareProfile.isIndia(): Boolean = site == GateSite.INDIA || site == GateSite.KOLKATA_INDIA

    private fun invalidStatusField(
        name: String,
        offset: Int,
        actual: Byte,
        expected: String,
    ): IllegalArgumentException = IllegalArgumentException(invalidStatusFieldMessage(name, offset, actual, expected))

    private fun invalidStatusFieldMessage(
        name: String,
        offset: Int,
        actual: Byte,
        expected: String,
    ): String = "Invalid Puloon $name at payload offset $offset: actual ${actual.hexByte()}, expected $expected"

    private fun ByteArray.toHexString(): String = joinToString(" ") { it.hexByte() }

    private fun Byte.hexByte(): String = (toInt() and MAX_UNSIGNED_BYTE).hexInt()

    private fun Int.hexInt(): String = "0x${toString(HEX_BASE).uppercase().padStart(2, '0')}"

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
    private const val STATUS_WITH_UPS_LENGTH = STATUS_BASE_LENGTH + UPS_STATUS_LENGTH
    private const val STATUS_WITH_TOKEN_LENGTH = STATUS_BASE_LENGTH + TOKEN_STATUS_LENGTH
    private const val STATUS_WITH_UPS_AND_TOKEN_LENGTH = STATUS_WITH_UPS_LENGTH + TOKEN_STATUS_LENGTH
    private const val SENSOR_TEXT_LENGTH = 12
    private const val SENSOR_BITMAP_LENGTH = 6
    private const val CLOCK_TEXT_LENGTH = 12
    private const val STANDBY_RESPONSE_LENGTH = 5
    private const val DOOR_TIMING_RESPONSE_LENGTH = 5
    private const val DELAY_STEP_MILLISECONDS = 100
    private const val MAX_DOOR_DELAY_UNITS = 10
    private const val DECIMAL_BASE = 10
    private const val HEX_BASE = 16
    private const val BITS_PER_NIBBLE = 4
    private const val MAX_UNSIGNED_BYTE = 0xFF
    private const val PASS_MODE_BASE = 0x30
    private const val UPS_ONLINE_MASK = 0x08
    private const val UPS_BATTERY_MASK = 0x01
    private const val TOKEN_PATH_A_SENSOR_ID = 23
    private const val TOKEN_PATH_B_SENSOR_ID = 24
    private val V25_STATUS_LENGTHS = setOf(STATUS_BASE_LENGTH, STATUS_WITH_UPS_LENGTH)
    private val V28_STATUS_LENGTHS =
        setOf(STATUS_BASE_LENGTH, STATUS_WITH_UPS_LENGTH, STATUS_WITH_TOKEN_LENGTH, STATUS_WITH_UPS_AND_TOKEN_LENGTH)
}
