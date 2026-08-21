package com.qurkos.gate.sdk

import kotlinx.datetime.LocalDateTime
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Immutable normalized identity information for a configured gate instance.
 *
 * @property vendor Selected protocol vendor.
 * @property mechanism Configured physical barrier mechanism.
 * @property site Configured regional protocol profile.
 */
public data class GateDescriptor(
    public val vendor: GateVendor,
    public val mechanism: GateMechanism,
    public val site: GateSite,
)

/** Lifecycle state emitted by [Gate.connectionState] and [GateEvent.ConnectionChanged]. */
public enum class GateConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED,
}

/**
 * Vendor-neutral feature identifiers used for preflight capability checks.
 *
 * Applications may inspect [Gate.capabilities] to adapt their UI. Calling an unavailable operation is still safe and
 * returns [GateError.UnsupportedCapability] without writing to the serial port.
 */
public enum class GateCapability {
    PASSAGE,
    MULTI_PERSON_PASSAGE,
    PASSAGE_LAMP,
    INVALID_TICKET,
    EMERGENCY,
    INITIALIZE,
    FIRMWARE,
    STATUS,
    PASS_MODE,
    SAFETY_REGION,
    PASSAGE_COUNTERS,
    SENSORS,
    CLOCK,
    UPS_SHUTDOWN,
    STANDBY,
    DOOR_TIMING,
    SETTINGS,
    DIAGNOSTICS,
    RESET,
}

/** Direction of passenger movement relative to the controlled area. */
public enum class GateDirection {
    ENTRY,
    EXIT,
}

/** Requested passage indicator color, normalized across supported controllers. */
public enum class GateLampColor {
    OFF,
    GREEN,
    BLUE,
    RED,
    YELLOW,
}

/**
 * Authorization or rejection request sent to a gate for one direction.
 *
 * @property direction Passenger movement direction.
 * @property passengerCount Number of passengers authorized by one request, from 1 through 99.
 * @property lampColor Requested controller indicator color.
 * @property invalidTicket Whether this is an invalid-ticket rejection rather than an authorization.
 */
public data class GatePassageRequest(
    public val direction: GateDirection,
    public val passengerCount: Int = 1,
    public val lampColor: GateLampColor = GateLampColor.GREEN,
    public val invalidTicket: Boolean = false,
) {
    init {
        require(passengerCount in 1..99) { "Passenger count must be between 1 and 99" }
    }
}

/** Semantic operating modes shared by gate protocol families. */
public enum class GatePassMode {
    CONTROLLED_BOTH,
    FREE_ENTRY_LOCKED_EXIT_NORMAL_CLOSED,
    FREE_EXIT_LOCKED_ENTRY_NORMAL_CLOSED,
    CONTROLLED_ENTRY_LOCKED_EXIT,
    CONTROLLED_EXIT_LOCKED_ENTRY,
    FREE_ENTRY_CONTROLLED_EXIT_NORMAL_CLOSED,
    FREE_EXIT_CONTROLLED_ENTRY_NORMAL_CLOSED,
    FREE_ENTRY_CONTROLLED_EXIT_NORMAL_OPEN,
    FREE_EXIT_CONTROLLED_ENTRY_NORMAL_OPEN,
    FREE_ENTRY_LOCKED_EXIT_NORMAL_OPEN,
    FREE_EXIT_LOCKED_ENTRY_NORMAL_OPEN,
    FREE_BOTH,
    LOCKED_BOTH,
    MAINTENANCE,
    TEST_PASSAGE,
    OUT_OF_SERVICE,
}

/**
 * One-based controller safety-region identifier.
 *
 * The adapter validates mechanism-specific upper bounds before serial I/O.
 *
 * @property number Positive one-based region number.
 */
@JvmInline
public value class GateSafetyRegion(
    public val number: Int,
) {
    init {
        require(number > 0) { "Safety region number must be positive" }
    }
}

/**
 * One-based normalized sensor identifier.
 *
 * @property number Positive one-based sensor number.
 */
@JvmInline
public value class GateSensorId(
    public val number: Int,
) {
    init {
        require(number > 0) { "Sensor number must be positive" }
    }
}

/** Emergency input state reported by a controller. */
public enum class GateEmergencyState {
    INACTIVE,
    ECU,
    TWENTY_FOUR_VOLT,
    BOTH,
    UNKNOWN,
}

/**
 * Normalized snapshot of gate sensors.
 *
 * @property active One-based identifiers currently active.
 * @property hasFault Whether the controller reports a sensor subsystem fault.
 * @property faulted One-based identifiers whose individual fault bits are set.
 */
public data class GateSensorStatus(
    public val active: Set<GateSensorId>,
    public val hasFault: Boolean,
    public val faulted: Set<GateSensorId> = emptySet(),
)

/**
 * Optional power subsystem status.
 *
 * @property upsPresent Whether the configured/observed controller includes a UPS.
 * @property online Whether external/line power is available when reported.
 * @property onBattery Whether the UPS is supplying battery power when reported.
 * @property chargePercent Remaining battery charge from 0 through 100 when reported.
 * @property summary Vendor-normalized diagnostic summary when the protocol exposes one.
 */
public data class GatePowerStatus(
    public val upsPresent: Boolean,
    public val online: Boolean? = null,
    public val onBattery: Boolean? = null,
    public val chargePercent: Int? = null,
    public val summary: String? = null,
) {
    init {
        require(chargePercent == null || chargePercent in 0..100) { "UPS charge must be between 0 and 100 percent" }
    }
}

/** Reason the controller last consumed or cleared an authorized passage count. */
public enum class GatePassageResult {
    IDLE,

    /** Direction-neutral completion value reported by V2.5 controllers. */
    PASSAGE_COMPLETED,

    /** No-entry timeout value reported by V2.5 controllers. */
    NO_ENTRY_TIMEOUT,

    /** Passing timeout value reported by V2.5 controllers. */
    PASSING_TIMEOUT,

    /** Tailing counter-use value reported by V2.5 controllers. */
    TAILING_USED,

    /** Wrong-way counter-use value reported by V2.5 controllers. */
    WRONG_WAY_USED,
    ENTRY_COMPLETED,
    EXIT_COMPLETED,
    ENTRY_TIMEOUT,
    EXIT_TIMEOUT,
    ENTRY_PRE_DOOR_TIMEOUT,
    EXIT_PRE_DOOR_TIMEOUT,
    ENTRY_TAILGATE_USED,
    EXIT_TAILGATE_USED,
    ENTRY_WRONG_WAY_USED,
    EXIT_WRONG_WAY_USED,
    UNKNOWN,
}

/** Direction-specific fraud or intrusion condition reported by status. */
public enum class GatePassageError {
    NORMAL,
    PIGGY_TAILING,
    INTRUSION,
    WRONG_WAY_FRAUD,
    UNKNOWN,
}

/** Door actuator faults reported in the status bit mask. */
public enum class GateDoorFault {
    DOOR_1_OPEN,
    DOOR_1_CLOSE,
    DOOR_2_OPEN,
    DOOR_2_CLOSE,
}

/** Occupancy zones reported by the GCU inner-state bit mask. */
public enum class GateOccupancyZone {
    ENTRY_BLOCK_1,
    ENTRY_BLOCK_2,
    ENTRY_BLOCK_3,
    SAFETY_AREA,
    EXIT_BLOCK_3,
    EXIT_BLOCK_2,
    EXIT_BLOCK_1,
}

/**
 * Latest normalized status read from a controller.
 *
 * @property passMode Current passage behavior.
 * @property entryCount Controller entry counter.
 * @property exitCount Controller exit counter.
 * @property emergency Current emergency input state.
 * @property sensors Sensor summary included in the status response.
 * @property power Optional UPS/power information.
 * @property passageResult Most recent passage completion or timeout result.
 * @property entryError Fraud or intrusion condition detected in the entry direction.
 * @property exitError Fraud or intrusion condition detected in the exit direction.
 * @property doorFaults Door actuator fault bits currently reported by the GCU.
 * @property occupiedZones Passenger-detection zones currently occupied.
 * @property switches One-based controller switch states when exposed by the status frame.
 * @property inputs One-based controller input states when exposed by the status frame.
 * @property tokenPathACount Optional token-control-unit path A count.
 * @property tokenPathBCount Optional token-control-unit path B count.
 * @property returnCupOccupied Optional token return-cup occupancy state.
 * @property observedAt SDK clock instant at which the valid response was decoded.
 */
public data class GateStatus(
    public val passMode: GatePassMode,
    public val entryCount: Int,
    public val exitCount: Int,
    public val emergency: GateEmergencyState,
    public val sensors: GateSensorStatus,
    public val power: GatePowerStatus? = null,
    public val passageResult: GatePassageResult = GatePassageResult.UNKNOWN,
    public val entryError: GatePassageError = GatePassageError.UNKNOWN,
    public val exitError: GatePassageError = GatePassageError.UNKNOWN,
    public val doorFaults: Set<GateDoorFault> = emptySet(),
    public val occupiedZones: Set<GateOccupancyZone> = emptySet(),
    public val switches: Map<Int, Boolean> = emptyMap(),
    public val inputs: Map<Int, Boolean> = emptyMap(),
    public val tokenPathACount: Int? = null,
    public val tokenPathBCount: Int? = null,
    public val returnCupOccupied: Boolean? = null,
    public val observedAt: Instant,
)

/**
 * Controller firmware identification.
 *
 * @property version Vendor-provided firmware version.
 * @property model Optional controller model when exposed by the protocol.
 */
public data class GateFirmwareInfo(
    public val version: String,
    public val model: String? = null,
)

/**
 * Controller-local civil date and time without a time-zone assumption.
 *
 * @property dateTime Valid local date and time represented with `kotlinx-datetime`.
 */
public data class GateClock(
    public val dateTime: LocalDateTime,
)

/**
 * Controller standby transition policy.
 *
 * @property timeout Idle duration before transition.
 * @property passMode Mode selected after the timeout.
 */
public data class GateStandbyPolicy(
    public val timeout: Duration,
    public val passMode: GatePassMode,
)

/**
 * Mechanical door timing configuration.
 *
 * @property openingDelay Delay used by the controller while opening.
 * @property closingDelay Delay used by the controller while closing.
 */
public data class GateDoorTiming(
    public val openingDelay: Duration,
    public val closingDelay: Duration,
)

/**
 * Explicit maintenance diagnostic.
 *
 * Diagnostics can actuate gate hardware and require [GateDeviceConfig.maintenanceOperationsEnabled].
 */
public enum class GateDoorTestAction {
    OPEN,
    CLOSE,
    FREE,
    LOCK,
}

/** Typed maintenance diagnostics documented by the selected gate controller protocol. */
public sealed interface GateDiagnostic {
    /** Runs one documented door actuator test. */
    public data class Door(
        /** Requested actuator action. */
        public val action: GateDoorTestAction,
    ) : GateDiagnostic

    /**
     * Controls the gate-end display using the documented green, yellow, and red outputs.
     *
     * @property color Documented end-display output color.
     * @property enabled Whether the selected output is illuminated.
     */
    public data class EndDisplay(
        public val color: GateLampColor,
        public val enabled: Boolean,
    ) : GateDiagnostic {
        init {
            require(color == GateLampColor.GREEN || color == GateLampColor.YELLOW || color == GateLampColor.RED) {
                "End-display test supports green, yellow, or red"
            }
        }
    }

    /**
     * Controls the concession/direction indicator using green, blue, or red.
     *
     * @property color Documented indicator output color.
     * @property enabled Whether the selected output is illuminated.
     */
    public data class Indicator(
        public val color: GateLampColor,
        public val enabled: Boolean,
    ) : GateDiagnostic {
        init {
            require(color == GateLampColor.GREEN || color == GateLampColor.BLUE || color == GateLampColor.RED) {
                "Indicator test supports green, blue, or red"
            }
        }
    }

    /**
     * Controls one of the three documented buzzer outputs.
     *
     * @property index One-based buzzer output number from 1 through 3.
     * @property enabled Whether the selected buzzer is energized.
     */
    public data class Buzzer(
        public val index: Int,
        public val enabled: Boolean,
    ) : GateDiagnostic {
        init {
            require(index in 1..3) { "Buzzer index must be between 1 and 3" }
        }
    }

    /** Controls the optional TCU return-cup LED. */
    public data class ReturnCupLamp(
        /** Whether the lamp is illuminated. */
        public val enabled: Boolean,
    ) : GateDiagnostic
}

/**
 * Typed controller setting returned by [Gate.readSettings] and accepted by [Gate.applySettings].
 *
 * Supported ranges remain adapter-specific. Controllers that write settings as one block may require exactly one value
 * of every supported subtype.
 */
public sealed interface GateSetting {
    /** Normal-open behavior. @property enabled Whether the barrier normally remains open. */
    public data class NormalOpenMode(
        /** Whether the barrier normally remains open. */
        public val enabled: Boolean,
    ) : GateSetting

    /** Time allowed for a passenger to enter after authorization. */
    public data class NoEntryTimeout(
        /** Finite adapter-validated duration. */
        public val value: Duration,
    ) : GateSetting

    /**
     * Child-detection controller level: 0 disabled, 1 normal, 2 optional enhanced sensors.
     *
     * @property level Documented controller level from 0 through 2.
     */
    public data class ChildDetection(
        public val level: Int,
    ) : GateSetting

    /** Tailing sensitivity. @property level Adapter-validated controller level. */
    public data class TailingSensitivity(
        /** Adapter-validated controller level. */
        public val level: Int,
    ) : GateSetting

    /** Hurry-up behavior. @property level Adapter-validated controller level. */
    public data class HurryUpLevel(
        /** Adapter-validated controller level. */
        public val level: Int,
    ) : GateSetting

    /**
     * Tag-timeout reference behavior.
     *
     * @property enabled Whether timeout is measured from the most recently detected tag.
     */
    public data class TagTimeoutFromLastTag(
        public val enabled: Boolean,
    ) : GateSetting

    /**
     * Raw documented buzzer-timeout value, from 0x00 through 0xFE.
     *
     * @property value Unsigned controller timeout unit from 0 through 254.
     */
    public data class BuzzerTimeoutUnits(
        public val value: Int,
    ) : GateSetting

    /**
     * Safety-region timeout, or `null` for the documented 0xFF disabled value.
     *
     * @property value Finite timeout duration, or `null` to disable the timeout.
     */
    public data class SafetyRegionTimeout(
        public val value: Duration?,
    ) : GateSetting
}

/**
 * Asynchronous information emitted by [Gate.events].
 *
 * Events are observational; command completion and errors are returned directly through [GateResult]. Consumers should
 * not use events as command acknowledgements.
 */
public sealed interface GateEvent {
    /** Connection transition. @property state Newly published serial lifecycle state. */
    public data class ConnectionChanged(
        /** Newly published serial lifecycle state. */
        public val state: GateConnectionState,
    ) : GateEvent

    /** Status transition. @property status Newly validated status snapshot. */
    public data class StatusChanged(
        /** Newly validated status snapshot. */
        public val status: GateStatus,
    ) : GateEvent

    /** Protocol warning. @property message Non-sensitive validation diagnostic. */
    public data class ProtocolWarning(
        /** Non-sensitive validation diagnostic. */
        public val message: String,
    ) : GateEvent

    /** Reconnect notification. @property attempt One-based attempt number. */
    public data class ReconnectAttempt(
        /** One-based reconnect attempt number. */
        public val attempt: Int,
    ) : GateEvent

    /** A semantic SDK command was accepted for serialized transmission. */
    public data class CommandSent(
        /** Monotonic identifier used to correlate the response. */
        public val sequence: Long,
        /** Vendor-neutral operation being sent. */
        public val command: GateCommand,
        /** Human-readable typed request summary without raw protocol bytes. */
        public val detail: String,
        /** Wall-clock time at which the command entered the serial execution path. */
        public val at: Instant,
    ) : GateEvent

    /** The serial execution path completed for a previously emitted [CommandSent]. */
    public data class ResponseReceived(
        /** Correlation identifier copied from [CommandSent.sequence]. */
        public val sequence: Long,
        /** Vendor-neutral operation that completed. */
        public val command: GateCommand,
        /** Whether the response completed successfully. */
        public val outcome: GateCommandOutcome,
        /** Human-readable normalized response or error summary. */
        public val detail: String,
        /** Total serialized operation latency, including retries and response decoding. */
        public val elapsed: Duration,
        /** Wall-clock time at which execution completed. */
        public val at: Instant,
    ) : GateEvent
}

/** Semantic command names exposed for safe traffic monitoring without protocol wire access. */
public enum class GateCommand {
    CONNECT,
    DISCONNECT,
    FIRMWARE,
    PASSAGE,
    EMERGENCY,
    INITIALIZE,
    STATUS,
    SET_PASS_MODE,
    SET_SAFETY_REGION,
    CLEAR_PASSAGE_COUNTERS,
    SENSORS,
    READ_CLOCK,
    SET_CLOCK,
    SET_UPS_SHUTDOWN_DELAY,
    READ_STANDBY_POLICY,
    SET_STANDBY_POLICY,
    READ_DOOR_TIMING,
    SET_DOOR_TIMING,
    READ_SETTINGS,
    APPLY_SETTINGS,
    DIAGNOSTIC,
    RESET,
}

/** Terminal result classification for [GateEvent.ResponseReceived]. */
public enum class GateCommandOutcome {
    SUCCESS,
    FAILURE,
}
