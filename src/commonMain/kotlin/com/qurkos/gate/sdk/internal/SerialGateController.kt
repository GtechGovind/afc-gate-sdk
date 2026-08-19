package com.qurkos.gate.sdk.internal

import com.qurkos.gate.sdk.Gate
import com.qurkos.gate.sdk.GateCapability
import com.qurkos.gate.sdk.GateClock
import com.qurkos.gate.sdk.GateConnectionState
import com.qurkos.gate.sdk.GateDeviceConfig
import com.qurkos.gate.sdk.GateDiagnostic
import com.qurkos.gate.sdk.GateDoorTiming
import com.qurkos.gate.sdk.GateError
import com.qurkos.gate.sdk.GateEvent
import com.qurkos.gate.sdk.GateFirmwareInfo
import com.qurkos.gate.sdk.GatePassMode
import com.qurkos.gate.sdk.GatePassageRequest
import com.qurkos.gate.sdk.GateResult
import com.qurkos.gate.sdk.GateSafetyRegion
import com.qurkos.gate.sdk.GateSensorStatus
import com.qurkos.gate.sdk.GateSetting
import com.qurkos.gate.sdk.GateStandbyPolicy
import com.qurkos.gate.sdk.GateStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sole implementation of the public [Gate] contract.
 *
 * It performs capability checks, serializes operation creation, maps response variants, and owns normalized status/event
 * state. Vendor semantics are delegated to [GateProtocolAdapter], while I/O lifecycle is delegated to [SerialSession].
 */
internal class SerialGateController(
    config: GateDeviceConfig,
    private val adapter: GateProtocolAdapter,
    transport: SerialTransport,
    dispatcher: CoroutineDispatcher,
) : Gate {
    private val mutableStatus = MutableStateFlow<GateStatus?>(null)
    private val mutableEvents = MutableSharedFlow<GateEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    private val operationMutex = Mutex()
    private val session =
        SerialSession(
            serialConfig = config.serial,
            runtime = config.runtime,
            adapter = adapter,
            transport = transport,
            eventSink = mutableEvents::tryEmit,
            dispatcher = dispatcher,
        )

    override val descriptor = adapter.descriptor
    override val capabilities: Set<GateCapability> = adapter.capabilities
    override val connectionState: StateFlow<GateConnectionState> = session.connectionState
    override val status: StateFlow<GateStatus?> = mutableStatus.asStateFlow()
    override val events: Flow<GateEvent> = mutableEvents.asSharedFlow()

    /** Connects the shared session and starts optional monitoring exactly once. */
    override suspend fun connect(): GateResult<Unit> {
        val result = session.connect()
        session.startMonitoring { refreshStatus() }
        return result
    }

    /** Disconnects the session and invalidates the last status snapshot. */
    override suspend fun disconnect(): GateResult<Unit> {
        val result = session.disconnect()
        mutableStatus.value = null
        return result
    }

    /** Resolves request-specific capabilities before delegating passage authorization. */
    override suspend fun allowPassage(request: GatePassageRequest): GateResult<Unit> {
        val required =
            buildSet {
                add(GateCapability.PASSAGE)
                if (request.invalidTicket) add(GateCapability.INVALID_TICKET)
                if (request.passengerCount > 1) add(GateCapability.MULTI_PERSON_PASSAGE)
                if (request.lampColor != com.qurkos.gate.sdk.GateLampColor.GREEN) add(GateCapability.PASSAGE_LAMP)
            }
        val unsupported = required.firstOrNull { it !in capabilities }
        if (unsupported != null) return GateResult.Failure(GateError.UnsupportedCapability(unsupported))
        return acknowledge(GateOperation.Passage(request), GateCapability.PASSAGE)
    }

    /** Delegates emergency state mutation after capability validation. */
    override suspend fun setEmergency(enabled: Boolean): GateResult<Unit> =
        acknowledge(GateOperation.Emergency(enabled), GateCapability.EMERGENCY)

    /** Delegates controller initialization after capability validation. */
    override suspend fun initialize(): GateResult<Unit> = acknowledge(GateOperation.Initialize, GateCapability.INITIALIZE)

    /** Executes a firmware read and enforces its normalized response type. */
    override suspend fun firmware(): GateResult<GateFirmwareInfo> =
        execute(GateOperation.Firmware, GateCapability.FIRMWARE).mapExpected { response ->
            (response as? GateResponse.Firmware)?.value
        }

    /** Executes a status read and atomically publishes successful results. */
    override suspend fun refreshStatus(): GateResult<GateStatus> {
        val result =
            execute(GateOperation.Status, GateCapability.STATUS).mapExpected { response ->
                (response as? GateResponse.Status)?.value
            }
        if (result is GateResult.Success) {
            mutableStatus.value = result.value
            mutableEvents.tryEmit(GateEvent.StatusChanged(result.value))
        }
        return result
    }

    /** Delegates a passage-mode write after capability validation. */
    override suspend fun setPassMode(mode: GatePassMode): GateResult<Unit> =
        acknowledge(GateOperation.SetPassMode(mode), GateCapability.PASS_MODE)

    /** Delegates a safety-region write; adapter validation supplies mechanism-specific limits. */
    override suspend fun setSafetyRegion(region: GateSafetyRegion): GateResult<Unit> =
        acknowledge(GateOperation.SetSafetyRegion(region), GateCapability.SAFETY_REGION)

    /** Delegates the non-retryable passage-counter reset. */
    override suspend fun clearPassageCounters(): GateResult<Unit> =
        acknowledge(GateOperation.ClearPassageCounters, GateCapability.PASSAGE_COUNTERS)

    /** Executes a sensor read and enforces its normalized response type. */
    override suspend fun readSensors(): GateResult<GateSensorStatus> =
        execute(GateOperation.Sensors, GateCapability.SENSORS).mapExpected { response ->
            (response as? GateResponse.Sensors)?.value
        }

    /** Executes a controller-clock read. */
    override suspend fun readClock(): GateResult<GateClock> =
        execute(GateOperation.ReadClock, GateCapability.CLOCK).mapExpected { response ->
            (response as? GateResponse.Clock)?.value
        }

    /** Delegates a non-retryable controller-clock write. */
    override suspend fun setClock(clock: GateClock): GateResult<Unit> = acknowledge(GateOperation.SetClock(clock), GateCapability.CLOCK)

    /** Delegates UPS delay validation and mutation to the selected adapter. */
    override suspend fun setUpsShutdownDelaySeconds(seconds: Int): GateResult<Unit> =
        acknowledge(GateOperation.SetUpsShutdownDelay(seconds), GateCapability.UPS_SHUTDOWN)

    /** Executes a standby-policy read. */
    override suspend fun readStandbyPolicy(): GateResult<GateStandbyPolicy> =
        execute(GateOperation.ReadStandbyPolicy, GateCapability.STANDBY).mapExpected { response ->
            (response as? GateResponse.StandbyPolicy)?.value
        }

    /** Delegates a non-retryable standby-policy write. */
    override suspend fun setStandbyPolicy(policy: GateStandbyPolicy): GateResult<Unit> =
        acknowledge(GateOperation.SetStandbyPolicy(policy), GateCapability.STANDBY)

    /** Executes a door-timing read. */
    override suspend fun readDoorTiming(): GateResult<GateDoorTiming> =
        execute(GateOperation.ReadDoorTiming, GateCapability.DOOR_TIMING).mapExpected { response ->
            (response as? GateResponse.DoorTiming)?.value
        }

    /** Delegates a non-retryable door-timing write. */
    override suspend fun setDoorTiming(timing: GateDoorTiming): GateResult<Unit> =
        acknowledge(GateOperation.SetDoorTiming(timing), GateCapability.DOOR_TIMING)

    /** Executes a complete typed settings-block read. */
    override suspend fun readSettings(): GateResult<Set<GateSetting>> =
        execute(GateOperation.ReadSettings, GateCapability.SETTINGS).mapExpected { response ->
            (response as? GateResponse.Settings)?.value
        }

    /** Delegates validation and a non-retryable complete settings-block write. */
    override suspend fun applySettings(settings: Set<GateSetting>): GateResult<Unit> =
        acknowledge(GateOperation.ApplySettings(settings.toSet()), GateCapability.SETTINGS)

    /** Delegates an explicitly enabled, non-retryable maintenance diagnostic. */
    override suspend fun runDiagnostic(diagnostic: GateDiagnostic): GateResult<Unit> =
        acknowledge(GateOperation.Diagnostic(diagnostic), GateCapability.DIAGNOSTICS)

    /** Delegates an explicitly enabled, non-retryable controller reset. */
    override suspend fun reset(): GateResult<Unit> = acknowledge(GateOperation.Reset, GateCapability.RESET)

    /** Converts the normalized acknowledgement response into `GateResult<Unit>`. */
    private suspend fun acknowledge(
        operation: GateOperation,
        capability: GateCapability,
    ): GateResult<Unit> =
        execute(operation, capability).mapExpected { response ->
            if (response is GateResponse.Acknowledged) Unit else null
        }

    /** Serializes adapter sequence allocation and session execution for one operation. */
    private suspend fun execute(
        operation: GateOperation,
        capability: GateCapability,
    ): GateResult<GateResponse> =
        operationMutex.withLock {
            if (capability !in capabilities) {
                return@withLock GateResult.Failure(GateError.UnsupportedCapability(capability))
            }
            when (val transaction = adapter.transaction(operation)) {
                is GateResult.Success -> session.transact(transaction.value)
                is GateResult.Failure -> transaction
            }
        }

    /** Preserves failures and rejects a successful response of the wrong normalized variant. */
    private fun <T> GateResult<GateResponse>.mapExpected(extract: (GateResponse) -> T?): GateResult<T> =
        when (this) {
            is GateResult.Failure -> this
            is GateResult.Success -> {
                val value = extract(value)
                if (value == null) {
                    GateResult.Failure(GateError.Protocol("Unexpected response type"))
                } else {
                    GateResult.Success(value)
                }
            }
        }

    private companion object {
        const val EVENT_BUFFER_CAPACITY = 64
    }
}
