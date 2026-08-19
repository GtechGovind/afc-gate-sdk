package com.qurkos.gate.controlpanel.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qurkos.gate.controlpanel.ui.components.PageTitle
import com.qurkos.gate.controlpanel.ui.components.PanelCard
import com.qurkos.gate.controlpanel.ui.model.ConnectionHealth
import com.qurkos.gate.controlpanel.ui.model.ControlPanelCallbacks
import com.qurkos.gate.controlpanel.ui.model.ControlPanelUiState
import com.qurkos.gate.controlpanel.ui.model.DiagnosticState
import com.qurkos.gate.controlpanel.ui.model.DiagnosticTestUi

/** Dense maintenance console that exposes every supported hardware diagnostic in one view. */
@Composable
internal fun DiagnosticsScreen(
    state: ControlPanelUiState,
    callbacks: ControlPanelCallbacks,
    modifier: Modifier = Modifier,
) {
    val connected = state.connectionHealth == ConnectionHealth.CONNECTED
    val maintenanceEnabled = state.configuration.maintenanceOperationsEnabled
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageTitle("Diagnostics", "Read controller health or run protected maintenance operations") {
            Button(
                onClick = callbacks::onDiagnosticRunAll,
                enabled =
                    connected &&
                        maintenanceEnabled &&
                        !state.commandInProgress &&
                        state.diagnostics.none { it.state == DiagnosticState.RUNNING },
            ) {
                Icon(Icons.Outlined.PlayArrow, null)
                Text("Run all", Modifier.padding(start = 8.dp))
            }
        }
        DiagnosticNotice(connected = connected, maintenanceEnabled = maintenanceEnabled)
        LazyVerticalGrid(
            columns = GridCells.Fixed(DIAGNOSTIC_COLUMNS),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.diagnostics, key = DiagnosticTestUi::id) { test ->
                DiagnosticCard(
                    test = test,
                    enabled =
                        connected &&
                            !state.commandInProgress &&
                            (!test.requiresMaintenance || maintenanceEnabled),
                    onRun = { callbacks.onDiagnosticRun(test.id) },
                )
            }
        }
    }
}

@Composable
private fun DiagnosticNotice(
    connected: Boolean,
    maintenanceEnabled: Boolean,
) {
    PanelCard(Modifier.fillMaxWidth()) {
        val message =
            when {
                !connected -> "Connect the physical gate to run diagnostics. No operation is simulated."
                !maintenanceEnabled ->
                    "Read-only checks are available. Enable maintenance operations before actuator, lamp, buzzer, or reset tests."
                else -> "Maintenance operations are enabled. Actuator tests may move the physical gate."
            }
        Text(
            message,
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color =
                if (connected && maintenanceEnabled) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.tertiary
                },
        )
    }
}

@Composable
private fun DiagnosticCard(
    test: DiagnosticTestUi,
    enabled: Boolean,
    onRun: () -> Unit,
) {
    PanelCard(Modifier.fillMaxWidth().height(DIAGNOSTIC_CARD_HEIGHT)) {
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(test.state.icon, test.state.name, tint = test.state.tint)
                    Text(test.state.displayName, style = MaterialTheme.typography.labelMedium, color = test.state.tint)
                }
                if (test.requiresMaintenance) {
                    Text("Protected", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                }
            }
            Text(test.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                test.result ?: test.description,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = if (test.result == null) MaterialTheme.colorScheme.onSurfaceVariant else test.state.tint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            OutlinedButton(
                onClick = onRun,
                enabled = enabled && test.state != DiagnosticState.RUNNING,
                modifier = Modifier.fillMaxWidth().height(34.dp),
                contentPadding =
                    androidx.compose.foundation.layout
                        .PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) {
                Text(if (test.state == DiagnosticState.RUNNING) "Running…" else "Run")
            }
        }
    }
}

private val DiagnosticState.icon: ImageVector
    get() =
        when (this) {
            DiagnosticState.IDLE -> Icons.Outlined.RadioButtonUnchecked
            DiagnosticState.RUNNING -> Icons.Outlined.HourglassEmpty
            DiagnosticState.PASSED -> Icons.Outlined.CheckCircle
            DiagnosticState.FAILED -> Icons.Outlined.ErrorOutline
        }

private val DiagnosticState.tint: androidx.compose.ui.graphics.Color
    @Composable get() =
        when (this) {
            DiagnosticState.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
            DiagnosticState.RUNNING -> MaterialTheme.colorScheme.primary
            DiagnosticState.PASSED -> MaterialTheme.colorScheme.secondary
            DiagnosticState.FAILED -> MaterialTheme.colorScheme.error
        }

private val DiagnosticState.displayName: String
    get() = name.lowercase().replaceFirstChar(Char::uppercase)

private const val DIAGNOSTIC_COLUMNS = 4
private val DIAGNOSTIC_CARD_HEIGHT = 142.dp
