package com.qurkos.gate.controlpanel.app

import com.qurkos.gate.controlpanel.ui.model.ControlPanelUiState
import com.qurkos.gate.controlpanel.ui.model.IndicatorLamp
import com.qurkos.gate.controlpanel.ui.model.SensorHealth
import com.qurkos.gate.sdk.GateEmergencyState
import com.qurkos.gate.sdk.GatePassMode
import com.qurkos.gate.sdk.GateStatus

/** Maps a validated physical-controller status response into the immutable presentation model. */
internal fun ControlPanelUiState.withHardwareStatus(status: GateStatus): ControlPanelUiState =
    copy(
        passageMode = status.passMode.displayName(),
        passengerCount = status.entryCount + status.exitCount,
        gateTwin =
            gateTwin.copy(
                emergencyActive = status.emergency != GateEmergencyState.INACTIVE,
                lamp =
                    if (status.emergency == GateEmergencyState.INACTIVE) {
                        IndicatorLamp.GREEN
                    } else {
                        IndicatorLamp.RED
                    },
                sensors =
                    gateTwin.sensors.map { sensor ->
                        sensor.copy(
                            health =
                                when {
                                    status.sensors.hasFault -> SensorHealth.FAULT
                                    status.sensors.active.any { it.number == sensor.id } -> SensorHealth.ACTIVE
                                    else -> SensorHealth.CLEAR
                                },
                        )
                    },
            ),
    )

private fun GatePassMode.displayName(): String =
    name
        .lowercase()
        .split('_')
        .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
