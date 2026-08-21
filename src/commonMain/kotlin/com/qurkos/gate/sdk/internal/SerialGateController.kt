package com.qurkos.gate.sdk.internal

import com.qurkos.gate.sdk.Gate
import com.qurkos.gate.sdk.GateCapability
import com.qurkos.gate.sdk.GateClock
import com.qurkos.gate.sdk.GateCommand
import com.qurkos.gate.sdk.GateCommandOutcome
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
import kotlin.time.Clock
import kotlin.time.TimeSource

/**
 * Sole implementation of the public [Gate] contract.
 *
 * It performs capability checks, serializes operation creation, maps response variants, and owns normalized status/event
 * state. Vendor semantics are delegated to [GateProtocolAdapter], while I/O lifecycle is delegated to [SerialSession].
 */
@Suppress("TooManyFunctions") // The Gate contract is intentionally implemented in one serialized controller.
internal class SerialGateController(
    config: GateDeviceConfig,
    private val adapter: GateProtocolAdapter,
    transport: SerialTransport,
    dispatcher: CoroutineDispatcher,
) : Gate {
    private val mutableStatus = MutableStateFlow<GateStatus?>(null)
    private val mutableConnectionState = MutableStateFlow(GateConnectionState.DISCONNECTED)
    private val mutableEvents = MutableSharedFlow<GateEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    private val operationMutex = Mutex()
    private var traceSequence = 0L
    private var normalOpenMode = config.hardware.normalOpen
    private val session =
        SerialSession(
            serialConfig = config.serial,
            runtime = config.runtime,
            adapter = adapter,
            transport = transport,
            eventSink = ::onSessionEvent,
            dispatcher = dispatcher,
        )

    override val descriptor = adapter.descriptor
    override val capabilities: Set<GateCapability> = adapter.capabilities
    override val connectionState: StateFlow<GateConnectionState> = mutableConnectionState.asStateFlow()
    override val status: StateFlow<GateStatus?> = mutableStatus.asStateFlow()
    override val events: Flow<GateEvent> = mutableEvents.asSharedFlow()

    /** Connects the shared session and starts optional monitoring exactly once. */
    override suspend fun connect(): GateResult<Unit> {
        val opened =
            trace(
                command = GateCommand.CONNECT,
                requestDetail = "Open serial session",
                successDetail = { "Serial session connected" },
                block = session::connect,
            )
        if (opened is GateResult.Failure) return opened
        val verified = refreshStatus()
        if (verified is GateResult.Failure) {
            session.disconnect()
            return verified
        }
        session.startMonitoring { refreshStatus() }
        return GateResult.Success(Unit)
    }

    /** Disconnects the session and invalidates the last status snapshot. */
    override suspend fun disconnect(): GateResult<Unit> {
        val result =
            trace(
                command = GateCommand.DISCONNECT,
                requestDetail = "Close serial session",
                successDetail = { "Serial session disconnected" },
                block = session::disconnect,
            )
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
            publishConnection(GateConnectionState.CONNECTED)
            mutableEvents.tryEmit(GateEvent.StatusChanged(result.value))
        }
        return result
    }

    /** Delegates a passage-mode write after capability validation. */
    override suspend fun setPassMode(mode: GatePassMode): GateResult<Unit> =
        acknowledge(GateOperation.SetPassMode(mode, normalOpenMode), GateCapability.PASS_MODE)

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
        acknowledge(GateOperation.SetStandbyPolicy(policy, normalOpenMode), GateCapability.STANDBY)

    /** Executes a door-timing read. */
    override suspend fun readDoorTiming(): GateResult<GateDoorTiming> =
        execute(GateOperation.ReadDoorTiming, GateCapability.DOOR_TIMING).mapExpected { response ->
            (response as? GateResponse.DoorTiming)?.value
        }

    /** Delegates a non-retryable door-timing write. */
    override suspend fun setDoorTiming(timing: GateDoorTiming): GateResult<Unit> =
        acknowledge(GateOperation.SetDoorTiming(timing), GateCapability.DOOR_TIMING)

    /** Executes a complete typed settings-block read. */
    override suspend fun readSettings(): GateResult<Set<GateSetting>> {
        val result =
            execute(GateOperation.ReadSettings, GateCapability.SETTINGS).mapExpected { response ->
                (response as? GateResponse.Settings)?.value
            }
        if (result is GateResult.Success) updateNormalOpen(result.value)
        return result
    }

    /** Delegates validation and a non-retryable complete settings-block write. */
    override suspend fun applySettings(settings: Set<GateSetting>): GateResult<Unit> {
        val stableSettings = settings.toSet()
        val result = acknowledge(GateOperation.ApplySettings(stableSettings), GateCapability.SETTINGS)
        if (result is GateResult.Success) updateNormalOpen(stableSettings)
        return result
    }

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
            trace(
                command = operation.command,
                requestDetail = operation.requestDetail,
                successDetail = { it.responseDetail() },
            ) {
                if (capability !in capabilities) {
                    return@trace GateResult.Failure(GateError.UnsupportedCapability(capability))
                }
                when (val transaction = adapter.transaction(operation)) {
                    is GateResult.Success -> session.transact(transaction.value)
                    is GateResult.Failure -> transaction
                }
            }
        }

    /** Emits a bounded, observational TX/RX pair around one serialized SDK operation. */
    private suspend fun <T> trace(
        command: GateCommand,
        requestDetail: String,
        successDetail: (T) -> String,
        block: suspend () -> GateResult<T>,
    ): GateResult<T> {
        val sequence = ++traceSequence
        val started = TimeSource.Monotonic.markNow()
        mutableEvents.emit(GateEvent.CommandSent(sequence, command, requestDetail, Clock.System.now()))
        val result = block()
        val outcome =
            when (result) {
                is GateResult.Success -> GateCommandOutcome.SUCCESS
                is GateResult.Failure -> GateCommandOutcome.FAILURE
            }
        val detail =
            when (result) {
                is GateResult.Success -> successDetail(result.value)
                is GateResult.Failure -> result.error.traceDetail()
            }
        mutableEvents.emit(
            GateEvent.ResponseReceived(
                sequence = sequence,
                command = command,
                outcome = outcome,
                detail = detail,
                elapsed = started.elapsedNow(),
                at = Clock.System.now(),
            ),
        )
        return result
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

    private val GateOperation.command: GateCommand
        get() =
            when (this) {
                GateOperation.Firmware -> GateCommand.FIRMWARE
                is GateOperation.Passage -> GateCommand.PASSAGE
                is GateOperation.Emergency -> GateCommand.EMERGENCY
                GateOperation.Initialize -> GateCommand.INITIALIZE
                GateOperation.Status -> GateCommand.STATUS
                is GateOperation.SetPassMode -> GateCommand.SET_PASS_MODE
                is GateOperation.SetSafetyRegion -> GateCommand.SET_SAFETY_REGION
                GateOperation.ClearPassageCounters -> GateCommand.CLEAR_PASSAGE_COUNTERS
                GateOperation.Sensors -> GateCommand.SENSORS
                GateOperation.ReadClock -> GateCommand.READ_CLOCK
                is GateOperation.SetClock -> GateCommand.SET_CLOCK
                is GateOperation.SetUpsShutdownDelay -> GateCommand.SET_UPS_SHUTDOWN_DELAY
                GateOperation.ReadStandbyPolicy -> GateCommand.READ_STANDBY_POLICY
                is GateOperation.SetStandbyPolicy -> GateCommand.SET_STANDBY_POLICY
                GateOperation.ReadDoorTiming -> GateCommand.READ_DOOR_TIMING
                is GateOperation.SetDoorTiming -> GateCommand.SET_DOOR_TIMING
                GateOperation.ReadSettings -> GateCommand.READ_SETTINGS
                is GateOperation.ApplySettings -> GateCommand.APPLY_SETTINGS
                is GateOperation.Diagnostic -> GateCommand.DIAGNOSTIC
                GateOperation.Reset -> GateCommand.RESET
            }

    private val GateOperation.requestDetail: String
        get() =
            when (this) {
                GateOperation.Firmware -> "Read firmware identity"
                is GateOperation.Passage ->
                    "${request.direction.name.lowercase().replaceFirstChar(Char::uppercase)} · " +
                        "${request.passengerCount} passenger${if (request.passengerCount == 1) "" else "s"} · " +
                        "lamp ${request.lampColor.name.lowercase()} · " +
                        if (request.invalidTicket) "reject" else "authorize"
                is GateOperation.Emergency -> if (enabled) "Engage emergency release" else "Clear emergency release"
                GateOperation.Initialize -> "Initialize controller"
                GateOperation.Status -> "Read normalized status"
                is GateOperation.SetPassMode -> "Set passage mode to ${mode.name.displayName()}"
                is GateOperation.SetSafetyRegion -> "Select safety region ${region.number}"
                GateOperation.ClearPassageCounters -> "Clear entry and exit counters"
                GateOperation.Sensors -> "Read physical sensor inputs"
                GateOperation.ReadClock -> "Read controller RTC"
                is GateOperation.SetClock -> "Synchronize controller RTC to ${clock.dateTime}"
                is GateOperation.SetUpsShutdownDelay -> "Set UPS shutdown delay to $seconds s"
                GateOperation.ReadStandbyPolicy -> "Read standby policy"
                is GateOperation.SetStandbyPolicy ->
                    "Set standby to ${policy.timeout.inWholeSeconds} s · ${policy.passMode.name.displayName()}"
                GateOperation.ReadDoorTiming -> "Read door timing"
                is GateOperation.SetDoorTiming ->
                    "Set opening ${timing.openingDelay.inWholeMilliseconds} ms · " +
                        "closing ${timing.closingDelay.inWholeMilliseconds} ms"
                GateOperation.ReadSettings -> "Read typed settings block"
                is GateOperation.ApplySettings -> "Apply settings ${settings.traceSummary()}"
                is GateOperation.Diagnostic -> "Run ${diagnostic.traceName()} diagnostic"
                GateOperation.Reset -> "Reset controller"
            }

    private fun GateResponse.responseDetail(): String =
        when (this) {
            GateResponse.Acknowledged -> "Controller acknowledged"
            is GateResponse.Firmware -> "Firmware ${value.version} received"
            is GateResponse.Status ->
                "Status passMode=${value.passMode.name} entry=${value.entryCount} exit=${value.exitCount} " +
                    "emergency=${value.emergency.name} sensorFault=${value.sensors.hasFault}"
            is GateResponse.Sensors ->
                "Sensors active=${value.active.map { it.number }.sorted()} " +
                    "faulted=${value.faulted.map { it.number }.sorted()} hasFault=${value.hasFault}"
            is GateResponse.Clock -> "Controller RTC ${value.dateTime}"
            is GateResponse.StandbyPolicy ->
                "Standby timeout=${value.timeout.inWholeSeconds}s passMode=${value.passMode.name}"
            is GateResponse.DoorTiming ->
                "Door timing opening=${value.openingDelay.inWholeMilliseconds}ms " +
                    "closing=${value.closingDelay.inWholeMilliseconds}ms"
            is GateResponse.Settings -> "Settings ${value.traceSummary()}"
        }

    private fun Set<GateSetting>.traceSummary(): String =
        map { setting ->
            when (setting) {
                is GateSetting.NoEntryTimeout -> "noEntry=${setting.value.inWholeSeconds}s"
                is GateSetting.NormalOpenMode -> "normalOpen=${setting.enabled}"
                is GateSetting.HurryUpLevel -> "hurryUp=${setting.level}"
                is GateSetting.TagTimeoutFromLastTag -> "tagTimeoutFromLast=${setting.enabled}"
                is GateSetting.TailingSensitivity -> "tailing=${setting.level}"
                is GateSetting.BuzzerTimeoutUnits -> "buzzer=${setting.value}"
                is GateSetting.SafetyRegionTimeout ->
                    "safetyTimeout=${setting.value?.inWholeSeconds?.let { "${it}s" } ?: "disabled"}"
                is GateSetting.ChildDetection -> "childDetection=${setting.level}"
            }
        }.sorted().joinToString(prefix = "[", postfix = "]")

    private fun GateDiagnostic.traceName(): String =
        when (this) {
            is GateDiagnostic.Door -> "door ${action.name.lowercase()}"
            is GateDiagnostic.EndDisplay -> "${color.name.lowercase()} end display ${enabled.onOff()}"
            is GateDiagnostic.Indicator -> "${color.name.lowercase()} indicator ${enabled.onOff()}"
            is GateDiagnostic.Buzzer -> "buzzer $index ${enabled.onOff()}"
            is GateDiagnostic.ReturnCupLamp -> "return-cup lamp ${enabled.onOff()}"
        }

    private fun Boolean.onOff(): String = if (this) "on" else "off"

    private fun updateNormalOpen(settings: Set<GateSetting>) {
        settings.filterIsInstance<GateSetting.NormalOpenMode>().singleOrNull()?.let { normalOpenMode = it.enabled }
    }

    private fun GateError.traceDetail(): String =
        when (this) {
            GateError.NotConnected -> "Gate is not connected"
            is GateError.Timeout -> "Response timeout"
            is GateError.Transport -> message
            is GateError.Protocol -> message
            is GateError.Device -> message ?: "Controller rejected request"
            is GateError.InvalidRequest -> message
            is GateError.UnsupportedCapability -> "Unsupported capability: ${capability.name.displayName()}"
            is GateError.UnsupportedVendor -> "Unsupported vendor: ${vendor.name.displayName()}"
        }

    private fun String.displayName(): String =
        lowercase()
            .split('_')
            .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    /** Keeps transport-open state private until a valid controller status has completed the handshake. */
    private fun onSessionEvent(event: GateEvent) {
        if (event is GateEvent.ConnectionChanged) {
            if (event.state != GateConnectionState.CONNECTED) publishConnection(event.state)
        } else {
            mutableEvents.tryEmit(event)
        }
    }

    private fun publishConnection(state: GateConnectionState) {
        if (mutableConnectionState.value == state) return
        mutableConnectionState.value = state
        mutableEvents.tryEmit(GateEvent.ConnectionChanged(state))
    }

    private companion object {
        const val EVENT_BUFFER_CAPACITY = 64
    }
}
