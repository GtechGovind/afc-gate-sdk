package com.qurkos.gate.controlpanel.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qurkos.gate.controlpanel.ui.components.PageTitle
import com.qurkos.gate.controlpanel.ui.components.PanelCard
import com.qurkos.gate.controlpanel.ui.model.ControlPanelCallbacks
import com.qurkos.gate.controlpanel.ui.model.ControlPanelUiState
import com.qurkos.gate.controlpanel.ui.model.GateConfigurationUi
import com.qurkos.gate.controlpanel.ui.model.PuloonInputRules
import com.qurkos.gate.controlpanel.ui.model.hasValidInputs

/** Operational and serial configuration form with explicit save and discard semantics. */
@Composable
internal fun ConfigurationScreen(
    state: ControlPanelUiState,
    callbacks: ControlPanelCallbacks,
    modifier: Modifier = Modifier,
) {
    var selectedSection by remember { mutableStateOf(ConfigurationSection.CONNECTION) }
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageTitle(
            title = "Configuration",
            subtitle = "Review changes before applying them to the controller",
        ) {
            OutlinedButton(
                onClick = callbacks::onDiscardConfiguration,
                enabled = state.configuration.hasUnsavedChanges,
            ) { Text("Discard") }
            Button(
                onClick = callbacks::onSaveConfiguration,
                enabled =
                    state.configuration.hasUnsavedChanges &&
                        state.configuration.hasValidInputs() &&
                        !state.commandInProgress,
            ) { Text("Save configuration") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ConfigurationSection.entries.forEach { section ->
                FilterChip(
                    selected = selectedSection == section,
                    onClick = { selectedSection = section },
                    label = { Text(section.title) },
                )
            }
        }
        when (selectedSection) {
            ConfigurationSection.CONNECTION -> SerialSettings(state, callbacks)
            ConfigurationSection.PASSAGE -> TimingSettings(state.configuration, callbacks)
            ConfigurationSection.CONTROLLER -> ControllerSettings(state.configuration, callbacks)
            ConfigurationSection.MAINTENANCE -> SafetySettings(state.configuration, callbacks)
        }
    }
}

@Composable
private fun ControllerSettings(
    configuration: GateConfigurationUi,
    callbacks: ControlPanelCallbacks,
) {
    fun update(value: GateConfigurationUi) = callbacks.onConfigurationChanged(value.copy(hasUnsavedChanges = true))
    SettingsSection("Controller settings", "Applied through typed SDK operations when a gate is connected") {
        ControllerNumericSettings(configuration, ::update)
        ToggleSetting("Normal-open mode", "Keep the barrier normally open", configuration.normalOpenMode) {
            update(configuration.copy(normalOpenMode = it))
        }
        ToggleSetting("Child detection", "Enable child-height safety behavior", configuration.childDetection) {
            update(configuration.copy(childDetection = it))
        }
        ToggleSetting(
            "Tag timeout from last tag",
            "Measure the timeout from the most recent credential",
            configuration.tagTimeoutFromLastTag,
        ) { update(configuration.copy(tagTimeoutFromLastTag = it)) }
    }
}

@Composable
private fun ControllerNumericSettings(
    configuration: GateConfigurationUi,
    update: (GateConfigurationUi) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OptionDropdown(
                "Safety region",
                configuration.safetyRegion,
                { update(configuration.copy(safetyRegion = it)) },
                SAFETY_REGION_OPTIONS,
                Modifier.weight(1f),
            )
            NumericSettingField(
                "UPS shutdown delay (s)",
                configuration.upsShutdownDelaySeconds,
                { update(configuration.copy(upsShutdownDelaySeconds = it)) },
                PuloonInputRules.upsShutdownDelay,
                Modifier.weight(1f),
            )
            NumericSettingField(
                "Standby timeout (s)",
                configuration.standbyTimeoutSeconds,
                { update(configuration.copy(standbyTimeoutSeconds = it)) },
                PuloonInputRules.standbyTimeout,
                Modifier.weight(1f),
            )
        }
        PassModeDropdown(
            "Standby passage mode",
            configuration.standbyPassMode,
            { update(configuration.copy(standbyPassMode = it)) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            NumericSettingField(
                "Sensor sensitivity",
                configuration.sensorSensitivity,
                { update(configuration.copy(sensorSensitivity = it)) },
                PuloonInputRules.byteSetting,
                Modifier.weight(1f),
            )
            NumericSettingField(
                "Passage timeout (s)",
                configuration.passageTimeoutSeconds,
                { update(configuration.copy(passageTimeoutSeconds = it)) },
                PuloonInputRules.byteSetting,
                Modifier.weight(1f),
            )
            NumericSettingField(
                "Child height",
                configuration.childHeight,
                { update(configuration.copy(childHeight = it)) },
                PuloonInputRules.byteSetting,
                Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OptionDropdown(
                "Tailing sensitivity",
                configuration.tailingSensitivity,
                { update(configuration.copy(tailingSensitivity = it)) },
                LEVEL_OPTIONS,
                Modifier.weight(1f),
            )
            OptionDropdown(
                "Hurry-up level",
                configuration.hurryUpLevel,
                { update(configuration.copy(hurryUpLevel = it)) },
                LEVEL_OPTIONS,
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SerialSettings(
    state: ControlPanelUiState,
    callbacks: ControlPanelCallbacks,
) {
    val configuration = state.configuration
    SettingsSection("Connection", "Serial transport and retry behavior") {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SerialPortDropdown(
                selectedPort = configuration.serialPort,
                ports = state.availableSerialPorts,
                discoveryError = state.serialPortDiscoveryError,
                onSelected = {
                    callbacks.onConfigurationChanged(configuration.copy(serialPort = it, hasUnsavedChanges = true))
                },
                onRefresh = callbacks::onRefreshSerialPorts,
                modifier = Modifier.weight(2f),
            )
            NumericSettingField(
                "Baud rate",
                configuration.baudRate,
                { callbacks.onConfigurationChanged(configuration.copy(baudRate = it, hasUnsavedChanges = true)) },
                PuloonInputRules.baudRate,
                Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            NumericSettingField(
                "Response timeout (ms)",
                configuration.responseTimeoutMs,
                { callbacks.onConfigurationChanged(configuration.copy(responseTimeoutMs = it, hasUnsavedChanges = true)) },
                PuloonInputRules.responseTimeout,
                Modifier.weight(1f),
            )
            NumericSettingField(
                "Status poll interval (ms)",
                configuration.pollIntervalMs,
                { callbacks.onConfigurationChanged(configuration.copy(pollIntervalMs = it, hasUnsavedChanges = true)) },
                PuloonInputRules.pollInterval,
                Modifier.weight(1f),
            )
        }
        ToggleSetting(
            "Automatic reconnection",
            "Reconnect monitoring after a transport failure; commands are never replayed",
            configuration.reconnectAutomatically,
        ) {
            callbacks.onConfigurationChanged(configuration.copy(reconnectAutomatically = it, hasUnsavedChanges = true))
        }
    }
}

@Composable
private fun TimingSettings(
    configuration: GateConfigurationUi,
    callbacks: ControlPanelCallbacks,
) {
    SettingsSection("Passage", "Mode and mechanical timing") {
        PassModeDropdown(
            "Passage mode",
            configuration.passageMode,
            { callbacks.onConfigurationChanged(configuration.copy(passageMode = it, hasUnsavedChanges = true)) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            NumericSettingField(
                "Open duration (ms)",
                configuration.openDurationMs,
                { callbacks.onConfigurationChanged(configuration.copy(openDurationMs = it, hasUnsavedChanges = true)) },
                PuloonInputRules.doorTiming,
                Modifier.weight(1f),
            )
            NumericSettingField(
                "Close delay (ms)",
                configuration.closeDelayMs,
                { callbacks.onConfigurationChanged(configuration.copy(closeDelayMs = it, hasUnsavedChanges = true)) },
                PuloonInputRules.doorTiming,
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SafetySettings(
    configuration: GateConfigurationUi,
    callbacks: ControlPanelCallbacks,
) {
    SettingsSection("Maintenance safety", "Hardware actuation is disabled by default") {
        ToggleSetting(
            "Enable maintenance operations",
            "Allows diagnostics that move flaps, lamps, or the buzzer",
            configuration.maintenanceOperationsEnabled,
        ) {
            callbacks.onConfigurationChanged(
                configuration.copy(maintenanceOperationsEnabled = it, hasUnsavedChanges = true),
            )
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    PanelCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            Text(subtitle, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            content()
        }
    }
}

private enum class ConfigurationSection(
    val title: String,
) {
    CONNECTION("Connection"),
    PASSAGE("Passage"),
    CONTROLLER("Controller settings"),
    MAINTENANCE("Maintenance safety"),
}
