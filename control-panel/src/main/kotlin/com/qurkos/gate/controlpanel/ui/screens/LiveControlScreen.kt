package com.qurkos.gate.controlpanel.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qurkos.gate.controlpanel.ui.components.GateTwin
import com.qurkos.gate.controlpanel.ui.components.PanelCard
import com.qurkos.gate.controlpanel.ui.components.SafetyHoldButton
import com.qurkos.gate.controlpanel.ui.model.ConnectionHealth
import com.qurkos.gate.controlpanel.ui.model.ControlPanelCallbacks
import com.qurkos.gate.controlpanel.ui.model.ControlPanelUiState
import com.qurkos.gate.controlpanel.ui.model.GateEventUi
import com.qurkos.gate.controlpanel.ui.model.GateTrafficUi
import com.qurkos.gate.controlpanel.ui.model.TrafficDirection
import com.qurkos.gate.sdk.GateCapability

/** Primary physical-gate screen for authorization, rejection, emergency, and confirmed motion. */
@Composable
internal fun LiveControlScreen(
    state: ControlPanelUiState,
    callbacks: ControlPanelCallbacks,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            GateTwin(
                state = state.gateTwin,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            CommandPanel(state, callbacks, Modifier.fillMaxHeight().fillMaxWidth(.30f))
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(LIVE_FEED_HEIGHT),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LiveEvents(state, Modifier.weight(.43f).fillMaxHeight())
            TrafficConsole(state, callbacks, Modifier.weight(.57f).fillMaxHeight())
        }
    }
}

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod") // Capability branches deliberately colocate all passage controls.
private fun CommandPanel(
    state: ControlPanelUiState,
    callbacks: ControlPanelCallbacks,
    modifier: Modifier,
) {
    val hardwareReady =
        state.connectionHealth == com.qurkos.gate.controlpanel.ui.model.ConnectionHealth.CONNECTED &&
            !state.commandInProgress
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.connectionHealth != ConnectionHealth.CONNECTED) {
            DisconnectedNotice()
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TelemetryCard("Passage mode", state.passageMode, Modifier.weight(1f))
            TelemetryCard("Passengers", state.passengerCount?.toString() ?: "—", Modifier.weight(1f))
        }
        TelemetryCard("Controller status", state.controllerStatusDetail, Modifier.fillMaxWidth())
        if (GateCapability.UPS_SHUTDOWN in state.supportedCapabilities) {
            TelemetryCard("Power / UPS", state.powerStatusDetail, Modifier.fillMaxWidth())
        }
        if (state.configuration.tokenControlUnitInstalled) {
            TelemetryCard("Token control unit", state.tokenStatusDetail, Modifier.fillMaxWidth())
        }
        Text("Passage controls", style = MaterialTheme.typography.titleMedium)
        if (GateCapability.MULTI_PERSON_PASSAGE in state.supportedCapabilities) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Passenger count", style = MaterialTheme.typography.bodyMedium)
                Row {
                    TextButton(
                        onClick = { callbacks.onPassagePassengerCountChanged(state.passagePassengerCount - 1) },
                        enabled = state.passagePassengerCount > 1,
                    ) { Text("−") }
                    Text(state.passagePassengerCount.toString(), Modifier.padding(horizontal = 8.dp, vertical = 12.dp))
                    TextButton(
                        onClick = { callbacks.onPassagePassengerCountChanged(state.passagePassengerCount + 1) },
                        enabled = state.passagePassengerCount < 99,
                    ) { Text("+") }
                }
            }
        }
        if (GateCapability.PASSAGE_LAMP in state.supportedCapabilities) {
            OptionDropdown(
                "Passage indicator",
                state.passageLampColor,
                callbacks::onPassageLampColorChanged,
                listOf(
                    "GREEN",
                    "BLUE",
                    "RED",
                    "YELLOW",
                    "OFF",
                ).map { SelectionOption(it, it.lowercase().replaceFirstChar(Char::uppercase)) },
            )
        }
        if (GateCapability.PASSAGE in state.supportedCapabilities) {
            Button(
                onClick = callbacks::onAllowEntry,
                enabled = hardwareReady && !state.gateTwin.emergencyActive,
                modifier = Modifier.fillMaxWidth(),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, null)
                Text("Allow Entry", Modifier.padding(vertical = 9.dp))
            }
            OutlinedButton(
                onClick = callbacks::onAllowExit,
                enabled = hardwareReady && !state.gateTwin.emergencyActive,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant),
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                Text("Allow Exit", Modifier.padding(vertical = 9.dp))
            }
        }
        if (GateCapability.INVALID_TICKET in state.supportedCapabilities) {
            OptionDropdown(
                "Reject direction",
                state.rejectDirection,
                callbacks::onRejectDirectionChanged,
                listOf(SelectionOption("ENTRY", "Entry"), SelectionOption("EXIT", "Exit")),
            )
            OutlinedButton(
                onClick = callbacks::onReject,
                enabled = hardwareReady && !state.gateTwin.emergencyActive,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant),
            ) {
                Icon(Icons.Outlined.Block, null)
                Text("Reject Passage", Modifier.padding(vertical = 9.dp))
            }
        }
        if (GateCapability.EMERGENCY in state.supportedCapabilities) {
            HorizontalDivider(Modifier.padding(vertical = 2.dp))
            SafetyHoldButton(
                label = if (state.gateTwin.emergencyActive) "CLEAR EMERGENCY RELEASE" else "EMERGENCY RELEASE",
                holdProgress = state.safetyHoldProgress,
                enabled = hardwareReady,
                onHoldCompleted = if (state.gateTwin.emergencyActive) callbacks::onEmergencyReset else callbacks::onEmergencyStop,
            )
        }
    }
}

@Composable
private fun DisconnectedNotice() {
    Surface(
        color = MaterialTheme.colorScheme.tertiary.copy(alpha = .08f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = .45f)),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Gate disconnected", style = MaterialTheme.typography.titleSmall)
            Text(
                "Connect the physical gate to read telemetry and enable commands.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TelemetryCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    PanelCard(modifier) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
    }
}

@Composable
private fun LiveEvents(
    state: ControlPanelUiState,
    modifier: Modifier = Modifier,
) {
    PanelCard(modifier) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("Live Events", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            state.events.take(LIVE_EVENT_PREVIEW_ROWS).forEach { event ->
                HorizontalDivider(color = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant)
                CompactEventRow(event)
            }
            if (state.events.isEmpty()) {
                Text(
                    "No activity yet",
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CompactEventRow(event: GateEventUi) {
    Column(Modifier.padding(horizontal = 8.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(event.timestamp, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                event.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(event.severity.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            event.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Latest semantic SDK requests and responses, correlated without exposing raw vendor frames. */
@Composable
private fun TrafficConsole(
    state: ControlPanelUiState,
    callbacks: ControlPanelCallbacks,
    modifier: Modifier = Modifier,
) {
    PanelCard(modifier) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Command Traffic", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = callbacks::onClearTraffic, enabled = state.traffic.isNotEmpty()) {
                    Text("Clear")
                }
            }
            if (state.traffic.isEmpty()) {
                Text(
                    "TX requests and correlated RX responses will appear after connection.",
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                state.traffic.take(TRAFFIC_PREVIEW_ROWS).forEach { traffic ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TrafficRow(traffic)
                }
            }
        }
    }
}

@Composable
private fun TrafficRow(traffic: GateTrafficUi) {
    val accent =
        when {
            traffic.failed -> MaterialTheme.colorScheme.error
            traffic.direction == TrafficDirection.TX -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.secondary
        }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(traffic.timestamp, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(traffic.direction.name, style = MaterialTheme.typography.labelMedium, color = accent, fontWeight = FontWeight.Bold)
        Text(
            traffic.command,
            modifier = Modifier.weight(.28f),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            traffic.detail,
            modifier = Modifier.weight(.52f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            traffic.latencyMs?.let { "$it ms" } ?: "—",
            modifier = Modifier.weight(.12f),
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            maxLines = 1,
        )
    }
}

private const val TRAFFIC_PREVIEW_ROWS = 3
private const val LIVE_EVENT_PREVIEW_ROWS = 1
private val LIVE_FEED_HEIGHT = 154.dp
