package com.qurkos.gate.sdk.internal.puloon

import com.qurkos.gate.sdk.GateCapability
import com.qurkos.gate.sdk.GateDescriptor
import com.qurkos.gate.sdk.GateDiagnostic
import com.qurkos.gate.sdk.GateDoorTiming
import com.qurkos.gate.sdk.GateError
import com.qurkos.gate.sdk.GateFirmwareInfo
import com.qurkos.gate.sdk.GateHardwareProfile
import com.qurkos.gate.sdk.GateMechanism
import com.qurkos.gate.sdk.GateModule
import com.qurkos.gate.sdk.GatePassMode
import com.qurkos.gate.sdk.GateResult
import com.qurkos.gate.sdk.GateSite
import com.qurkos.gate.sdk.GateStandbyPolicy
import com.qurkos.gate.sdk.GateVendor
import com.qurkos.gate.sdk.SerialParameters
import com.qurkos.gate.sdk.internal.GateOperation
import com.qurkos.gate.sdk.internal.GateProtocolAdapter
import com.qurkos.gate.sdk.internal.GateResponse
import com.qurkos.gate.sdk.internal.SerialTransaction
import com.qurkos.gate.sdk.internal.StreamingFrameDecoder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Puloon GCU implementation of the internal protocol-adapter contract.
 *
 * Capabilities are derived from the configured site, mechanism, installed modules, and explicit maintenance opt-in. The
 * adapter allocates sequences and creates immutable transactions but never opens a serial port.
 */
internal class PuloonAdapter(
    private val hardware: GateHardwareProfile,
    maintenanceOperationsEnabled: Boolean,
) : GateProtocolAdapter {
    private var sequence = 0

    override val descriptor = GateDescriptor(GateVendor.PULOON, hardware.mechanism, hardware.site)
    override val defaultSerialParameters = SerialParameters(baudRate = 57_600)
    override val minimumPollInterval: Duration = 101.milliseconds
    override val capabilities: Set<GateCapability> = buildCapabilities(maintenanceOperationsEnabled)

    /** Creates independent streaming state for this adapter's serial session. */
    override fun newDecoder(): StreamingFrameDecoder = PuloonFrameDecoder()

    /** Validates [operation] and translates it into a Puloon command transaction. */
    override fun transaction(operation: GateOperation): GateResult<SerialTransaction> =
        try {
            GateResult.Success(createTransaction(operation))
        } catch (error: IllegalArgumentException) {
            GateResult.Failure(GateError.InvalidRequest(error.message ?: "Invalid Puloon operation"))
        }

    /** Performs exhaustive vendor command and payload mapping. */
    private fun createTransaction(operation: GateOperation): PuloonTransaction =
        when (operation) {
            GateOperation.Firmware ->
                read('V') { data ->
                    val raw = data.decodeToString()
                    require(VERSION_PATTERN.matches(raw)) { "Invalid Puloon firmware version" }
                    GateResponse.Firmware(GateFirmwareInfo(version = raw))
                }
            is GateOperation.Passage ->
                write(
                    'A',
                    PuloonPayloadCodec.encodePassage(operation.request, hardware),
                )
            is GateOperation.Emergency -> write('E', byteArrayOf(ascii(if (operation.enabled) '1' else '0')))
            GateOperation.Initialize -> write('I')
            GateOperation.Status ->
                read('S') { data ->
                    GateResponse.Status(PuloonPayloadCodec.decodeStatus(data, hardware))
                }
            is GateOperation.SetPassMode -> write('D', byteArrayOf(ascii(operation.mode.wireValue())))
            is GateOperation.SetSafetyRegion -> {
                validateSafetyRegion(operation.region.number)
                write('G', byteArrayOf((ascii('0') + operation.region.number).toByte()))
            }
            GateOperation.ClearPassageCounters -> write('C')
            GateOperation.Sensors ->
                read('H') { data ->
                    GateResponse.Sensors(PuloonPayloadCodec.decodeSensors(data, hardware))
                }
            GateOperation.ReadClock ->
                read('X', byteArrayOf(ascii('1')), setOf('X', 'P')) { data ->
                    GateResponse.Clock(PuloonPayloadCodec.decodeClock(data))
                }
            is GateOperation.SetClock ->
                write(
                    'X',
                    byteArrayOf(ascii('0')) + PuloonPayloadCodec.encodeClock(operation.clock),
                )
            is GateOperation.SetUpsShutdownDelay -> {
                require(operation.seconds in 0..MAX_UPS_SECONDS && operation.seconds % UPS_STEP_SECONDS == 0) {
                    "UPS shutdown delay must be 0..$MAX_UPS_SECONDS seconds in $UPS_STEP_SECONDS-second steps"
                }
                write('Y', encodeAsciiHex(operation.seconds / UPS_STEP_SECONDS))
            }
            GateOperation.ReadStandbyPolicy ->
                read('U', byteArrayOf(ascii('1')) + STANDBY_SELECTOR) { data ->
                    GateResponse.StandbyPolicy(PuloonPayloadCodec.decodeStandby(data))
                }
            is GateOperation.SetStandbyPolicy -> write('U', encodeStandby(operation.policy))
            GateOperation.ReadDoorTiming ->
                read('U', byteArrayOf(ascii('1')) + DOOR_TIMING_SELECTOR) { data ->
                    GateResponse.DoorTiming(PuloonPayloadCodec.decodeDoorTiming(data))
                }
            is GateOperation.SetDoorTiming -> write('U', encodeDoorTiming(operation.timing))
            GateOperation.ReadSettings ->
                read('P', byteArrayOf(ascii('1'))) { data ->
                    val settings = if (data.firstOrNull() == ascii('1')) data.drop(1).toByteArray() else data
                    GateResponse.Settings(PuloonSettingsCodec.decode(settings))
                }
            is GateOperation.ApplySettings ->
                write(
                    'P',
                    byteArrayOf(ascii('0')) + PuloonSettingsCodec.encode(operation.settings),
                )
            is GateOperation.Diagnostic -> write('T', encodeDiagnostic(operation.diagnostic))
            GateOperation.Reset -> write('R')
        }

    /** Creates an idempotent Puloon read transaction. */
    private fun read(
        command: Char,
        data: ByteArray = ByteArray(0),
        responseCommands: Set<Char> = setOf(command),
        decode: (ByteArray) -> GateResponse,
    ): PuloonTransaction = transaction(command, data, responseCommands, idempotent = true, decode)

    /** Creates a non-idempotent Puloon state-changing transaction. */
    private fun write(
        command: Char,
        data: ByteArray = ByteArray(0),
    ): PuloonTransaction = transaction(command, data, setOf(command), idempotent = false) { GateResponse.Acknowledged }

    /** Allocates the next wrapping sequence and captures immutable request/correlation fields. */
    private fun transaction(
        command: Char,
        data: ByteArray,
        responseCommands: Set<Char>,
        idempotent: Boolean,
        decode: (ByteArray) -> GateResponse,
    ): PuloonTransaction {
        val currentSequence = sequence.also { sequence = (sequence + 1) and MAX_SEQUENCE }
        return PuloonTransaction(
            sequence = currentSequence,
            command = command,
            requestData = data.copyOf(),
            responseCommands = responseCommands.mapTo(mutableSetOf(), ::ascii),
            idempotent = idempotent,
            responseDecoder = decode,
        )
    }

    /** Validates and encodes the selected `U/2402` standby extension. */
    private fun encodeStandby(policy: GateStandbyPolicy): ByteArray {
        val seconds = policy.timeout.inWholeSeconds
        require(seconds in 0..MAX_UNSIGNED_BYTE) { "Standby timeout must be between 0 and 255 seconds" }
        return byteArrayOf(ascii('0')) + STANDBY_SELECTOR +
            PuloonPayloadCodec.encodeOffsetHexByte(seconds.toInt()) +
            byteArrayOf(ascii('3'), ascii(policy.passMode.wireValue()))
    }

    /** Validates and encodes the selected `U/1102` door-timing extension. */
    private fun encodeDoorTiming(timing: GateDoorTiming): ByteArray {
        val opening = timing.openingDelay.toDelayUnits("opening")
        val closing = timing.closingDelay.toDelayUnits("closing")
        return byteArrayOf(ascii('0')) + DOOR_TIMING_SELECTOR +
            PuloonPayloadCodec.encodeOffsetHexByte(opening) +
            PuloonPayloadCodec.encodeOffsetHexByte(closing)
    }

    /** Converts a duration to documented 100-millisecond door-delay units. */
    private fun Duration.toDelayUnits(name: String): Int {
        require(inWholeMilliseconds in 0..MAX_DOOR_DELAY_MILLISECONDS) {
            "Door $name delay must be between 0 and $MAX_DOOR_DELAY_MILLISECONDS milliseconds"
        }
        require(inWholeMilliseconds % DOOR_DELAY_STEP_MILLISECONDS == 0L) {
            "Door $name delay must use $DOOR_DELAY_STEP_MILLISECONDS-millisecond steps"
        }
        return (inWholeMilliseconds / DOOR_DELAY_STEP_MILLISECONDS).toInt()
    }

    /** Encodes an explicitly enabled maintenance diagnostic subtype. */
    private fun encodeDiagnostic(diagnostic: GateDiagnostic): ByteArray =
        when (diagnostic) {
            is GateDiagnostic.Door -> byteArrayOf(ascii('0'), ascii(if (diagnostic.open) '1' else '0'))
            is GateDiagnostic.Lamp -> {
                require(diagnostic.index in 0..9) { "Lamp index must be between 0 and 9" }
                byteArrayOf(
                    ascii('1'),
                    (ascii('0') + diagnostic.index).toByte(),
                    ascii(if (diagnostic.enabled) '1' else '0'),
                )
            }
            GateDiagnostic.Buzzer -> byteArrayOf(ascii('2'), ascii('1'))
        }

    /** Enforces mechanism-specific GCU safety-region bounds. */
    private fun validateSafetyRegion(region: Int) {
        val valid =
            when (hardware.mechanism) {
                GateMechanism.SECTOR -> region in 1..6
                GateMechanism.SWING -> region in 1..3
                GateMechanism.FLAP -> region in 1..3
            }
        require(valid) { "Safety region $region is invalid for ${hardware.mechanism}" }
    }

    /** Derives the immutable capability set from physical and operational configuration. */
    private fun buildCapabilities(maintenanceEnabled: Boolean): Set<GateCapability> =
        buildSet {
            addAll(BASE_CAPABILITIES)
            if (hardware.isIndia()) addAll(INDIA_CAPABILITIES)
            if (hardware.site == GateSite.KOLKATA_INDIA) add(GateCapability.STANDBY)
            if (GateModule.UPS in hardware.modules) add(GateCapability.UPS_SHUTDOWN)
            if (maintenanceEnabled) addAll(MAINTENANCE_CAPABILITIES)
        }

    /** Returns whether India-specific GCU commands and fields are available. */
    private fun GateHardwareProfile.isIndia(): Boolean = site == GateSite.INDIA || site == GateSite.KOLKATA_INDIA

    /** Maps the common enum ordinal to the stable documented GCU mode character. */
    private fun GatePassMode.wireValue(): Char = PASS_MODE_WIRE[ordinal]

    /** Encodes one unsigned value as two uppercase hexadecimal ASCII characters. */
    private fun encodeAsciiHex(value: Int): ByteArray =
        value
            .toString(HEX_BASE)
            .uppercase()
            .padStart(2, '0')
            .encodeToByteArray()

    private companion object {
        val VERSION_PATTERN = Regex("\\d{2}\\.\\d{2}")
        val STANDBY_SELECTOR = "2402".encodeToByteArray()
        val DOOR_TIMING_SELECTOR = "1102".encodeToByteArray()
        const val PASS_MODE_WIRE = "0123456789ABCDEF"
        const val MAX_SEQUENCE = 0xFFFF
        const val MAX_UNSIGNED_BYTE = 0xFF
        const val UPS_STEP_SECONDS = 10
        const val MAX_UPS_SECONDS = MAX_UNSIGNED_BYTE * UPS_STEP_SECONDS
        const val DOOR_DELAY_STEP_MILLISECONDS = 100L
        const val MAX_DOOR_DELAY_MILLISECONDS = 1_000L
        const val HEX_BASE = 16

        val BASE_CAPABILITIES =
            setOf(
                GateCapability.PASSAGE,
                GateCapability.EMERGENCY,
                GateCapability.INITIALIZE,
                GateCapability.FIRMWARE,
                GateCapability.STATUS,
                GateCapability.PASS_MODE,
                GateCapability.SAFETY_REGION,
                GateCapability.PASSAGE_COUNTERS,
                GateCapability.SENSORS,
                GateCapability.DOOR_TIMING,
                GateCapability.SETTINGS,
            )
        val INDIA_CAPABILITIES =
            setOf(
                GateCapability.MULTI_PERSON_PASSAGE,
                GateCapability.PASSAGE_LAMP,
                GateCapability.INVALID_TICKET,
                GateCapability.CLOCK,
            )
        val MAINTENANCE_CAPABILITIES = setOf(GateCapability.DIAGNOSTICS, GateCapability.RESET)
    }
}
