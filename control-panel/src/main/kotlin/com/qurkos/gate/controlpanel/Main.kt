package com.qurkos.gate.controlpanel

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.qurkos.gate.controlpanel.app.ControlPanelController
import com.qurkos.gate.controlpanel.ui.ControlPanel

/** Launches the standalone hardware-only AFC gate control panel. */
fun main() =
    application {
        val controller = remember { ControlPanelController() }
        val state by controller.state.collectAsState()
        val windowState =
            rememberWindowState(
                position = WindowPosition.Aligned(androidx.compose.ui.Alignment.Center),
                size = DpSize(1440.dp, 960.dp),
            )
        Window(
            onCloseRequest = {
                controller.close()
                exitApplication()
            },
            state = windowState,
            title = "AFC Gate Control Panel",
        ) {
            ControlPanel(state = state, callbacks = controller)
        }
    }
