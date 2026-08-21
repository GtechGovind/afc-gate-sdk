package com.qurkos.gate.controlpanel.app

import com.qurkos.gate.sdk.Gate
import com.qurkos.gate.sdk.GateCapability
import com.qurkos.gate.sdk.GateClock
import com.qurkos.gate.sdk.GateConnectionState
import com.qurkos.gate.sdk.GateDescriptor
import com.qurkos.gate.sdk.GateDiagnostic
import com.qurkos.gate.sdk.GateDoorTiming
import com.qurkos.gate.sdk.GateEmergencyState
import com.qurkos.gate.sdk.GateEvent
import com.qurkos.gate.sdk.GateFirmwareInfo
import com.qurkos.gate.sdk.GateMechanism
import com.qurkos.gate.sdk.GatePassMode
import com.qurkos.gate.sdk.GatePassageRequest
import com.qurkos.gate.sdk.GatePowerStatus
import com.qurkos.gate.sdk.GateResult
import com.qurkos.gate.sdk.GateSafetyRegion
import com.qurkos.gate.sdk.GateSensorId
import com.qurkos.gate.sdk.GateSensorStatus
import com.qurkos.gate.sdk.GateSetting
import com.qurkos.gate.sdk.GateSite
import com.qurkos.gate.sdk.GateStandbyPolicy
import com.qurkos.gate.sdk.GateStatus
import com.qurkos.gate.sdk.GateVendor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.datetime.LocalDateTime
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** In-memory [Gate] used to prove that the control panel routes only through the physical SDK contract. */
internal class FakeGate(
    private val connectResult: GateResult<Unit> = GateResult.Success(Unit),
    var passModeResult: GateResult<Unit> = GateResult.Success(Unit),
) : Gate {
    override val descriptor = GateDescriptor(GateVendor.PULOON, GateMechanism.SECTOR, GateSite.GENERIC)
    override val capabilities: Set<GateCapability> = GateCapability.entries.toSet()
    private val mutableConnection = MutableStateFlow(GateConnectionState.DISCONNECTED)
    private val mutableStatus = MutableStateFlow<GateStatus?>(sampleStatus())
    private val mutableEvents = MutableSharedFlow<GateEvent>(extraBufferCapacity = 8)
    override val connectionState: StateFlow<GateConnectionState> = mutableConnection
    override val status: StateFlow<GateStatus?> = mutableStatus
    override val events: Flow<GateEvent> = mutableEvents.asSharedFlow()

    val passageRequests = mutableListOf<GatePassageRequest>()
    val emergencyRequests = mutableListOf<Boolean>()
    val diagnostics = mutableListOf<GateDiagnostic>()
    val passModes = mutableListOf<GatePassMode>()
    val safetyRegions = mutableListOf<GateSafetyRegion>()
    val clockWrites = mutableListOf<GateClock>()
    val upsDelays = mutableListOf<Int>()
    val standbyWrites = mutableListOf<GateStandbyPolicy>()
    val timingWrites = mutableListOf<GateDoorTiming>()
    val settingWrites = mutableListOf<Set<GateSetting>>()
    var sensorReads = 0
    var statusReads = 0
    var disconnectCalls = 0
    var initializeCalls = 0
    var clearCounterCalls = 0
    var resetCalls = 0

    fun emitEvent(event: GateEvent) {
        check(mutableEvents.tryEmit(event)) { "Fake event buffer is full" }
    }

    override suspend fun connect(): GateResult<Unit> {
        mutableConnection.value =
            if (connectResult is GateResult.Success) GateConnectionState.CONNECTED else GateConnectionState.CONNECTING
        if (connectResult is GateResult.Success) statusReads += 1
        return connectResult
    }

    override suspend fun disconnect(): GateResult<Unit> {
        disconnectCalls += 1
        mutableConnection.value = GateConnectionState.DISCONNECTED
        return GateResult.Success(Unit)
    }

    override suspend fun allowPassage(request: GatePassageRequest): GateResult<Unit> {
        passageRequests += request
        return GateResult.Success(Unit)
    }

    override suspend fun setEmergency(enabled: Boolean): GateResult<Unit> {
        emergencyRequests += enabled
        mutableStatus.value =
            mutableStatus.value?.copy(
                emergency = if (enabled) GateEmergencyState.ECU else GateEmergencyState.INACTIVE,
            )
        return GateResult.Success(Unit)
    }

    override suspend fun initialize(): GateResult<Unit> {
        initializeCalls += 1
        return GateResult.Success(Unit)
    }

    override suspend fun firmware(): GateResult<GateFirmwareInfo> =
        GateResult.Success(GateFirmwareInfo(version = "9.9.9", model = "Test GCU"))

    override suspend fun refreshStatus(): GateResult<GateStatus> {
        statusReads += 1
        return GateResult.Success(requireNotNull(mutableStatus.value))
    }

    override suspend fun setPassMode(mode: GatePassMode): GateResult<Unit> {
        passModes += mode
        return passModeResult
    }

    override suspend fun setSafetyRegion(region: GateSafetyRegion): GateResult<Unit> {
        safetyRegions += region
        return GateResult.Success(Unit)
    }

    override suspend fun clearPassageCounters(): GateResult<Unit> {
        clearCounterCalls += 1
        return GateResult.Success(Unit)
    }

    override suspend fun readSensors(): GateResult<GateSensorStatus> {
        sensorReads += 1
        return GateResult.Success(requireNotNull(mutableStatus.value).sensors)
    }

    override suspend fun readClock(): GateResult<GateClock> = GateResult.Success(GateClock(LocalDateTime(2026, 8, 20, 10, 30)))

    override suspend fun setClock(clock: GateClock): GateResult<Unit> {
        clockWrites += clock
        return GateResult.Success(Unit)
    }

    override suspend fun setUpsShutdownDelaySeconds(seconds: Int): GateResult<Unit> {
        upsDelays += seconds
        return GateResult.Success(Unit)
    }

    override suspend fun readStandbyPolicy(): GateResult<GateStandbyPolicy> =
        GateResult.Success(GateStandbyPolicy(255.seconds, GatePassMode.OUT_OF_SERVICE))

    override suspend fun setStandbyPolicy(policy: GateStandbyPolicy): GateResult<Unit> {
        standbyWrites += policy
        return GateResult.Success(Unit)
    }

    override suspend fun readDoorTiming(): GateResult<GateDoorTiming> = GateResult.Success(GateDoorTiming(1.seconds, 1.seconds))

    override suspend fun setDoorTiming(timing: GateDoorTiming): GateResult<Unit> {
        timingWrites += timing
        return GateResult.Success(Unit)
    }

    override suspend fun readSettings(): GateResult<Set<GateSetting>> = GateResult.Success(emptySet())

    override suspend fun applySettings(settings: Set<GateSetting>): GateResult<Unit> {
        settingWrites += settings
        return GateResult.Success(Unit)
    }

    override suspend fun runDiagnostic(diagnostic: GateDiagnostic): GateResult<Unit> {
        diagnostics += diagnostic
        return GateResult.Success(Unit)
    }

    override suspend fun reset(): GateResult<Unit> {
        resetCalls += 1
        return GateResult.Success(Unit)
    }

    fun publishStatus(value: GateStatus) {
        mutableStatus.value = value
    }

    companion object {
        fun sampleStatus(
            activeSensors: Set<GateSensorId> = setOf(GateSensorId(2), GateSensorId(15)),
            sensorFault: Boolean = false,
        ): GateStatus =
            GateStatus(
                passMode = GatePassMode.CONTROLLED_BOTH,
                entryCount = 7,
                exitCount = 5,
                emergency = GateEmergencyState.INACTIVE,
                sensors =
                    GateSensorStatus(
                        activeSensors,
                        sensorFault,
                        faulted = if (sensorFault) setOf(GateSensorId(4)) else emptySet(),
                    ),
                power = GatePowerStatus(upsPresent = true, summary = "Good"),
                observedAt = Instant.fromEpochMilliseconds(1_000),
            )
    }
}
