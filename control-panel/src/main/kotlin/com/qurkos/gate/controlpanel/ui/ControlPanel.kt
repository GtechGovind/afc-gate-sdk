package com.qurkos.gate.controlpanel.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qurkos.gate.controlpanel.ui.components.ControlPanelHeader
import com.qurkos.gate.controlpanel.ui.components.ControlPanelSidebar
import com.qurkos.gate.controlpanel.ui.model.ControlPanelCallbacks
import com.qurkos.gate.controlpanel.ui.model.ControlPanelDestination
import com.qurkos.gate.controlpanel.ui.model.ControlPanelUiState
import com.qurkos.gate.controlpanel.ui.screens.ConfigurationScreen
import com.qurkos.gate.controlpanel.ui.screens.DiagnosticsScreen
import com.qurkos.gate.controlpanel.ui.screens.EventLogScreen
import com.qurkos.gate.controlpanel.ui.screens.LiveControlScreen
import com.qurkos.gate.controlpanel.ui.screens.SensorsScreen
import com.qurkos.gate.controlpanel.ui.theme.AfcControlPanelTheme

/**
 * Renders the complete desktop operations console from immutable [state].
 *
 * The host owns state, performs all SDK calls, and handles every user intent through [callbacks]. The UI never
 * assumes that a hardware command succeeded: visual state is updated only from confirmed controller feedback.
 */
@Composable
fun ControlPanel(
    state: ControlPanelUiState,
    callbacks: ControlPanelCallbacks,
    modifier: Modifier = Modifier,
) {
    AfcControlPanelTheme {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                ControlPanelSidebar(
                    selected = state.destination,
                    onNavigate = callbacks::onNavigate,
                    modifier = Modifier.width(220.dp),
                )
                Column(Modifier.weight(1f)) {
                    ControlPanelHeader(
                        controllerName = state.controllerName,
                        serialPort = state.configuration.serialPort,
                        firmware = state.firmware,
                        connectionHealth = state.connectionHealth,
                        connectionActionEnabled =
                            !state.commandInProgress &&
                                (
                                    state.connectionHealth ==
                                        com.qurkos.gate.controlpanel.ui.model.ConnectionHealth.CONNECTED ||
                                        state.availableSerialPorts.any { it.name == state.configuration.serialPort }
                                ),
                        onConnectionClick =
                            if (
                                state.connectionHealth == com.qurkos.gate.controlpanel.ui.model.ConnectionHealth.CONNECTED
                            ) {
                                callbacks::onDisconnect
                            } else {
                                callbacks::onConnect
                            },
                        modifier = Modifier.height(80.dp),
                    )
                    AnimatedContent(
                        targetState = state.destination,
                        transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) },
                        label = "control-panel-destination",
                        modifier = Modifier.weight(1f),
                    ) { destination ->
                        DestinationContent(destination, state, callbacks)
                    }
                }
            }
            state.transientMessage?.let { message ->
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.BottomCenter) {
                    Surface(
                        color = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        shape = MaterialTheme.shapes.small,
                        shadowElevation = 8.dp,
                    ) {
                        Text(text = message, modifier = Modifier.width(420.dp).padding(14.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DestinationContent(
    destination: ControlPanelDestination,
    state: ControlPanelUiState,
    callbacks: ControlPanelCallbacks,
) {
    when (destination) {
        ControlPanelDestination.LIVE_CONTROL -> LiveControlScreen(state, callbacks)
        ControlPanelDestination.SENSORS -> SensorsScreen(state, callbacks)
        ControlPanelDestination.CONFIGURATION -> ConfigurationScreen(state, callbacks)
        ControlPanelDestination.DIAGNOSTICS -> DiagnosticsScreen(state, callbacks)
        ControlPanelDestination.EVENT_LOG -> EventLogScreen(state, callbacks)
    }
}
