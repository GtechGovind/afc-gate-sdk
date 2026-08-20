package com.qurkos.gate.sdk.internal

import com.qurkos.gate.sdk.GateCapability
import com.qurkos.gate.sdk.GateClock
import com.qurkos.gate.sdk.GateDescriptor
import com.qurkos.gate.sdk.GateDiagnostic
import com.qurkos.gate.sdk.GateDoorTiming
import com.qurkos.gate.sdk.GateError
import com.qurkos.gate.sdk.GateFirmwareInfo
import com.qurkos.gate.sdk.GatePassMode
import com.qurkos.gate.sdk.GatePassageRequest
import com.qurkos.gate.sdk.GateResult
import com.qurkos.gate.sdk.GateSafetyRegion
import com.qurkos.gate.sdk.GateSensorStatus
import com.qurkos.gate.sdk.GateSetting
import com.qurkos.gate.sdk.GateStandbyPolicy
import com.qurkos.gate.sdk.GateStatus
import com.qurkos.gate.sdk.GateSupport
import com.qurkos.gate.sdk.SerialParameters
import kotlin.time.Duration

/** Vendor-neutral operation algebra passed from the common controller to a protocol adapter. */
internal sealed interface GateOperation {
    /** Read firmware metadata. */
    data object Firmware : GateOperation

    /** Passage operation. @property request Complete authorization/rejection request. */
    data class Passage(
        /** Complete authorization or rejection request. */
        val request: GatePassageRequest,
    ) : GateOperation

    /** Emergency mutation. @property enabled Requested emergency state. */
    data class Emergency(
        /** Requested emergency state. */
        val enabled: Boolean,
    ) : GateOperation

    /** Initialize the controller. */
    data object Initialize : GateOperation

    /** Read normalized status. */
    data object Status : GateOperation

    /** Passage-mode mutation. @property mode Requested common mode. */
    data class SetPassMode(
        /** Requested common passage mode. */
        val mode: GatePassMode,
        /** Last confirmed normal-open setting used for profile validation. */
        val normalOpen: Boolean = false,
    ) : GateOperation

    /** Safety-region mutation. @property region Requested one-based region. */
    data class SetSafetyRegion(
        /** Requested one-based safety region. */
        val region: GateSafetyRegion,
    ) : GateOperation

    /** Clear passage counters. */
    data object ClearPassageCounters : GateOperation

    /** Read the sensor bitmap. */
    data object Sensors : GateOperation

    /** Read the controller-local clock. */
    data object ReadClock : GateOperation

    /** Clock mutation. @property clock Requested local clock. */
    data class SetClock(
        /** Requested controller-local clock. */
        val clock: GateClock,
    ) : GateOperation

    /** UPS shutdown delay mutation. @property seconds Requested whole-second delay. */
    data class SetUpsShutdownDelay(
        /** Requested whole-second shutdown delay. */
        val seconds: Int,
    ) : GateOperation

    /** Read standby policy. */
    data object ReadStandbyPolicy : GateOperation

    /** Standby-policy mutation. @property policy Complete requested policy. */
    data class SetStandbyPolicy(
        /** Complete requested standby policy. */
        val policy: GateStandbyPolicy,
    ) : GateOperation

    /** Read door timing. */
    data object ReadDoorTiming : GateOperation

    /** Door-timing mutation. @property timing Complete requested timing. */
    data class SetDoorTiming(
        /** Complete requested door timing. */
        val timing: GateDoorTiming,
    ) : GateOperation

    /** Read complete typed settings. */
    data object ReadSettings : GateOperation

    /** Settings mutation. @property settings Complete typed settings block. */
    data class ApplySettings(
        /** Complete typed settings block. */
        val settings: Set<GateSetting>,
    ) : GateOperation

    /** Maintenance diagnostic. @property diagnostic Requested diagnostic subtype. */
    data class Diagnostic(
        /** Requested maintenance diagnostic subtype. */
        val diagnostic: GateDiagnostic,
    ) : GateOperation

    /** Reset the controller. */
    data object Reset : GateOperation
}

/** Normalized protocol response algebra returned by a decoded serial transaction. */
internal sealed interface GateResponse {
    /** Successful command acknowledgement without payload. */
    data object Acknowledged : GateResponse

    /** Firmware response. @property value Normalized firmware metadata. */
    data class Firmware(
        /** Normalized firmware metadata. */
        val value: GateFirmwareInfo,
    ) : GateResponse

    /** Status response. @property value Normalized status snapshot. */
    data class Status(
        /** Normalized status snapshot. */
        val value: GateStatus,
    ) : GateResponse

    /** Sensor response. @property value Normalized sensor snapshot. */
    data class Sensors(
        /** Normalized sensor snapshot. */
        val value: GateSensorStatus,
    ) : GateResponse

    /** Clock response. @property value Normalized local clock. */
    data class Clock(
        /** Normalized controller-local clock. */
        val value: GateClock,
    ) : GateResponse

    /** Standby response. @property value Normalized policy. */
    data class StandbyPolicy(
        /** Normalized standby policy. */
        val value: GateStandbyPolicy,
    ) : GateResponse

    /** Door-timing response. @property value Normalized timing. */
    data class DoorTiming(
        /** Normalized door timing. */
        val value: GateDoorTiming,
    ) : GateResponse

    /** Settings response. @property value Complete typed settings block. */
    data class Settings(
        /** Complete typed settings block. */
        val value: Set<GateSetting>,
    ) : GateResponse
}

/** Marker implemented by an immutable, validated frame produced by a streaming decoder. */
internal interface ProtocolFrame

/** One result produced while incrementally consuming transport bytes. */
internal sealed interface FrameDecodeResult {
    /** Successfully validated frame. @property value Immutable protocol frame. */
    data class Frame(
        /** Immutable validated protocol frame. */
        val value: ProtocolFrame,
    ) : FrameDecodeResult

    /** Recoverable malformed input. @property message Safe validation diagnostic. */
    data class Error(
        /** Safe validation diagnostic. */
        val message: String,
    ) : FrameDecodeResult
}

/** Stateful frame decoder that accepts arbitrarily fragmented or coalesced serial chunks. */
internal interface StreamingFrameDecoder {
    /**
     * Consumes a defensive copy of [bytes] and returns every complete frame or recoverable error now available.
     */
    fun feed(bytes: ByteArray): List<FrameDecodeResult>

    /** Discards buffered partial input when a serial session boundary changes. */
    fun reset()
}

/**
 * One immutable request/response correlation unit.
 *
 * A transaction is created once per logical operation. Idempotent retries preserve correlation identity while encoding
 * the attempt number required by the vendor protocol.
 */
internal interface SerialTransaction {
    /** Stable, non-sensitive name used in timeout diagnostics. */
    val operationName: String

    /** Whether retrying this request is guaranteed not to change device state. */
    val idempotent: Boolean

    /** Encodes a fresh request byte array for zero-based [attempt]. */
    fun encode(attempt: Int): ByteArray

    /** Returns whether [frame] is the response correlated to this transaction. */
    fun matches(frame: ProtocolFrame): Boolean

    /** Validates and normalizes an already correlated [frame]. */
    fun decode(frame: ProtocolFrame): GateResult<GateResponse>
}

/**
 * Internal extension point implemented by each supported serial controller protocol.
 *
 * Adapters translate operations and bytes only. Connection lifecycle, concurrency, retries, and reconnection remain in
 * [SerialSession].
 */
internal interface GateProtocolAdapter {
    /** Normalized configured identity. */
    val descriptor: GateDescriptor

    /** Capabilities supported by this adapter and hardware profile. */
    val capabilities: Set<GateCapability>

    /** Complete profile support used by pre-connection UI filtering. */
    val support: GateSupport

    /** Documented line settings used when callers omit explicit parameters. */
    val defaultSerialParameters: SerialParameters

    /** Smallest polling interval that does not violate the controller protocol. */
    val minimumPollInterval: Duration

    /** Creates isolated streaming decoder state for one serial session. */
    fun newDecoder(): StreamingFrameDecoder

    /** Validates and converts [operation] into an immutable transaction without performing I/O. */
    fun transaction(operation: GateOperation): GateResult<SerialTransaction>
}

/** Creates the standard typed result for a malformed or unexpected protocol response. */
internal fun protocolFailure(message: String): GateResult.Failure = GateResult.Failure(GateError.Protocol(message))
