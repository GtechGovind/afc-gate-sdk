package com.qurkos.gate.controlpanel.ui.model

import androidx.compose.runtime.Immutable

/** Top-level destinations available from the control-panel sidebar. */
enum class ControlPanelDestination(
    val title: String,
) {
    LIVE_CONTROL("Live Control"),
    SENSORS("Sensors"),
    CONFIGURATION("Configuration"),
    DIAGNOSTICS("Diagnostics"),
    EVENT_LOG("Event Log"),
}

/** Connection health shown in the persistent header. */
enum class ConnectionHealth {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DEGRADED,
}

/** Normalized physical flap position reported or confirmed by the gate controller. */
enum class FlapPosition {
    CLOSED,
    OPENING,
    OPEN,
    CLOSING,
    UNKNOWN,
}

/** Direction of an authorized physical passage. */
enum class PassageDirection {
    ENTRY,
    EXIT,
}

/** Indicator color requested from the gate hardware. */
enum class IndicatorLamp {
    OFF,
    GREEN,
    RED,
    AMBER,
    BLUE,
}

/** Health of one beam, switch, or safety input. */
enum class SensorHealth {
    CLEAR,
    ACTIVE,
    BLOCKED,
    FAULT,
    UNKNOWN,
}

/** Functional grouping used by sensor filters and labels. */
enum class SensorGroup(
    val title: String,
) {
    ENTRY("Entry lane"),
    SAFETY("Safety zone"),
    PASSAGE("Passage lane"),
    EXIT("Exit lane"),
    MECHANISM("Mechanism"),
    SECURITY("Security"),
}

/** Presentation state for one of the sixteen controller sensors. */
@Immutable
data class GateSensorUi(
    val id: Int,
    val code: String,
    val name: String,
    val description: String,
    val group: SensorGroup,
    val health: SensorHealth = SensorHealth.UNKNOWN,
    val lastChanged: String = "Never",
)

/** State consumed by the animated physical-gate twin. */
@Immutable
data class GateTwinUiState(
    val leftFlap: FlapPosition = FlapPosition.UNKNOWN,
    val rightFlap: FlapPosition = FlapPosition.UNKNOWN,
    val leftFlapProgress: Float = 0f,
    val rightFlapProgress: Float = 0f,
    val passageDirection: PassageDirection? = null,
    val lamp: IndicatorLamp = IndicatorLamp.OFF,
    val emergencyActive: Boolean = false,
    val passengerProgress: Float? = null,
    val sensors: List<GateSensorUi> = defaultGateSensors(),
    val highlightedSensorId: Int? = null,
    val reducedMotion: Boolean = false,
) {
    init {
        require(leftFlapProgress in 0f..1f) { "Left flap progress must be normalized" }
        require(rightFlapProgress in 0f..1f) { "Right flap progress must be normalized" }
        require(passengerProgress == null || passengerProgress in 0f..1f) {
            "Passenger progress must be normalized"
        }
    }
}

/** One controller event displayed in live activity and event history. */
@Immutable
data class GateEventUi(
    val id: String,
    val timestamp: String,
    val title: String,
    val detail: String,
    val severity: EventSeverity,
    val category: EventCategory,
)

enum class EventSeverity { INFO, SUCCESS, WARNING, ERROR }

enum class EventCategory { CONNECTION, PASSAGE, SENSOR, CONFIGURATION, DIAGNOSTIC, EMERGENCY }

/** Direction of one semantic serial traffic row. */
enum class TrafficDirection { TX, RX }

/** One correlated command or response shown in the live read-only traffic console. */
@Immutable
data class GateTrafficUi(
    val id: String,
    val timestamp: String,
    val direction: TrafficDirection,
    val command: String,
    val detail: String,
    val latencyMs: Long? = null,
    val failed: Boolean = false,
)

/** Editable operational settings. Values are strings so partially entered form values remain representable. */
@Immutable
data class GateConfigurationUi(
    val passageMode: String = "Controlled Both",
    val serialPort: String = "/dev/ttyUSB0",
    val baudRate: String = "57600",
    val responseTimeoutMs: String = "1000",
    val pollIntervalMs: String = "500",
    val openDurationMs: String = "1000",
    val closeDelayMs: String = "500",
    val safetyRegion: String = "1",
    val upsShutdownDelaySeconds: String = "30",
    val standbyTimeoutSeconds: String = "255",
    val standbyPassMode: String = "OUT_OF_SERVICE",
    val sensorSensitivity: String = "5",
    val passageTimeoutSeconds: String = "10",
    val childHeight: String = "120",
    val tailingSensitivity: String = "1",
    val hurryUpLevel: String = "1",
    val normalOpenMode: Boolean = false,
    val childDetection: Boolean = true,
    val tagTimeoutFromLastTag: Boolean = true,
    val reconnectAutomatically: Boolean = true,
    val maintenanceOperationsEnabled: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
)

/** Available maintenance test and its latest outcome. */
@Immutable
data class DiagnosticTestUi(
    val id: String,
    val title: String,
    val description: String,
    val requiresMaintenance: Boolean = false,
    val state: DiagnosticState = DiagnosticState.IDLE,
    val result: String? = null,
)

enum class DiagnosticState { IDLE, RUNNING, PASSED, FAILED }

/** One serial device currently reported by the host operating system. */
@Immutable
data class SerialPortOptionUi(
    val name: String,
    val description: String? = null,
)

/** Immutable snapshot rendered by the entire control panel. */
@Immutable
data class ControlPanelUiState(
    val destination: ControlPanelDestination = ControlPanelDestination.LIVE_CONTROL,
    val connectionHealth: ConnectionHealth = ConnectionHealth.DISCONNECTED,
    val controllerName: String = "Puloon GCU",
    val firmware: String = "—",
    val passageMode: String = "—",
    val passengerCount: Int? = null,
    val upsChargePercent: Int? = null,
    val upsRuntimeMinutes: Int? = null,
    val gateTwin: GateTwinUiState = GateTwinUiState(),
    val events: List<GateEventUi> = emptyList(),
    val traffic: List<GateTrafficUi> = emptyList(),
    val configuration: GateConfigurationUi = GateConfigurationUi(),
    val availableSerialPorts: List<SerialPortOptionUi> = emptyList(),
    val serialPortDiscoveryError: String? = null,
    val diagnostics: List<DiagnosticTestUi> = defaultDiagnostics(),
    val sensorGroupFilter: SensorGroup? = null,
    val eventSeverityFilter: EventSeverity? = null,
    val eventSearchQuery: String = "",
    val commandInProgress: Boolean = false,
    val safetyHoldProgress: Float = 0f,
    val transientMessage: String? = null,
) {
    init {
        require(passengerCount == null || passengerCount >= 0) { "Passenger count cannot be negative" }
        require(upsChargePercent == null || upsChargePercent in 0..100) { "UPS charge must be a percentage" }
        require(upsRuntimeMinutes == null || upsRuntimeMinutes >= 0) { "UPS runtime cannot be negative" }
        require(safetyHoldProgress in 0f..1f) { "Hold progress must be normalized" }
    }
}

/** User intents emitted by the presentation layer. */
interface ControlPanelCallbacks {
    fun onNavigate(destination: ControlPanelDestination)

    fun onAllowEntry()

    fun onAllowExit()

    fun onReject()

    fun onEmergencyStop()

    fun onEmergencyReset()

    fun onConnect()

    fun onDisconnect()

    fun onSensorSelected(sensorId: Int)

    fun onSensorGroupFilterChanged(group: SensorGroup?)

    fun onConfigurationChanged(configuration: GateConfigurationUi)

    fun onRefreshSerialPorts()

    fun onSaveConfiguration()

    fun onDiscardConfiguration()

    fun onDiagnosticRun(testId: String)

    fun onDiagnosticRunAll()

    fun onEventSearchChanged(query: String)

    fun onEventSeverityFilterChanged(severity: EventSeverity?)

    fun onEventLogExport()

    fun onClearTraffic()
}

/** Safe default callbacks for previews and incremental host integration. */
object NoOpControlPanelCallbacks : ControlPanelCallbacks {
    override fun onNavigate(destination: ControlPanelDestination) = Unit

    override fun onAllowEntry() = Unit

    override fun onAllowExit() = Unit

    override fun onReject() = Unit

    override fun onEmergencyStop() = Unit

    override fun onEmergencyReset() = Unit

    override fun onConnect() = Unit

    override fun onDisconnect() = Unit

    override fun onSensorSelected(sensorId: Int) = Unit

    override fun onSensorGroupFilterChanged(group: SensorGroup?) = Unit

    override fun onConfigurationChanged(configuration: GateConfigurationUi) = Unit

    override fun onRefreshSerialPorts() = Unit

    override fun onSaveConfiguration() = Unit

    override fun onDiscardConfiguration() = Unit

    override fun onDiagnosticRun(testId: String) = Unit

    override fun onDiagnosticRunAll() = Unit

    override fun onEventSearchChanged(query: String) = Unit

    override fun onEventSeverityFilterChanged(severity: EventSeverity?) = Unit

    override fun onEventLogExport() = Unit

    override fun onClearTraffic() = Unit
}

/** Complete ordered controller sensor inventory used by the external sensor panel. */
fun defaultGateSensors(): List<GateSensorUi> =
    listOf(
        sensor(1, "EA-L", "Entry approach left", "Left entry approach beam", SensorGroup.ENTRY),
        sensor(2, "EA-R", "Entry approach right", "Right entry approach beam", SensorGroup.ENTRY),
        sensor(3, "EP-L", "Entry presence left", "Left entry presence beam", SensorGroup.ENTRY),
        sensor(4, "EP-R", "Entry presence right", "Right entry presence beam", SensorGroup.ENTRY),
        sensor(5, "ES-L", "Entry safety left", "Left leading safety beam", SensorGroup.SAFETY),
        sensor(6, "ES-R", "Entry safety right", "Right leading safety beam", SensorGroup.SAFETY),
        sensor(7, "CP-L", "Center presence left", "Left center passage beam", SensorGroup.PASSAGE),
        sensor(8, "CP-R", "Center presence right", "Right center passage beam", SensorGroup.PASSAGE),
        sensor(9, "XS-L", "Exit safety left", "Left trailing safety beam", SensorGroup.SAFETY),
        sensor(10, "XS-R", "Exit safety right", "Right trailing safety beam", SensorGroup.SAFETY),
        sensor(11, "XP-L", "Exit presence left", "Left exit presence beam", SensorGroup.EXIT),
        sensor(12, "XP-R", "Exit presence right", "Right exit presence beam", SensorGroup.EXIT),
        sensor(13, "XA-L", "Exit approach left", "Left exit approach beam", SensorGroup.EXIT),
        sensor(14, "XA-R", "Exit approach right", "Right exit approach beam", SensorGroup.EXIT),
        sensor(15, "CH-L", "Child safety left", "Low-height left child safety beam", SensorGroup.SAFETY),
        sensor(16, "CH-R", "Child safety right", "Low-height right child safety beam", SensorGroup.SAFETY),
    )

private fun sensor(
    id: Int,
    code: String,
    name: String,
    description: String,
    group: SensorGroup,
) = GateSensorUi(id, code, name, description, group)

private fun defaultDiagnostics(): List<DiagnosticTestUi> =
    listOf(
        DiagnosticTestUi("initialize", "Initialize controller", "Run the vendor initialization sequence", true),
        DiagnosticTestUi("firmware", "Firmware identity", "Read model and firmware version"),
        DiagnosticTestUi("status", "Live status", "Refresh normalized gate status and counters"),
        DiagnosticTestUi("clock-read", "Read RTC", "Read the controller real-time clock"),
        DiagnosticTestUi("clock-sync", "Synchronize RTC", "Set controller RTC from this workstation", true),
        DiagnosticTestUi("standby-read", "Read standby", "Read standby timeout and passage mode"),
        DiagnosticTestUi("timing-read", "Read door timing", "Read opening and closing delays"),
        DiagnosticTestUi("settings-read", "Read settings", "Read the typed controller settings block"),
        DiagnosticTestUi("clear-counters", "Clear counters", "Reset accumulated entry and exit counters", true),
        DiagnosticTestUi("left-flap", "Left flap actuator", "Open, hold, and close the left flap", true),
        DiagnosticTestUi("right-flap", "Right flap actuator", "Open, hold, and close the right flap", true),
        DiagnosticTestUi("sensor-bank", "Sensor bank", "Verify all sixteen sensor channels"),
        DiagnosticTestUi("lamps", "Direction lamps", "Cycle green, red, amber, and blue indicators", true),
        DiagnosticTestUi("buzzer", "Warning buzzer", "Play the maintenance warning sequence", true),
        DiagnosticTestUi("ups", "UPS and power", "Read UPS health and estimated runtime"),
        DiagnosticTestUi("reset", "Controller reset", "Run the opt-in controller reset operation", true),
    )
