package com.qurkos.gate.controlpanel.app

import com.qurkos.gate.controlpanel.ui.model.ControlPanelUiState
import com.qurkos.gate.controlpanel.ui.model.IndicatorLamp
import com.qurkos.gate.controlpanel.ui.model.SensorHealth
import com.qurkos.gate.sdk.GateEmergencyState
import com.qurkos.gate.sdk.GatePassMode
import com.qurkos.gate.sdk.GateSensorStatus
import com.qurkos.gate.sdk.GateStatus

/** Maps a validated physical-controller status response into the immutable presentation model. */
@Suppress("CyclomaticComplexMethod") // Every optional status field is rendered from one consistent snapshot.
internal fun ControlPanelUiState.withHardwareStatus(status: GateStatus): ControlPanelUiState =
    copy(
        passageMode = status.passMode.displayName(),
        passengerCount = status.entryCount + status.exitCount,
        upsChargePercent = status.power?.chargePercent,
        controllerStatusDetail = status.controllerDetail(),
        powerStatusDetail =
            status.power?.let { power ->
                listOfNotNull(
                    power.online?.let { if (it) "Mains online" else "Mains offline" },
                    power.onBattery?.let { if (it) "On battery" else "Not on battery" },
                    power.chargePercent?.let { "$it%" },
                    power.summary,
                ).distinct().joinToString(" · ")
            } ?: "Not configured",
        tokenStatusDetail =
            if (status.tokenPathACount != null || status.tokenPathBCount != null || status.returnCupOccupied != null) {
                "Path A ${status.tokenPathACount ?: "—"} · Path B ${status.tokenPathBCount ?: "—"} · Cup ${if (status.returnCupOccupied == true) "occupied" else "clear"}"
            } else {
                "Not configured"
            },
        gateTwin =
            gateTwin.copy(
                emergencyActive = status.emergency != GateEmergencyState.INACTIVE,
                lamp =
                    if (status.emergency == GateEmergencyState.INACTIVE) {
                        IndicatorLamp.GREEN
                    } else {
                        IndicatorLamp.BLUE
                    },
            ),
    )

private fun GateStatus.controllerDetail(): String =
    buildList {
        add(passageResult.name.humanize())
        if (entryError.name != "NORMAL") add("Entry ${entryError.name.humanize()}")
        if (exitError.name != "NORMAL") add("Exit ${exitError.name.humanize()}")
        if (doorFaults.isNotEmpty()) add("Door fault: ${doorFaults.joinToString { it.name.humanize() }}")
        if (occupiedZones.isNotEmpty()) add("Occupied: ${occupiedZones.joinToString { it.name.humanize() }}")
        val activeSwitches = switches.filterValues { it }.keys
        val activeInputs = inputs.filterValues { it }.keys
        if (activeSwitches.isNotEmpty()) add("Switches ${activeSwitches.joinToString()}")
        if (activeInputs.isNotEmpty()) add("Inputs ${activeInputs.joinToString()}")
    }.joinToString(" · ")

private fun String.humanize(): String = lowercase().replace('_', ' ')

/** Maps the dedicated H response, whose first six bytes are active sensors and last six are failures. */
internal fun ControlPanelUiState.withSensorStatus(status: GateSensorStatus): ControlPanelUiState =
    copy(
        gateTwin =
            gateTwin.copy(
                sensors =
                    gateTwin.sensors.map { sensor ->
                        sensor.copy(
                            health =
                                when {
                                    status.faulted.any { it.number == sensor.id } -> SensorHealth.FAULT
                                    status.active.any { it.number == sensor.id } -> SensorHealth.ACTIVE
                                    else -> SensorHealth.CLEAR
                                },
                            lastChanged = "Latest hardware read",
                        )
                    },
            ),
    )

private fun GatePassMode.displayName(): String =
    name
        .lowercase()
        .split('_')
        .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
