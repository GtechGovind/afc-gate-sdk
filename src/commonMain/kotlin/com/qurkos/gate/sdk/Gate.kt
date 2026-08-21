package com.qurkos.gate.sdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Unified API implemented by every supported AFC gate.
 *
 * Implementations are safe for concurrent coroutine callers. Commands are serialized on the wire.
 */
public interface Gate {
    /** Immutable vendor and hardware identity supplied when this gate was created. */
    public val descriptor: GateDescriptor

    /** Operations supported by the selected adapter and hardware profile. */
    public val capabilities: Set<GateCapability>

    /** Current serial lifecycle state. */
    public val connectionState: StateFlow<GateConnectionState>

    /** Latest normalized status, or `null` before the first successful status read. */
    public val status: StateFlow<GateStatus?>

    /** Connection, status, reconnect, and recoverable protocol events. */
    public val events: Flow<GateEvent>

    /** Opens the configured serial port and starts status monitoring when enabled. */
    public suspend fun connect(): GateResult<Unit>

    /** Stops monitoring and releases the serial port. */
    public suspend fun disconnect(): GateResult<Unit>

    /** Authorizes or rejects a passage using a fully specified request. */
    public suspend fun allowPassage(request: GatePassageRequest): GateResult<Unit>

    /** Authorizes entry for [passengerCount] passengers. */
    public suspend fun allowEntry(
        passengerCount: Int = 1,
        lampColor: GateLampColor = GateLampColor.GREEN,
    ): GateResult<Unit> = allowPassage(GatePassageRequest(GateDirection.ENTRY, passengerCount, lampColor))

    /** Authorizes exit for [passengerCount] passengers. */
    public suspend fun allowExit(
        passengerCount: Int = 1,
        lampColor: GateLampColor = GateLampColor.GREEN,
    ): GateResult<Unit> = allowPassage(GatePassageRequest(GateDirection.EXIT, passengerCount, lampColor))

    /** Sends the vendor-neutral invalid-ticket response for [direction]. */
    public suspend fun rejectPassage(
        direction: GateDirection,
        lampColor: GateLampColor = GateLampColor.RED,
    ): GateResult<Unit> =
        allowPassage(
            GatePassageRequest(
                direction = direction,
                lampColor = lampColor,
                invalidTicket = true,
            ),
        )

    /** Enables or clears the controller's emergency state. */
    public suspend fun setEmergency(enabled: Boolean): GateResult<Unit>

    /** Runs the controller initialization command. */
    public suspend fun initialize(): GateResult<Unit>

    /** Reads controller firmware information. */
    public suspend fun firmware(): GateResult<GateFirmwareInfo>

    /** Reads status immediately and updates [status] on success. */
    public suspend fun refreshStatus(): GateResult<GateStatus>

    /** Changes the controller's passage mode. */
    public suspend fun setPassMode(mode: GatePassMode): GateResult<Unit>

    /** Selects a safety-sensor region supported by the gate mechanism. */
    public suspend fun setSafetyRegion(region: GateSafetyRegion): GateResult<Unit>

    /** Resets accumulated entry and exit counters. Puloon controllers also close the barrier as part of this command. */
    public suspend fun clearPassageCounters(): GateResult<Unit>

    /** Reads normalized safety-sensor state. */
    public suspend fun readSensors(): GateResult<GateSensorStatus>

    /** Reads the controller's real-time clock. */
    public suspend fun readClock(): GateResult<GateClock>

    /** Sets the controller's real-time clock. */
    public suspend fun setClock(clock: GateClock): GateResult<Unit>

    /** Configures the delay before an installed UPS powers down. */
    public suspend fun setUpsShutdownDelaySeconds(seconds: Int): GateResult<Unit>

    /** Reads the controller's standby policy. */
    public suspend fun readStandbyPolicy(): GateResult<GateStandbyPolicy>

    /** Replaces the controller's standby policy. */
    public suspend fun setStandbyPolicy(policy: GateStandbyPolicy): GateResult<Unit>

    /** Reads door opening and closing delays. */
    public suspend fun readDoorTiming(): GateResult<GateDoorTiming>

    /** Sets door opening and closing delays. */
    public suspend fun setDoorTiming(timing: GateDoorTiming): GateResult<Unit>

    /** Reads all settings represented by the controller's typed settings block. */
    public suspend fun readSettings(): GateResult<Set<GateSetting>>

    /** Replaces the controller's settings block with [settings]. */
    public suspend fun applySettings(settings: Set<GateSetting>): GateResult<Unit>

    /** Runs an opt-in maintenance diagnostic. */
    public suspend fun runDiagnostic(diagnostic: GateDiagnostic): GateResult<Unit>

    /** Runs the opt-in controller reset operation. */
    public suspend fun reset(): GateResult<Unit>
}
