package com.qurkos.gate.sdk.internal.puloon

import com.qurkos.gate.sdk.GateCapability
import com.qurkos.gate.sdk.GateDescriptor
import com.qurkos.gate.sdk.GateDiagnostic
import com.qurkos.gate.sdk.GateDoorTestAction
import com.qurkos.gate.sdk.GateDoorTiming
import com.qurkos.gate.sdk.GateError
import com.qurkos.gate.sdk.GateFirmwareInfo
import com.qurkos.gate.sdk.GateHardwareProfile
import com.qurkos.gate.sdk.GateLampColor
import com.qurkos.gate.sdk.GateMechanism
import com.qurkos.gate.sdk.GateModule
import com.qurkos.gate.sdk.GatePassMode
import com.qurkos.gate.sdk.GateProtocolRevision
import com.qurkos.gate.sdk.GateResult
import com.qurkos.gate.sdk.GateSafetyRegion
import com.qurkos.gate.sdk.GateSensorId
import com.qurkos.gate.sdk.GateSetting
import com.qurkos.gate.sdk.GateSite
import com.qurkos.gate.sdk.GateStandbyPolicy
import com.qurkos.gate.sdk.GateSupport
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
    private val maintenanceOperationsEnabled: Boolean,
) : GateProtocolAdapter {
    private var sequence = 0

    override val descriptor = GateDescriptor(GateVendor.PULOON, hardware.mechanism, hardware.site)
    override val defaultSerialParameters = SerialParameters(baudRate = 57_600)
    override val minimumPollInterval: Duration = 101.milliseconds
    override val capabilities: Set<GateCapability> = buildCapabilities()
    override val support: GateSupport =
        GateSupport(
            capabilities = capabilities,
            passModes = GatePassMode.entries.filterTo(mutableSetOf()) { isPassModeSupported(it, hardware.normalOpen) },
            safetyRegions = supportedSafetyRegions(),
            sensors = supportedSensors(),
        )

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
            is GateOperation.SetPassMode -> {
                require(isPassModeSupported(operation.mode, operation.normalOpen)) {
                    "Pass mode ${operation.mode} is unsupported for ${hardware.mechanism}, ${hardware.site}, " +
                        "normalOpen=${operation.normalOpen}"
                }
                write('D', byteArrayOf(ascii(operation.mode.wireValue())))
            }
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
                    responseCommands = setOf('X', 'P'),
                )
            is GateOperation.SetUpsShutdownDelay -> {
                require(operation.seconds in 0..MAX_UPS_SECONDS && operation.seconds % UPS_STEP_SECONDS == 0) {
                    "UPS shutdown delay must be 0..$MAX_UPS_SECONDS seconds in $UPS_STEP_SECONDS-second steps"
                }
                write('Y', PuloonOffsetHexCodec.encode(operation.seconds / UPS_STEP_SECONDS))
            }
            GateOperation.ReadStandbyPolicy -> readStandbyTransaction()
            is GateOperation.SetStandbyPolicy -> setStandbyTransaction(operation)
            GateOperation.ReadDoorTiming -> readDoorTimingTransaction()
            is GateOperation.SetDoorTiming -> setDoorTimingTransaction(operation)
            GateOperation.ReadSettings ->
                read('P', byteArrayOf(ascii('1'))) { data ->
                    val settings = if (data.firstOrNull() == ascii('1')) data.drop(1).toByteArray() else data
                    GateResponse.Settings(PuloonSettingsCodec.decode(settings))
                }
            is GateOperation.ApplySettings -> {
                validateSettings(operation.settings)
                write('P', byteArrayOf(ascii('0')) + PuloonSettingsCodec.encode(operation.settings))
            }
            is GateOperation.Diagnostic -> write('T', encodeDiagnostic(operation.diagnostic))
            GateOperation.Reset -> write('R')
        }

    /** Creates the V2.8 Kolkata standby read after enforcing both compatibility dimensions. */
    private fun readStandbyTransaction(): PuloonTransaction {
        requireStandbySupport()
        return read('U', byteArrayOf(ascii('1')) + STANDBY_SELECTOR) { data ->
            GateResponse.StandbyPolicy(PuloonPayloadCodec.decodeStandby(data))
        }
    }

    /** Validates and creates the V2.8 Kolkata standby write. */
    private fun setStandbyTransaction(operation: GateOperation.SetStandbyPolicy): PuloonTransaction {
        requireStandbySupport()
        require(isPassModeSupported(operation.policy.passMode, operation.normalOpen)) {
            "Standby pass mode ${operation.policy.passMode} is unsupported for ${hardware.mechanism}, " +
                "${hardware.site}, normalOpen=${operation.normalOpen}"
        }
        return write('U', encodeStandby(operation.policy))
    }

    /** Creates the V2.8 door-timing read transaction. */
    private fun readDoorTimingTransaction(): PuloonTransaction {
        requireV28("Door timing")
        return read('U', byteArrayOf(ascii('1')) + DOOR_TIMING_SELECTOR) { data ->
            GateResponse.DoorTiming(PuloonPayloadCodec.decodeDoorTiming(data))
        }
    }

    /** Creates the V2.8 door-timing write transaction. */
    private fun setDoorTimingTransaction(operation: GateOperation.SetDoorTiming): PuloonTransaction {
        requireV28("Door timing")
        return write('U', encodeDoorTiming(operation.timing))
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
        responseCommands: Set<Char> = setOf(command),
    ): PuloonTransaction = transaction(command, data, responseCommands, idempotent = false) { GateResponse.Acknowledged }

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
            PuloonOffsetHexCodec.encode(seconds.toInt()) +
            byteArrayOf(ascii('3'), ascii(policy.passMode.wireValue()))
    }

    /** Validates and encodes the selected `U/1102` door-timing extension. */
    private fun encodeDoorTiming(timing: GateDoorTiming): ByteArray {
        val opening = timing.openingDelay.toDelayUnits("opening")
        val closing = timing.closingDelay.toDelayUnits("closing")
        return byteArrayOf(ascii('0')) + DOOR_TIMING_SELECTOR +
            PuloonOffsetHexCodec.encode(opening) +
            PuloonOffsetHexCodec.encode(closing)
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
            is GateDiagnostic.Door -> byteArrayOf(ascii('0'), ascii(diagnostic.action.wireValue()))
            is GateDiagnostic.EndDisplay -> byteArrayOf(ascii('1'), ascii(diagnostic.color.outputWireValue(diagnostic.enabled)))
            is GateDiagnostic.Indicator -> byteArrayOf(ascii('2'), ascii(diagnostic.color.outputWireValue(diagnostic.enabled)))
            is GateDiagnostic.Buzzer -> {
                val action = (diagnostic.index - 1) * 2 + if (diagnostic.enabled) 1 else 2
                byteArrayOf(ascii('3'), (ascii('0') + action).toByte())
            }
            is GateDiagnostic.ReturnCupLamp -> {
                require(hardware.protocolRevision == GateProtocolRevision.V2_8) {
                    "Return-cup lamp test requires Puloon protocol V2.8"
                }
                require(GateModule.TOKEN_CONTROL_UNIT in hardware.modules) {
                    "Return-cup lamp test requires the token control unit"
                }
                byteArrayOf(ascii('5'), ascii(if (diagnostic.enabled) '7' else '8'))
            }
        }

    /** Enforces mechanism-specific GCU safety-region bounds. */
    private fun validateSafetyRegion(region: Int) {
        val valid =
            when (hardware.mechanism) {
                GateMechanism.SECTOR -> region in 1..6
                GateMechanism.SWING -> region in 1..3
                GateMechanism.FLAP -> false
            }
        require(valid) { "Safety region $region is invalid for ${hardware.mechanism}" }
    }

    /** Derives the immutable capability set from physical and operational configuration. */
    private fun buildCapabilities(): Set<GateCapability> =
        buildSet {
            addAll(BASE_CAPABILITIES)
            if (hardware.isIndia()) addAll(INDIA_CAPABILITIES)
            if (hardware.protocolRevision == GateProtocolRevision.V2_8) add(GateCapability.DOOR_TIMING)
            if (hardware.protocolRevision == GateProtocolRevision.V2_8 && hardware.site == GateSite.KOLKATA_INDIA) {
                add(GateCapability.STANDBY)
            }
            if (hardware.isIndia() && GateModule.UPS in hardware.modules) add(GateCapability.UPS_SHUTDOWN)
            if (hardware.isIndia() && hardware.mechanism == GateMechanism.SECTOR) add(GateCapability.INVALID_TICKET)
            if (maintenanceOperationsEnabled) addAll(MAINTENANCE_CAPABILITIES)
        }

    private fun supportedSafetyRegions(): Set<GateSafetyRegion> =
        when (hardware.mechanism) {
            GateMechanism.SECTOR -> (1..6)
            GateMechanism.SWING -> (1..3)
            GateMechanism.FLAP -> IntRange.EMPTY
        }.mapTo(mutableSetOf(), ::GateSafetyRegion)

    private fun supportedSensors(): Set<GateSensorId> =
        buildSet {
            when (hardware.mechanism) {
                GateMechanism.SECTOR -> addAll(((1..9) + (11..19)).map(::GateSensorId))
                GateMechanism.SWING -> addAll(((1..9) + (11..19) + listOf(23, 24)).map(::GateSensorId))
                GateMechanism.FLAP -> Unit
            }
            if (hardware.site == GateSite.CHINA && GateModule.CHILD_SENSORS in hardware.modules) {
                addAll(listOf(10, 20, 21, 22).map(::GateSensorId))
            }
            if (hardware.isIndia() && GateModule.TOKEN_CONTROL_UNIT in hardware.modules) {
                addAll(listOf(21, 22, 25, 26).map(::GateSensorId))
            }
        }

    private fun validateSettings(settings: Set<GateSetting>) {
        val normalOpen = settings.filterIsInstance<GateSetting.NormalOpenMode>().singleOrNull()
        if (hardware.mechanism == GateMechanism.SWING) {
            require(normalOpen?.enabled != true) { "Normal-open gate mode is unsupported by SwingDoor" }
        }
        val child = settings.filterIsInstance<GateSetting.ChildDetection>().singleOrNull()
        if (hardware.isIndia()) require(child?.level == 0) { "Child detection must be disabled for India profiles" }
        if (child != null && child.level > 0) {
            require(hardware.site == GateSite.CHINA && GateModule.CHILD_SENSORS in hardware.modules) {
                "Child detection requires the China profile and installed child sensors"
            }
        }
    }

    private fun isPassModeSupported(
        mode: GatePassMode,
        normalOpen: Boolean,
    ): Boolean =
        when (mode) {
            GatePassMode.CONTROLLED_BOTH -> hardware.mechanism != GateMechanism.SWING || !normalOpen
            GatePassMode.FREE_ENTRY_LOCKED_EXIT_NORMAL_CLOSED,
            GatePassMode.FREE_EXIT_LOCKED_ENTRY_NORMAL_CLOSED,
            -> !normalOpen
            GatePassMode.CONTROLLED_ENTRY_LOCKED_EXIT,
            GatePassMode.CONTROLLED_EXIT_LOCKED_ENTRY,
            -> true
            GatePassMode.FREE_ENTRY_CONTROLLED_EXIT_NORMAL_CLOSED,
            GatePassMode.FREE_EXIT_CONTROLLED_ENTRY_NORMAL_CLOSED,
            -> hardware.mechanism != GateMechanism.SWING || !normalOpen
            GatePassMode.FREE_ENTRY_CONTROLLED_EXIT_NORMAL_OPEN,
            GatePassMode.FREE_EXIT_CONTROLLED_ENTRY_NORMAL_OPEN,
            -> normalOpen && hardware.mechanism != GateMechanism.SWING
            GatePassMode.FREE_ENTRY_LOCKED_EXIT_NORMAL_OPEN,
            GatePassMode.FREE_EXIT_LOCKED_ENTRY_NORMAL_OPEN,
            -> normalOpen
            GatePassMode.FREE_BOTH ->
                (hardware.mechanism == GateMechanism.SECTOR && normalOpen) ||
                    (hardware.mechanism == GateMechanism.SWING && !normalOpen)
            GatePassMode.LOCKED_BOTH -> true
            GatePassMode.MAINTENANCE -> maintenanceOperationsEnabled
            GatePassMode.TEST_PASSAGE,
            GatePassMode.OUT_OF_SERVICE,
            -> hardware.isIndia()
        }

    private fun GateDoorTestAction.wireValue(): Char = ('1'.code + ordinal).toChar()

    private fun GateLampColor.outputWireValue(enabled: Boolean): Char {
        val onValue =
            when (this) {
                GateLampColor.GREEN -> 1
                GateLampColor.BLUE, GateLampColor.YELLOW -> 3
                GateLampColor.RED -> 5
                GateLampColor.OFF -> throw IllegalArgumentException("OFF is not a diagnostic output color")
            }
        return ('0'.code + onValue + if (enabled) 0 else 1).toChar()
    }

    /** Returns whether India-specific GCU commands and fields are available. */
    private fun GateHardwareProfile.isIndia(): Boolean = site == GateSite.INDIA || site == GateSite.KOLKATA_INDIA

    /** Rejects extension commands that were introduced after the V2.5 protocol. */
    private fun requireV28(feature: String) {
        require(hardware.protocolRevision == GateProtocolRevision.V2_8) {
            "$feature requires Puloon protocol V2.8"
        }
    }

    /** Requires the revision and site combination documented for standby configuration. */
    private fun requireStandbySupport() {
        requireV28("Standby configuration")
        require(hardware.site == GateSite.KOLKATA_INDIA) {
            "Standby configuration requires the Kolkata India profile"
        }
    }

    /** Maps the common enum ordinal to the documented offset mode byte (`0x30..0x3F`). */
    private fun GatePassMode.wireValue(): Char = (PASS_MODE_BASE + ordinal).toChar()

    private companion object {
        val VERSION_PATTERN = Regex("\\d{2}\\.\\d{2}")
        val STANDBY_SELECTOR = "2402".encodeToByteArray()
        val DOOR_TIMING_SELECTOR = "1102".encodeToByteArray()
        const val PASS_MODE_BASE = 0x30
        const val MAX_SEQUENCE = 0xFFFF
        const val MAX_UNSIGNED_BYTE = 0xFF
        const val UPS_STEP_SECONDS = 10
        const val MAX_UPS_SECONDS = MAX_UNSIGNED_BYTE * UPS_STEP_SECONDS
        const val DOOR_DELAY_STEP_MILLISECONDS = 100L
        const val MAX_DOOR_DELAY_MILLISECONDS = 1_000L

        val BASE_CAPABILITIES =
            setOf(
                GateCapability.PASSAGE,
                GateCapability.EMERGENCY,
                GateCapability.FIRMWARE,
                GateCapability.STATUS,
                GateCapability.PASS_MODE,
                GateCapability.SAFETY_REGION,
                GateCapability.SENSORS,
                GateCapability.SETTINGS,
            )
        val INDIA_CAPABILITIES =
            setOf(
                GateCapability.MULTI_PERSON_PASSAGE,
                GateCapability.PASSAGE_LAMP,
                GateCapability.CLOCK,
            )
        val MAINTENANCE_CAPABILITIES =
            setOf(
                GateCapability.DIAGNOSTICS,
                GateCapability.RESET,
                GateCapability.INITIALIZE,
                GateCapability.PASSAGE_COUNTERS,
            )
    }
}
