package com.qurkos.gate.controlpanel.ui.model

import androidx.compose.runtime.Immutable
import com.qurkos.gate.sdk.GateCapability
import com.qurkos.gate.sdk.GatePassMode

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
    val connectionHealth: ConnectionHealth = ConnectionHealth.DISCONNECTED,
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
    val protocolRevision: String = "V2_8",
    val mechanism: String = "SECTOR",
    val site: String = "GENERIC",
    val upsInstalled: Boolean = false,
    val tokenControlUnitInstalled: Boolean = false,
    val childSensorsInstalled: Boolean = false,
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
    val noEntryTimeoutSeconds: String = "10",
    val buzzerTimeoutUnits: String = "100",
    val safetyRegionTimeoutSeconds: String = "30",
    val tailingSensitivity: String = "1",
    val hurryUpLevel: String = "1",
    val normalOpenMode: Boolean = false,
    val childDetectionLevel: String = "0",
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
    val requiredCapability: GateCapability,
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
    val controllerStatusDetail: String = "Waiting for status",
    val powerStatusDetail: String = "Not configured",
    val tokenStatusDetail: String = "Not configured",
    val gateTwin: GateTwinUiState = GateTwinUiState(),
    val events: List<GateEventUi> = emptyList(),
    val traffic: List<GateTrafficUi> = emptyList(),
    val configuration: GateConfigurationUi = GateConfigurationUi(),
    val availableSerialPorts: List<SerialPortOptionUi> = emptyList(),
    val serialPortDiscoveryError: String? = null,
    val diagnostics: List<DiagnosticTestUi> = emptyList(),
    val sensorGroupFilter: SensorGroup? = null,
    val eventSeverityFilter: EventSeverity? = null,
    val eventSearchQuery: String = "",
    val commandInProgress: Boolean = false,
    val safetyHoldProgress: Float = 0f,
    val transientMessage: String? = null,
    val supportedCapabilities: Set<GateCapability> = emptySet(),
    val supportedPassModes: Set<GatePassMode> = emptySet(),
    val supportedSafetyRegions: Set<Int> = emptySet(),
    val passagePassengerCount: Int = 1,
    val passageLampColor: String = "GREEN",
    val rejectDirection: String = "ENTRY",
    val logDirectory: String = "",
) {
    init {
        require(passengerCount == null || passengerCount >= 0) { "Passenger count cannot be negative" }
        require(upsChargePercent == null || upsChargePercent in 0..100) { "UPS charge must be a percentage" }
        require(upsRuntimeMinutes == null || upsRuntimeMinutes >= 0) { "UPS runtime cannot be negative" }
        require(safetyHoldProgress in 0f..1f) { "Hold progress must be normalized" }
        require(passagePassengerCount in 1..99) { "Passage passenger count must be between 1 and 99" }
    }
}

/** User intents emitted by the presentation layer. */
interface ControlPanelCallbacks {
    fun onNavigate(destination: ControlPanelDestination)

    fun onAllowEntry()

    fun onAllowExit()

    fun onPassagePassengerCountChanged(value: Int)

    fun onPassageLampColorChanged(value: String)

    fun onRejectDirectionChanged(value: String)

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

    fun onOpenLogDirectory()

    fun onClearTraffic()
}

/** Safe default callbacks for previews and incremental host integration. */
object NoOpControlPanelCallbacks : ControlPanelCallbacks {
    override fun onNavigate(destination: ControlPanelDestination) = Unit

    override fun onAllowEntry() = Unit

    override fun onAllowExit() = Unit

    override fun onPassagePassengerCountChanged(value: Int) = Unit

    override fun onPassageLampColorChanged(value: String) = Unit

    override fun onRejectDirectionChanged(value: String) = Unit

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

    override fun onOpenLogDirectory() = Unit

    override fun onClearTraffic() = Unit
}

/** Complete ordered controller sensor inventory used by the external sensor panel. */
fun defaultGateSensors(): List<GateSensorUi> = gateSensors((1..19).filter { it != 10 }.toSet())

internal fun gateSensors(ids: Set<Int>): List<GateSensorUi> =
    ids.sorted().map { id ->
        val name =
            when (id) {
                10, 20, 21, 22 -> "Child/optional sensor ${id.toString().padStart(2, '0')}"
                23, 24 -> "SwingDoor sensor $id"
                25 -> "TCU path A sensor"
                26 -> "TCU path B sensor"
                else -> "Sensor ${id.toString().padStart(2, '0')}"
            }
        sensor(
            id = id,
            code = if (id <= 24) "S${id.toString().padStart(2, '0')}" else "TCU-${if (id == 25) "A" else "B"}",
            name = name,
            description = "Puloon GCU profile-specific physical input",
            group =
                when (id) {
                    in 1..6 -> SensorGroup.ENTRY
                    in 7..13, in 20..22 -> SensorGroup.SAFETY
                    in 14..19 -> SensorGroup.EXIT
                    23, 24 -> SensorGroup.MECHANISM
                    else -> SensorGroup.SECURITY
                },
        )
    }

private fun sensor(
    id: Int,
    code: String,
    name: String,
    description: String,
    group: SensorGroup,
) = GateSensorUi(id, code, name, description, group)

@Suppress("LongMethod") // Complete documented diagnostic inventory remains auditable in wire-command order.
internal fun diagnosticsFor(
    capabilities: Set<GateCapability>,
    tokenControlUnitInstalled: Boolean,
): List<DiagnosticTestUi> =
    listOf(
        DiagnosticTestUi(
            "initialize",
            "Initialize controller",
            "Run sensor, indicator, buzzer, and door initialization",
            true,
            requiredCapability = GateCapability.INITIALIZE,
        ),
        DiagnosticTestUi(
            "firmware",
            "Firmware identity",
            "Read the five-byte firmware version",
            requiredCapability = GateCapability.FIRMWARE,
        ),
        DiagnosticTestUi(
            "status",
            "Live status",
            "Refresh gate status, errors, switches, and counters",
            requiredCapability = GateCapability.STATUS,
        ),
        DiagnosticTestUi("clock-read", "Read RTC", "Read the India-profile controller clock", requiredCapability = GateCapability.CLOCK),
        DiagnosticTestUi(
            "clock-sync",
            "Synchronize RTC",
            "Set the India-profile controller clock",
            true,
            requiredCapability = GateCapability.CLOCK,
        ),
        DiagnosticTestUi("standby-read", "Read standby", "Read the Kolkata standby policy", requiredCapability = GateCapability.STANDBY),
        DiagnosticTestUi(
            "timing-read",
            "Read door timing",
            "Read opening and closing delays",
            requiredCapability = GateCapability.DOOR_TIMING,
        ),
        DiagnosticTestUi(
            "settings-read",
            "Read parameters",
            "Read the complete 12-byte parameter block",
            requiredCapability = GateCapability.SETTINGS,
        ),
        DiagnosticTestUi(
            "clear-counters",
            "Clear counters",
            "Clear counts; the controller may close the door",
            true,
            requiredCapability = GateCapability.PASSAGE_COUNTERS,
        ),
        DiagnosticTestUi(
            "sensor-bank",
            "Sensor status",
            "Read active and individually faulted profile sensors",
            requiredCapability = GateCapability.SENSORS,
        ),
        DiagnosticTestUi("door-open", "Door open test", "Run T/01 door-open output", true, requiredCapability = GateCapability.DIAGNOSTICS),
        DiagnosticTestUi(
            "door-close",
            "Door close test",
            "Run T/02 door-close output",
            true,
            requiredCapability = GateCapability.DIAGNOSTICS,
        ),
        DiagnosticTestUi("door-free", "Door free test", "Run T/03 door-free output", true, requiredCapability = GateCapability.DIAGNOSTICS),
        DiagnosticTestUi("door-lock", "Door lock test", "Run T/04 door-lock output", true, requiredCapability = GateCapability.DIAGNOSTICS),
        DiagnosticTestUi(
            "indicator-green-on",
            "Green indicator ON",
            "Run T/21 direction indicator output",
            true,
            requiredCapability = GateCapability.DIAGNOSTICS,
        ),
        DiagnosticTestUi(
            "indicator-green-off",
            "Green indicator OFF",
            "Run T/22 direction indicator output",
            true,
            requiredCapability = GateCapability.DIAGNOSTICS,
        ),
        DiagnosticTestUi(
            "indicator-blue-on",
            "Blue indicator ON",
            "Run T/23 direction indicator output",
            true,
            requiredCapability = GateCapability.DIAGNOSTICS,
        ),
        DiagnosticTestUi(
            "indicator-blue-off",
            "Blue indicator OFF",
            "Run T/24 direction indicator output",
            true,
            requiredCapability = GateCapability.DIAGNOSTICS,
        ),
        DiagnosticTestUi(
            "indicator-red-on",
            "Red indicator ON",
            "Run T/25 direction indicator output",
            true,
            requiredCapability = GateCapability.DIAGNOSTICS,
        ),
        DiagnosticTestUi(
            "indicator-red-off",
            "Red indicator OFF",
            "Run T/26 direction indicator output",
            true,
            requiredCapability = GateCapability.DIAGNOSTICS,
        ),
        DiagnosticTestUi("buzzer-1-on", "Buzzer 1 ON", "Run T/31 buzzer output", true, requiredCapability = GateCapability.DIAGNOSTICS),
        DiagnosticTestUi("buzzer-1-off", "Buzzer 1 OFF", "Run T/32 buzzer output", true, requiredCapability = GateCapability.DIAGNOSTICS),
        DiagnosticTestUi("buzzer-2-on", "Buzzer 2 ON", "Run T/33 buzzer output", true, requiredCapability = GateCapability.DIAGNOSTICS),
        DiagnosticTestUi("buzzer-2-off", "Buzzer 2 OFF", "Run T/34 buzzer output", true, requiredCapability = GateCapability.DIAGNOSTICS),
        DiagnosticTestUi("buzzer-3-on", "Buzzer 3 ON", "Run T/35 buzzer output", true, requiredCapability = GateCapability.DIAGNOSTICS),
        DiagnosticTestUi("buzzer-3-off", "Buzzer 3 OFF", "Run T/36 buzzer output", true, requiredCapability = GateCapability.DIAGNOSTICS),
        DiagnosticTestUi(
            "end-green-on",
            "End display green ON",
            "Run T/11 end-display output",
            true,
            requiredCapability = GateCapability.DIAGNOSTICS,
        ),
        DiagnosticTestUi(
            "end-green-off",
            "End display green OFF",
            "Run T/12 end-display output",
            true,
            requiredCapability = GateCapability.DIAGNOSTICS,
        ),
        DiagnosticTestUi(
            "end-yellow-on",
            "End display yellow ON",
            "Run T/13 end-display output",
            true,
            requiredCapability = GateCapability.DIAGNOSTICS,
        ),
        DiagnosticTestUi(
            "end-yellow-off",
            "End display yellow OFF",
            "Run T/14 end-display output",
            true,
            requiredCapability = GateCapability.DIAGNOSTICS,
        ),
        DiagnosticTestUi(
            "end-red-on",
            "End display red ON",
            "Run T/15 end-display output",
            true,
            requiredCapability = GateCapability.DIAGNOSTICS,
        ),
        DiagnosticTestUi(
            "end-red-off",
            "End display red OFF",
            "Run T/16 end-display output",
            true,
            requiredCapability = GateCapability.DIAGNOSTICS,
        ),
        DiagnosticTestUi(
            "return-cup-on",
            "Return-cup LED ON",
            "Run T/57 TCU lamp output",
            true,
            requiredCapability = GateCapability.DIAGNOSTICS,
        ),
        DiagnosticTestUi(
            "return-cup-off",
            "Return-cup LED OFF",
            "Run T/58 TCU lamp output",
            true,
            requiredCapability = GateCapability.DIAGNOSTICS,
        ),
        DiagnosticTestUi(
            "ups",
            "UPS and power",
            "Read parsed online, battery, and charge status",
            requiredCapability = GateCapability.UPS_SHUTDOWN,
        ),
        DiagnosticTestUi("reset", "Controller reset", "Run the hardware reset operation", true, requiredCapability = GateCapability.RESET),
    ).filter { test ->
        test.requiredCapability in capabilities &&
            (tokenControlUnitInstalled || !test.id.startsWith("return-cup"))
    }
