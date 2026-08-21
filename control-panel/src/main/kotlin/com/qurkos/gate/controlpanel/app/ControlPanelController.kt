package com.qurkos.gate.controlpanel.app

import com.qurkos.gate.controlpanel.ui.model.ConnectionHealth
import com.qurkos.gate.controlpanel.ui.model.ControlPanelCallbacks
import com.qurkos.gate.controlpanel.ui.model.ControlPanelDestination
import com.qurkos.gate.controlpanel.ui.model.ControlPanelUiState
import com.qurkos.gate.controlpanel.ui.model.DiagnosticState
import com.qurkos.gate.controlpanel.ui.model.EventCategory
import com.qurkos.gate.controlpanel.ui.model.EventSeverity
import com.qurkos.gate.controlpanel.ui.model.FlapPosition
import com.qurkos.gate.controlpanel.ui.model.GateConfigurationUi
import com.qurkos.gate.controlpanel.ui.model.GateEventUi
import com.qurkos.gate.controlpanel.ui.model.GateTrafficUi
import com.qurkos.gate.controlpanel.ui.model.IndicatorLamp
import com.qurkos.gate.controlpanel.ui.model.PassageDirection
import com.qurkos.gate.controlpanel.ui.model.PuloonInputRules
import com.qurkos.gate.controlpanel.ui.model.SensorGroup
import com.qurkos.gate.controlpanel.ui.model.SerialPortOptionUi
import com.qurkos.gate.controlpanel.ui.model.TrafficDirection
import com.qurkos.gate.controlpanel.ui.model.diagnosticsFor
import com.qurkos.gate.controlpanel.ui.model.gateSensors
import com.qurkos.gate.controlpanel.ui.model.hasValidInputs
import com.qurkos.gate.sdk.Gate
import com.qurkos.gate.sdk.GateCapability
import com.qurkos.gate.sdk.GateClock
import com.qurkos.gate.sdk.GateCommandOutcome
import com.qurkos.gate.sdk.GateConnectionState
import com.qurkos.gate.sdk.GateDeviceConfig
import com.qurkos.gate.sdk.GateDiagnostic
import com.qurkos.gate.sdk.GateDirection
import com.qurkos.gate.sdk.GateDoorTestAction
import com.qurkos.gate.sdk.GateDoorTiming
import com.qurkos.gate.sdk.GateError
import com.qurkos.gate.sdk.GateEvent
import com.qurkos.gate.sdk.GateHardwareProfile
import com.qurkos.gate.sdk.GateLampColor
import com.qurkos.gate.sdk.GateMechanism
import com.qurkos.gate.sdk.GateModule
import com.qurkos.gate.sdk.GatePassMode
import com.qurkos.gate.sdk.GateProtocolRevision
import com.qurkos.gate.sdk.GateResult
import com.qurkos.gate.sdk.GateRuntimeOptions
import com.qurkos.gate.sdk.GateSafetyRegion
import com.qurkos.gate.sdk.GateSdk
import com.qurkos.gate.sdk.GateSetting
import com.qurkos.gate.sdk.GateSite
import com.qurkos.gate.sdk.GateStandbyPolicy
import com.qurkos.gate.sdk.GateSupport
import com.qurkos.gate.sdk.GateVendor
import com.qurkos.gate.sdk.ReconnectPolicy
import com.qurkos.gate.sdk.SerialConnectionConfig
import com.qurkos.gate.sdk.SerialParameters
import com.qurkos.gate.sdk.SerialPortInfo
import com.qurkos.gate.sdk.SerialPortName
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Owns one physical-gate control-panel window.
 *
 * Every actuator operation is routed to the public [Gate] contract and requires an explicit serial
 * connection. Visual motion begins only after the SDK confirms the command; sensors, counters,
 * emergency state, and passage mode are always mapped from validated controller status.
 */
@Suppress("TooManyFunctions", "LargeClass") // Implements the complete, intentionally centralized UI intent contract.
class ControlPanelController(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val gateFactory: (GateDeviceConfig) -> GateResult<Gate> = GateSdk::create,
    private val serialPortProvider: () -> GateResult<List<SerialPortInfo>> = GateSdk::serialPorts,
    private val eventLogExporter: (List<GateEventUi>) -> String? = ::exportEventLog,
    private val supportProvider: (GateDeviceConfig) -> GateResult<GateSupport> = GateSdk::support,
    private val logDirectoryProvider: () -> String = { ApplicationLogging.directory.toString() },
    private val logDirectoryOpener: () -> Unit = ApplicationLogging::openDirectory,
) : ControlPanelCallbacks,
    AutoCloseable {
    private val logger = ApplicationLogging.logger(ControlPanelController::class.java)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState =
        MutableStateFlow(
            ControlPanelUiState(logDirectory = runCatching(logDirectoryProvider).getOrDefault("Unavailable")),
        )
    private var hardwareGate: Gate? = null
    private var hardwareObservers: Job? = null
    private var motionJob: Job? = null
    private var eventSequence = 0L
    private var savedConfiguration = GateConfigurationUi()
    private var lastLoggedStatus: String? = null
    private var lastLoggedSensors: String? = null
    private var lastLoggedSupport: String? = null

    /** Immutable presentation state consumed by Compose. */
    val state: StateFlow<ControlPanelUiState> = mutableState.asStateFlow()

    init {
        mutableState.update {
            it.copy(
                connectionHealth = ConnectionHealth.DISCONNECTED,
                events =
                    listOf(
                        event(
                            "Hardware control ready",
                            "Connect a physical gate before sending commands",
                            EventSeverity.INFO,
                        ),
                    ),
            )
        }
        refreshSupport(savedConfiguration)
        onRefreshSerialPorts()
    }

    override fun onNavigate(destination: ControlPanelDestination) {
        mutableState.update { it.copy(destination = destination) }
    }

    override fun onAllowEntry() {
        val request = mutableState.value
        executeHardware("Entry authorized", { it.allowEntry(request.passagePassengerCount, request.passageLampColor.toLampColor()) }) {
            animatePassage(PassageDirection.ENTRY, request.passageLampColor.toLampColor().toIndicatorLamp())
        }
    }

    override fun onAllowExit() {
        val request = mutableState.value
        executeHardware("Exit authorized", { it.allowExit(request.passagePassengerCount, request.passageLampColor.toLampColor()) }) {
            animatePassage(PassageDirection.EXIT, request.passageLampColor.toLampColor().toIndicatorLamp())
        }
    }

    override fun onPassagePassengerCountChanged(value: Int) {
        mutableState.update { it.copy(passagePassengerCount = value.coerceIn(1, 99)) }
    }

    override fun onPassageLampColorChanged(value: String) {
        mutableState.update { it.copy(passageLampColor = value.toLampColor().name) }
    }

    override fun onRejectDirectionChanged(value: String) {
        val direction = if (value.equals("EXIT", ignoreCase = true)) "EXIT" else "ENTRY"
        mutableState.update { it.copy(rejectDirection = direction) }
    }

    override fun onReject() {
        val direction = if (mutableState.value.rejectDirection == "EXIT") GateDirection.EXIT else GateDirection.ENTRY
        executeHardware("Passage rejected", { it.rejectPassage(direction) }) {
            pulseRejectedLamp()
        }
    }

    override fun onEmergencyStop() {
        executeHardware(
            title = "Emergency release engaged",
            command = { it.setEmergency(true) },
            severity = EventSeverity.ERROR,
            category = EventCategory.EMERGENCY,
        ) {
            animateEmergency(enabled = true)
        }
    }

    override fun onEmergencyReset() {
        executeHardware(
            title = "Emergency release cleared",
            command = { it.setEmergency(false) },
            severity = EventSeverity.WARNING,
            category = EventCategory.EMERGENCY,
        ) {
            animateEmergency(enabled = false)
        }
    }

    override fun onConnect() {
        if (hardwareGate != null) return
        val gate = createGate(mutableState.value.configuration) ?: return
        lastLoggedStatus = null
        lastLoggedSensors = null
        hardwareGate = gate
        observe(gate)
        mutableState.update { it.copy(commandInProgress = true, transientMessage = null) }
        scope.launch {
            when (val result = gate.connect()) {
                is GateResult.Success -> {
                    refreshIdentity(gate)
                    gate.status.value?.let { status -> mutableState.update { it.withHardwareStatus(status) } }
                    if (GateCapability.SENSORS in gate.capabilities) {
                        when (val sensors = gate.readSensors()) {
                            is GateResult.Success -> mutableState.update { it.withSensorStatus(sensors.value) }
                            is GateResult.Failure -> Unit
                        }
                    }
                    mutableState.update {
                        it
                            .copy(commandInProgress = false)
                            .appendEvent(event("Gate connected", "GCU status handshake verified", EventSeverity.SUCCESS))
                    }
                }

                is GateResult.Failure -> {
                    gate.disconnect()
                    hardwareGate = null
                    hardwareObservers?.cancel()
                    mutableState.update {
                        it
                            .copy(
                                connectionHealth = ConnectionHealth.DISCONNECTED,
                                commandInProgress = false,
                                transientMessage = result.error.displayMessage(),
                                gateTwin = it.gateTwin.copy(connectionHealth = ConnectionHealth.DISCONNECTED, lamp = IndicatorLamp.OFF),
                            ).appendEvent(event("Connection failed", result.error.displayMessage(), EventSeverity.ERROR))
                    }
                }
            }
        }
    }

    override fun onDisconnect() {
        disconnectHardware()
    }

    override fun onSensorSelected(sensorId: Int) {
        mutableState.update { it.copy(gateTwin = it.gateTwin.copy(highlightedSensorId = sensorId)) }
    }

    override fun onSensorGroupFilterChanged(group: SensorGroup?) {
        mutableState.update { it.copy(sensorGroupFilter = group) }
    }

    override fun onConfigurationChanged(configuration: GateConfigurationUi) {
        val current = mutableState.value.configuration
        if (hardwareGate != null && current.connectionFacts() != configuration.connectionFacts()) {
            showMessage("Disconnect the gate before changing its connection or hardware profile")
            return
        }
        val normalized = configuration.normalizedProfile().copy(hasUnsavedChanges = true)
        mutableState.update { it.copy(configuration = normalized) }
        refreshSupport(normalized)
    }

    override fun onRefreshSerialPorts() {
        when (val result = serialPortProvider()) {
            is GateResult.Success -> {
                logger.info(
                    "serialDiscovery count=${result.value.size} ports=" +
                        result.value
                            .map { "${it.name.value}:${it.description.orEmpty()}" }
                            .sorted()
                            .toString()
                            .logValue(),
                )
                mutableState.update { state ->
                    state.copy(
                        availableSerialPorts =
                            result.value.map { port ->
                                SerialPortOptionUi(port.name.value, port.description)
                            },
                        serialPortDiscoveryError = null,
                    )
                }
            }

            is GateResult.Failure -> {
                logger.warning("serialDiscovery failed detail=${result.error.displayMessage().logValue()}")
                mutableState.update {
                    it.copy(
                        availableSerialPorts = emptyList(),
                        serialPortDiscoveryError = result.error.displayMessage(),
                    )
                }
            }
        }
    }

    override fun onSaveConfiguration() {
        val gate = hardwareGate
        if (gate == null || mutableState.value.connectionHealth != ConnectionHealth.CONNECTED) {
            mutableState.update {
                it
                    .copy(configuration = it.configuration.copy(hasUnsavedChanges = false))
                    .appendEvent(
                        event(
                            "Configuration saved locally",
                            "Connect the gate to apply controller settings",
                            EventSeverity.INFO,
                            EventCategory.CONFIGURATION,
                        ),
                    )
            }
            savedConfiguration = mutableState.value.configuration
            return
        }
        val configuration = mutableState.value.configuration
        mutableState.update { it.copy(commandInProgress = true, transientMessage = null) }
        scope.launch {
            when (val result = applyControllerConfiguration(gate, configuration)) {
                is GateResult.Success -> {
                    savedConfiguration = configuration.copy(hasUnsavedChanges = false)
                    mutableState.update {
                        it
                            .copy(
                                commandInProgress = false,
                                configuration = configuration.copy(hasUnsavedChanges = false),
                            ).appendEvent(
                                event(
                                    "Configuration applied",
                                    "Controller confirmed all settings",
                                    EventSeverity.SUCCESS,
                                    EventCategory.CONFIGURATION,
                                ),
                            )
                    }
                }

                is GateResult.Failure ->
                    mutableState.update {
                        it
                            .copy(commandInProgress = false, transientMessage = result.error.displayMessage())
                            .appendEvent(
                                event(
                                    "Configuration failed",
                                    result.error.displayMessage(),
                                    EventSeverity.ERROR,
                                    EventCategory.CONFIGURATION,
                                ),
                            )
                    }
            }
        }
    }

    override fun onDiscardConfiguration() {
        mutableState.update { it.copy(configuration = savedConfiguration.copy(hasUnsavedChanges = false)) }
        refreshSupport(savedConfiguration)
    }

    override fun onDiagnosticRun(testId: String) {
        runDiagnostics(listOf(testId))
    }

    override fun onDiagnosticRunAll() {
        val current = mutableState.value
        runDiagnostics(
            current.diagnostics
                .filter { current.configuration.maintenanceOperationsEnabled || !it.requiresMaintenance }
                .map { it.id },
        )
    }

    override fun onEventSearchChanged(query: String) {
        mutableState.update { it.copy(eventSearchQuery = query) }
    }

    override fun onEventSeverityFilterChanged(severity: EventSeverity?) {
        mutableState.update { it.copy(eventSeverityFilter = severity) }
    }

    override fun onEventLogExport() {
        val events = mutableState.value.events
        if (events.isEmpty()) {
            showMessage("There are no events to export")
            return
        }
        runCatching { eventLogExporter(events) }
            .onSuccess { path ->
                if (path != null) showMessage("Event log exported to $path")
            }.onFailure { error ->
                showMessage("Unable to export event log: ${error.message ?: "unknown file error"}")
            }
    }

    override fun onOpenLogDirectory() {
        runCatching(logDirectoryOpener)
            .onSuccess { showMessage("Opened persistent log folder") }
            .onFailure { error ->
                showMessage("Unable to open log folder: ${error.message ?: "unknown desktop error"}")
            }
    }

    override fun onClearTraffic() {
        mutableState.update { it.copy(traffic = emptyList()) }
    }

    /** Disconnects the serial session and cancels all work owned by this window. */
    override fun close() {
        logger.info("Control-panel controller closing")
        val gate = hardwareGate
        hardwareGate = null
        hardwareObservers?.cancel()
        motionJob?.cancel()
        if (gate != null) runBlocking { gate.disconnect() }
        scope.cancel()
    }

    private fun executeHardware(
        title: String,
        command: suspend (Gate) -> GateResult<Unit>,
        severity: EventSeverity = EventSeverity.SUCCESS,
        category: EventCategory = EventCategory.PASSAGE,
        onConfirmed: suspend () -> Unit = {},
    ) {
        val gate = connectedGate() ?: return
        if (mutableState.value.commandInProgress) return
        mutableState.update { it.copy(commandInProgress = true, transientMessage = null) }
        scope.launch {
            when (val result = command(gate)) {
                is GateResult.Success -> {
                    onConfirmed()
                    gate.refreshStatus()
                    mutableState.update {
                        it
                            .copy(commandInProgress = false)
                            .appendEvent(event(title, "Confirmed by physical controller", severity, category))
                    }
                }

                is GateResult.Failure ->
                    mutableState.update {
                        it
                            .copy(commandInProgress = false, transientMessage = result.error.displayMessage())
                            .appendEvent(event("Command failed", result.error.displayMessage(), EventSeverity.ERROR, category))
                    }
            }
        }
    }

    private fun runDiagnostics(ids: List<String>) {
        val gate = connectedGate() ?: return
        if (mutableState.value.commandInProgress) return
        val available = mutableState.value.diagnostics.associateBy { it.id }
        val supportedIds = ids.distinct().filter { it in available }
        if (supportedIds.isEmpty()) {
            showMessage("This diagnostic is not supported by the selected gate profile")
            return
        }
        val maintenanceEnabled = mutableState.value.configuration.maintenanceOperationsEnabled
        if (
            !maintenanceEnabled &&
            supportedIds.any { available.getValue(it).requiresMaintenance }
        ) {
            showMessage("Enable maintenance operations before actuator diagnostics")
            return
        }
        mutableState.update { current ->
            current.copy(
                commandInProgress = true,
                diagnostics =
                    current.diagnostics.map { test ->
                        if (test.id in supportedIds) test.copy(state = DiagnosticState.RUNNING, result = "Running on hardware") else test
                    },
            )
        }
        scope.launch {
            for (id in supportedIds) {
                val result = performDiagnostic(gate, id)
                mutableState.update { current ->
                    current.copy(
                        diagnostics =
                            current.diagnostics.map { test ->
                                if (test.id == id) {
                                    when (result) {
                                        is GateResult.Success -> test.copy(state = DiagnosticState.PASSED, result = "Controller confirmed")
                                        is GateResult.Failure ->
                                            test.copy(state = DiagnosticState.FAILED, result = result.error.displayMessage())
                                    }
                                } else {
                                    test
                                }
                            },
                    )
                }
            }
            mutableState.update {
                it
                    .copy(commandInProgress = false)
                    .appendEvent(
                        event(
                            "Diagnostics completed",
                            "${supportedIds.size} hardware checks",
                            EventSeverity.INFO,
                            EventCategory.DIAGNOSTIC,
                        ),
                    )
            }
        }
    }

    private suspend fun performDiagnostic(
        gate: Gate,
        id: String,
    ): GateResult<Unit> =
        when (id) {
            "initialize" -> gate.initialize()
            "firmware" -> gate.firmware().asUnit()
            "status" -> gate.refreshStatus().asUnit()
            "clock-read" -> gate.readClock().asUnit()
            "clock-sync" -> gate.setClock(GateClock(Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())))
            "standby-read" -> gate.readStandbyPolicy().asUnit()
            "timing-read" -> gate.readDoorTiming().asUnit()
            "settings-read" -> gate.readSettings().asUnit()
            "clear-counters" -> gate.clearPassageCounters()
            "door-open" -> gate.runDiagnostic(GateDiagnostic.Door(GateDoorTestAction.OPEN))
            "door-close" -> gate.runDiagnostic(GateDiagnostic.Door(GateDoorTestAction.CLOSE))
            "door-free" -> gate.runDiagnostic(GateDiagnostic.Door(GateDoorTestAction.FREE))
            "door-lock" -> gate.runDiagnostic(GateDiagnostic.Door(GateDoorTestAction.LOCK))
            "sensor-bank" -> gate.readSensors().asUnit()
            "indicator-green-on" -> gate.runDiagnostic(GateDiagnostic.Indicator(GateLampColor.GREEN, true))
            "indicator-green-off" -> gate.runDiagnostic(GateDiagnostic.Indicator(GateLampColor.GREEN, false))
            "indicator-blue-on" -> gate.runDiagnostic(GateDiagnostic.Indicator(GateLampColor.BLUE, true))
            "indicator-blue-off" -> gate.runDiagnostic(GateDiagnostic.Indicator(GateLampColor.BLUE, false))
            "indicator-red-on" -> gate.runDiagnostic(GateDiagnostic.Indicator(GateLampColor.RED, true))
            "indicator-red-off" -> gate.runDiagnostic(GateDiagnostic.Indicator(GateLampColor.RED, false))
            "buzzer-1-on" -> gate.runDiagnostic(GateDiagnostic.Buzzer(1, true))
            "buzzer-1-off" -> gate.runDiagnostic(GateDiagnostic.Buzzer(1, false))
            "buzzer-2-on" -> gate.runDiagnostic(GateDiagnostic.Buzzer(2, true))
            "buzzer-2-off" -> gate.runDiagnostic(GateDiagnostic.Buzzer(2, false))
            "buzzer-3-on" -> gate.runDiagnostic(GateDiagnostic.Buzzer(3, true))
            "buzzer-3-off" -> gate.runDiagnostic(GateDiagnostic.Buzzer(3, false))
            "end-green-on" -> gate.runDiagnostic(GateDiagnostic.EndDisplay(GateLampColor.GREEN, true))
            "end-green-off" -> gate.runDiagnostic(GateDiagnostic.EndDisplay(GateLampColor.GREEN, false))
            "end-yellow-on" -> gate.runDiagnostic(GateDiagnostic.EndDisplay(GateLampColor.YELLOW, true))
            "end-yellow-off" -> gate.runDiagnostic(GateDiagnostic.EndDisplay(GateLampColor.YELLOW, false))
            "end-red-on" -> gate.runDiagnostic(GateDiagnostic.EndDisplay(GateLampColor.RED, true))
            "end-red-off" -> gate.runDiagnostic(GateDiagnostic.EndDisplay(GateLampColor.RED, false))
            "return-cup-on" -> gate.runDiagnostic(GateDiagnostic.ReturnCupLamp(true))
            "return-cup-off" -> gate.runDiagnostic(GateDiagnostic.ReturnCupLamp(false))
            "ups" -> gate.refreshStatus().asUnit()
            "reset" -> gate.reset()
            else -> GateResult.Failure(GateError.InvalidRequest("Unknown diagnostic: $id"))
        }

    private suspend fun applyControllerConfiguration(
        gate: Gate,
        configuration: GateConfigurationUi,
    ): GateResult<Unit> {
        val parsed =
            when (val result = configuration.parseControllerConfiguration()) {
                is GateResult.Success -> result.value
                is GateResult.Failure -> return result
            }
        val supported = mutableState.value.supportedCapabilities intersect gate.capabilities
        val operations: List<suspend () -> GateResult<Unit>> =
            buildList {
                if (GateCapability.SETTINGS in supported) add { gate.applySettings(parsed.settings) }
                if (GateCapability.PASS_MODE in supported) add { gate.setPassMode(parsed.passageMode) }
                if (GateCapability.SAFETY_REGION in supported) {
                    add { gate.setSafetyRegion(GateSafetyRegion(parsed.safetyRegion)) }
                }
                if (GateCapability.UPS_SHUTDOWN in supported) add { gate.setUpsShutdownDelaySeconds(parsed.upsDelay) }
                if (GateCapability.STANDBY in supported) {
                    add { gate.setStandbyPolicy(GateStandbyPolicy(parsed.standbyTimeout.seconds, parsed.standbyMode)) }
                }
                if (GateCapability.DOOR_TIMING in supported) {
                    add { gate.setDoorTiming(GateDoorTiming(parsed.openingDelay.milliseconds, parsed.closingDelay.milliseconds)) }
                }
            }
        operations.forEach { operation ->
            val result = operation()
            if (result is GateResult.Failure) return result
        }
        return GateResult.Success(Unit)
    }

    private suspend fun animatePassage(
        direction: PassageDirection,
        lamp: IndicatorLamp,
    ) {
        motionJob?.cancel()
        motionJob =
            scope.launch {
                updateMotion(FlapPosition.OPENING, 0f, direction, lamp)
                animateProgress(opening = true)
                updateMotion(FlapPosition.OPEN, 1f, direction, lamp)
                repeat(PASSENGER_STEPS) { step ->
                    val progress = (step + 1f) / PASSENGER_STEPS
                    val directionalProgress = if (direction == PassageDirection.ENTRY) progress else 1f - progress
                    mutableState.update { it.copy(gateTwin = it.gateTwin.copy(passengerProgress = directionalProgress)) }
                    delay(PASSENGER_FRAME_MILLIS)
                }
                updateMotion(FlapPosition.CLOSING, 1f, direction, lamp)
                animateProgress(opening = false)
                updateMotion(FlapPosition.CLOSED, 0f, null, lamp)
            }
        motionJob?.join()
    }

    private suspend fun animateEmergency(enabled: Boolean) {
        motionJob?.cancel()
        motionJob =
            scope.launch {
                if (enabled) {
                    mutableState.update { it.copy(gateTwin = it.gateTwin.copy(emergencyActive = true, lamp = IndicatorLamp.BLUE)) }
                    updateMotion(FlapPosition.OPENING, 0f, null, IndicatorLamp.BLUE)
                    animateProgress(opening = true)
                    updateMotion(FlapPosition.OPEN, 1f, null, IndicatorLamp.BLUE)
                } else {
                    updateMotion(FlapPosition.CLOSING, 1f, null, IndicatorLamp.GREEN)
                    animateProgress(opening = false)
                    mutableState.update { it.copy(gateTwin = it.gateTwin.copy(emergencyActive = false)) }
                    updateMotion(FlapPosition.CLOSED, 0f, null, IndicatorLamp.GREEN)
                }
            }
        motionJob?.join()
    }

    private suspend fun animateProgress(opening: Boolean) {
        repeat(MOTION_STEPS) { step ->
            val progress = (step + 1f) / MOTION_STEPS
            val value = if (opening) progress else 1f - progress
            mutableState.update {
                it.copy(gateTwin = it.gateTwin.copy(leftFlapProgress = value, rightFlapProgress = value))
            }
            delay(MOTION_FRAME_MILLIS)
        }
    }

    private suspend fun pulseRejectedLamp() {
        mutableState.update { it.copy(gateTwin = it.gateTwin.copy(lamp = IndicatorLamp.RED)) }
        delay(REJECTION_HOLD_MILLIS)
        mutableState.update { it.copy(gateTwin = it.gateTwin.copy(lamp = IndicatorLamp.GREEN)) }
    }

    private fun updateMotion(
        position: FlapPosition,
        progress: Float,
        direction: PassageDirection?,
        lamp: IndicatorLamp,
    ) {
        mutableState.update {
            it.copy(
                gateTwin =
                    it.gateTwin.copy(
                        leftFlap = position,
                        rightFlap = position,
                        leftFlapProgress = progress,
                        rightFlapProgress = progress,
                        passageDirection = direction,
                        passengerProgress = if (direction == null) null else it.gateTwin.passengerProgress,
                        lamp = lamp,
                    ),
            )
        }
    }

    private fun createGate(configuration: GateConfigurationUi): Gate? {
        if (mutableState.value.availableSerialPorts.none { it.name == configuration.serialPort }) {
            showMessage("Select a serial port currently detected by this workstation")
            return null
        }
        val baud = configuration.baudRate.toIntOrNull()
        val timeout = configuration.responseTimeoutMs.toLongOrNull()
        val polling = configuration.pollIntervalMs.toLongOrNull()
        if (!validSerialNumbers(baud, timeout, polling)) {
            showMessage("Check serial baud rate, timeout, and polling values")
            return null
        }
        val profile =
            configuration.toHardwareProfile()
                ?: run {
                    showMessage("Select a supported gate mechanism and controller profile")
                    return null
                }
        val config =
            GateDeviceConfig(
                vendor = GateVendor.PULOON,
                serial =
                    SerialConnectionConfig(
                        SerialPortName(configuration.serialPort),
                        SerialParameters(requireNotNull(baud)),
                    ),
                hardware = profile,
                runtime =
                    GateRuntimeOptions(
                        responseTimeout = requireNotNull(timeout).milliseconds,
                        statusPollInterval = requireNotNull(polling).milliseconds,
                        reconnectPolicy =
                            if (configuration.reconnectAutomatically) {
                                ReconnectPolicy.ExponentialBackoff()
                            } else {
                                ReconnectPolicy.Disabled
                            },
                    ),
                maintenanceOperationsEnabled = configuration.maintenanceOperationsEnabled,
            )
        logger.info(config.diagnosticLogMessage())
        return when (val result = gateFactory(config)) {
            is GateResult.Success -> {
                logger.info(
                    "gateCreated vendor=${result.value.descriptor.vendor.name} " +
                        "mechanism=${result.value.descriptor.mechanism.name} site=${result.value.descriptor.site.name} " +
                        "capabilities=${result.value.capabilities.map { it.name }.sorted()}",
                )
                result.value
            }
            is GateResult.Failure -> {
                logger.warning("gateCreation failed detail=${result.error.displayMessage().logValue()}")
                showMessage(result.error.displayMessage())
                null
            }
        }
    }

    private fun refreshSupport(configuration: GateConfigurationUi) {
        val profile = configuration.toHardwareProfile() ?: return
        val config =
            GateDeviceConfig(
                vendor = GateVendor.PULOON,
                serial = SerialConnectionConfig(SerialPortName(configuration.serialPort.ifBlank { "support-only" })),
                hardware = profile,
                maintenanceOperationsEnabled = configuration.maintenanceOperationsEnabled,
            )
        when (val result = supportProvider(config)) {
            is GateResult.Success -> {
                val support = result.value
                val supportDiagnostic = support.diagnosticLogMessage()
                if (supportDiagnostic != lastLoggedSupport) {
                    lastLoggedSupport = supportDiagnostic
                    logger.fine(supportDiagnostic)
                }
                mutableState.update { current ->
                    val passageMode =
                        current.configuration.passageMode
                            .toPassMode()
                            .takeIf { it in support.passModes }
                            ?: support.passModes.minByOrNull { it.ordinal }
                    val standbyMode =
                        current.configuration.standbyPassMode
                            .toPassMode()
                            .takeIf { it in support.passModes }
                            ?: support.passModes.minByOrNull { it.ordinal }
                    val safetyRegion =
                        current.configuration.safetyRegion.toIntOrNull().takeIf { selected ->
                            support.safetyRegions.any { it.number == selected }
                        } ?: support.safetyRegions.minOfOrNull { it.number }
                    val existingSensors = current.gateTwin.sensors.associateBy { it.id }
                    val sensors =
                        gateSensors(support.sensors.map { it.number }.toSet()).map { sensor ->
                            existingSensors[sensor.id]?.let { previous ->
                                sensor.copy(health = previous.health, lastChanged = previous.lastChanged)
                            } ?: sensor
                        }
                    current.copy(
                        supportedCapabilities = support.capabilities,
                        supportedPassModes = support.passModes,
                        supportedSafetyRegions = support.safetyRegions.map { it.number }.toSet(),
                        diagnostics = diagnosticsFor(support.capabilities, configuration.tokenControlUnitInstalled),
                        configuration =
                            current.configuration.copy(
                                passageMode = passageMode?.name ?: current.configuration.passageMode,
                                standbyPassMode = standbyMode?.name ?: current.configuration.standbyPassMode,
                                safetyRegion = safetyRegion?.toString() ?: current.configuration.safetyRegion,
                            ),
                        gateTwin = current.gateTwin.copy(sensors = sensors),
                    )
                }
            }

            is GateResult.Failure -> {
                logger.warning("gateSupport failed detail=${result.error.displayMessage().logValue()}")
                mutableState.update {
                    it.copy(
                        supportedCapabilities = emptySet(),
                        supportedPassModes = emptySet(),
                        supportedSafetyRegions = emptySet(),
                        diagnostics = emptyList(),
                        transientMessage = result.error.displayMessage(),
                    )
                }
            }
        }
    }

    private fun observe(gate: Gate) {
        hardwareObservers?.cancel()
        hardwareObservers =
            scope.launch {
                launch {
                    gate.connectionState.collectLatest { connection ->
                        val health = connection.toUiHealth()
                        mutableState.update {
                            it.copy(
                                connectionHealth = health,
                                gateTwin = it.gateTwin.copy(connectionHealth = health),
                            )
                        }
                    }
                }
                launch {
                    gate.status.collectLatest { status ->
                        if (status != null && gate.connectionState.value == GateConnectionState.CONNECTED) {
                            mutableState.update { it.withHardwareStatus(status) }
                            if (GateCapability.SENSORS in gate.capabilities) {
                                when (val result = gate.readSensors()) {
                                    is GateResult.Success -> {
                                        val sensorDiagnostic = result.value.diagnosticLogMessage()
                                        if (sensorDiagnostic != lastLoggedSensors) {
                                            lastLoggedSensors = sensorDiagnostic
                                            logger.fine(sensorDiagnostic)
                                        }
                                        mutableState.update { it.withSensorStatus(result.value) }
                                    }
                                    is GateResult.Failure -> Unit
                                }
                            }
                        }
                    }
                }
                launch {
                    gate.events.collectLatest { sdkEvent ->
                        if (sdkEvent is GateEvent.StatusChanged) {
                            val statusDiagnostic = sdkEvent.status.diagnosticLogMessage()
                            if (statusDiagnostic != lastLoggedStatus) {
                                lastLoggedStatus = statusDiagnostic
                                runCatching { logger.sdkEvent(sdkEvent) }
                            }
                        } else {
                            runCatching { logger.sdkEvent(sdkEvent) }
                        }
                        val traffic = sdkEvent.toTrafficUi() ?: return@collectLatest
                        runCatching { logger.traffic(traffic) }
                        mutableState.update { it.appendTraffic(traffic) }
                    }
                }
            }
    }

    private suspend fun refreshIdentity(gate: Gate) {
        when (val firmware = gate.firmware()) {
            is GateResult.Success ->
                mutableState.update {
                    it.copy(
                        controllerName = firmware.value.model ?: it.controllerName,
                        firmware = firmware.value.version,
                    )
                }

            is GateResult.Failure -> Unit
        }
    }

    private fun disconnectHardware() {
        val gate = hardwareGate ?: return
        hardwareGate = null
        lastLoggedStatus = null
        lastLoggedSensors = null
        hardwareObservers?.cancel()
        hardwareObservers = null
        motionJob?.cancel()
        scope.launch {
            gate.disconnect()
            mutableState.update {
                it
                    .copy(
                        connectionHealth = ConnectionHealth.DISCONNECTED,
                        commandInProgress = false,
                        gateTwin =
                            it.gateTwin.copy(
                                passengerProgress = null,
                                connectionHealth = ConnectionHealth.DISCONNECTED,
                                lamp = IndicatorLamp.OFF,
                                emergencyActive = false,
                            ),
                    ).appendEvent(event("Gate disconnected", "Serial port released", EventSeverity.INFO))
            }
        }
    }

    private fun connectedGate(): Gate? {
        val gate = hardwareGate
        if (gate == null || gate.connectionState.value != GateConnectionState.CONNECTED) {
            showMessage("Connect the physical gate before sending commands")
            return null
        }
        return gate
    }

    private fun showMessage(message: String) {
        mutableState.update { it.copy(transientMessage = message) }
    }

    private fun event(
        title: String,
        detail: String,
        severity: EventSeverity,
        category: EventCategory = EventCategory.CONNECTION,
    ): GateEventUi {
        eventSequence += 1
        return GateEventUi(
            id = "event-$eventSequence",
            timestamp = "T+${eventSequence.toString().padStart(3, '0')}",
            title = title,
            detail = detail,
            severity = severity,
            category = category,
        ).also { item -> runCatching { logger.event(item) } }
    }

    private companion object {
        const val MOTION_STEPS = 12
        const val MOTION_FRAME_MILLIS = 55L
        const val PASSENGER_STEPS = 16
        const val PASSENGER_FRAME_MILLIS = 70L
        const val REJECTION_HOLD_MILLIS = 900L
    }
}

private const val MAX_TRAFFIC_ROWS = 250

private fun GateConnectionState.toUiHealth(): ConnectionHealth =
    when (this) {
        GateConnectionState.DISCONNECTED -> ConnectionHealth.DISCONNECTED
        GateConnectionState.CONNECTING, GateConnectionState.RECONNECTING -> ConnectionHealth.CONNECTING
        GateConnectionState.CONNECTED -> ConnectionHealth.CONNECTED
        GateConnectionState.FAILED -> ConnectionHealth.DEGRADED
    }

private fun GateError.displayMessage(): String =
    when (this) {
        GateError.NotConnected -> "Gate is not connected"
        is GateError.Timeout -> "Timed out while waiting for $operation"
        is GateError.Transport -> message
        is GateError.Protocol -> message
        is GateError.Device -> message ?: "Controller rejected command $code"
        is GateError.InvalidRequest -> message
        is GateError.UnsupportedCapability -> "Gate does not support ${capability.name.lowercase()}"
        is GateError.UnsupportedVendor -> "${vendor.name} support is not installed"
    }

private fun ControlPanelUiState.appendEvent(item: GateEventUi): ControlPanelUiState = copy(events = (listOf(item) + events).take(250))

private fun ControlPanelUiState.appendTraffic(item: GateTrafficUi): ControlPanelUiState =
    copy(traffic = (listOf(item) + traffic).take(MAX_TRAFFIC_ROWS))

private fun com.qurkos.gate.sdk.GateEvent.toTrafficUi(): GateTrafficUi? =
    when (this) {
        is com.qurkos.gate.sdk.GateEvent.CommandSent ->
            GateTrafficUi(
                id = "$sequence-tx",
                timestamp = at.trafficTimestamp(),
                direction = TrafficDirection.TX,
                command = command.displayName,
                detail = detail,
            )

        is com.qurkos.gate.sdk.GateEvent.ResponseReceived ->
            GateTrafficUi(
                id = "$sequence-rx",
                timestamp = at.trafficTimestamp(),
                direction = TrafficDirection.RX,
                command = command.displayName,
                detail = detail,
                latencyMs = elapsed.inWholeMilliseconds,
                failed = outcome == GateCommandOutcome.FAILURE,
            )

        else -> null
    }

private val com.qurkos.gate.sdk.GateCommand.displayName: String
    get() = name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

private fun Instant.trafficTimestamp(): String {
    val local = toLocalDateTime(TimeZone.currentSystemDefault())
    val millis = (local.nanosecond / 1_000_000).toString().padStart(3, '0')
    return "${local.hour.twoDigits()}:${local.minute.twoDigits()}:${local.second.twoDigits()}.$millis"
}

private fun Int.twoDigits(): String = toString().padStart(2, '0')

private fun GateResult<*>.asUnit(): GateResult<Unit> =
    when (this) {
        is GateResult.Success -> GateResult.Success(Unit)
        is GateResult.Failure -> this
    }

private fun validSerialNumbers(
    baud: Int?,
    timeout: Long?,
    polling: Long?,
): Boolean =
    baud != null &&
        baud > 0 &&
        timeout != null &&
        timeout > 0 &&
        polling != null &&
        polling > 0

private fun String.toPassMode(): GatePassMode? {
    val normalized = trim().uppercase().replace(' ', '_').replace('-', '_')
    return GatePassMode.entries.firstOrNull { it.name == normalized } ?: when (normalized) {
        "CONTROLLED_BOTH" -> GatePassMode.CONTROLLED_BOTH
        else -> null
    }
}

private fun String.toLampColor(): GateLampColor = GateLampColor.entries.firstOrNull { it.name == trim().uppercase() } ?: GateLampColor.GREEN

private fun GateLampColor.toIndicatorLamp(): IndicatorLamp =
    when (this) {
        GateLampColor.OFF -> IndicatorLamp.OFF
        GateLampColor.GREEN -> IndicatorLamp.GREEN
        GateLampColor.BLUE -> IndicatorLamp.BLUE
        GateLampColor.RED -> IndicatorLamp.RED
        GateLampColor.YELLOW -> IndicatorLamp.AMBER
    }

private fun GateConfigurationUi.normalizedProfile(): GateConfigurationUi =
    when (site) {
        "INDIA", "KOLKATA_INDIA" -> copy(childSensorsInstalled = false, childDetectionLevel = "0")
        "CHINA" -> copy(upsInstalled = false, tokenControlUnitInstalled = false)
        else ->
            copy(
                upsInstalled = false,
                tokenControlUnitInstalled = false,
                childSensorsInstalled = false,
                childDetectionLevel = "0",
            )
    }.let { if (it.mechanism == "SWING") it.copy(normalOpenMode = false) else it }

private fun GateConfigurationUi.toHardwareProfile(): GateHardwareProfile? {
    val revision = GateProtocolRevision.entries.firstOrNull { it.name == protocolRevision } ?: return null
    val mechanism = GateMechanism.entries.firstOrNull { it.name == this.mechanism } ?: return null
    val site = GateSite.entries.firstOrNull { it.name == this.site } ?: return null
    val modules =
        buildSet {
            if (upsInstalled) add(GateModule.UPS)
            if (tokenControlUnitInstalled) add(GateModule.TOKEN_CONTROL_UNIT)
            if (childSensorsInstalled) add(GateModule.CHILD_SENSORS)
        }
    return GateHardwareProfile(
        mechanism = mechanism,
        site = site,
        modules = modules,
        normalOpen = normalOpenMode,
        protocolRevision = revision,
    )
}

private data class ConnectionFacts(
    val protocolRevision: String,
    val mechanism: String,
    val site: String,
    val ups: Boolean,
    val tcu: Boolean,
    val childSensors: Boolean,
    val serialPort: String,
    val baudRate: String,
    val responseTimeout: String,
    val pollInterval: String,
    val reconnectAutomatically: Boolean,
    val maintenanceEnabled: Boolean,
)

private fun GateConfigurationUi.connectionFacts(): ConnectionFacts =
    ConnectionFacts(
        protocolRevision,
        mechanism,
        site,
        upsInstalled,
        tokenControlUnitInstalled,
        childSensorsInstalled,
        serialPort,
        baudRate,
        responseTimeoutMs,
        pollIntervalMs,
        reconnectAutomatically,
        maintenanceOperationsEnabled,
    )

private data class ParsedControllerConfiguration(
    val passageMode: GatePassMode,
    val standbyMode: GatePassMode,
    val safetyRegion: Int,
    val upsDelay: Int,
    val standbyTimeout: Long,
    val openingDelay: Long,
    val closingDelay: Long,
    val settings: Set<GateSetting>,
)

/** Centralizes validation of the editable text form before any serial write is attempted. */
@Suppress("CyclomaticComplexMethod", "ReturnCount") // Fail-fast validation identifies the exact invalid field.
private fun GateConfigurationUi.parseControllerConfiguration(): GateResult<ParsedControllerConfiguration> {
    fun invalid(field: String): GateResult<ParsedControllerConfiguration> =
        GateResult.Failure(GateError.InvalidRequest("Invalid controller setting: $field"))

    if (!hasValidInputs()) return invalid("one or more values are outside the supported range")
    val passageMode = passageMode.toPassMode() ?: return invalid("passage mode")
    val standbyMode = standbyPassMode.toPassMode() ?: return invalid("standby passage mode")
    val safetyRegion = safetyRegion.toIntOrNull()?.takeIf { it > 0 } ?: return invalid("safety region")
    val upsDelay =
        upsShutdownDelaySeconds.toIntOrNull()?.takeIf { PuloonInputRules.upsShutdownDelay.accepts(it.toString()) }
            ?: return invalid("UPS shutdown delay")
    val standbyTimeout =
        standbyTimeoutSeconds.toLongOrNull()?.takeIf { PuloonInputRules.standbyTimeout.accepts(it.toString()) }
            ?: return invalid("standby timeout")
    val openingDelay =
        openDurationMs.toLongOrNull()?.takeIf { PuloonInputRules.doorTiming.accepts(it.toString()) }
            ?: return invalid("opening delay")
    val closingDelay =
        closeDelayMs.toLongOrNull()?.takeIf { PuloonInputRules.doorTiming.accepts(it.toString()) }
            ?: return invalid("closing delay")
    val noEntryTimeout = noEntryTimeoutSeconds.toLongOrNull() ?: return invalid("no-entry timeout")
    val buzzerTimeout = buzzerTimeoutUnits.toIntOrNull() ?: return invalid("buzzer timeout")
    val safetyTimeoutRaw = safetyRegionTimeoutSeconds.toLongOrNull() ?: return invalid("safety-region timeout")
    val childLevel = childDetectionLevel.toIntOrNull() ?: return invalid("child-detection level")
    val tailing = tailingSensitivity.toIntOrNull() ?: return invalid("tailing sensitivity")
    val hurryUp = hurryUpLevel.toIntOrNull() ?: return invalid("hurry-up level")
    val settings =
        setOf(
            GateSetting.NormalOpenMode(normalOpenMode),
            GateSetting.NoEntryTimeout(noEntryTimeout.seconds),
            GateSetting.ChildDetection(childLevel),
            GateSetting.TailingSensitivity(tailing),
            GateSetting.HurryUpLevel(hurryUp),
            GateSetting.TagTimeoutFromLastTag(tagTimeoutFromLastTag),
            GateSetting.BuzzerTimeoutUnits(buzzerTimeout),
            GateSetting.SafetyRegionTimeout(safetyTimeoutRaw.takeUnless { it == 255L }?.seconds),
        )
    return GateResult.Success(
        ParsedControllerConfiguration(
            passageMode,
            standbyMode,
            safetyRegion,
            upsDelay,
            standbyTimeout,
            openingDelay,
            closingDelay,
            settings,
        ),
    )
}
