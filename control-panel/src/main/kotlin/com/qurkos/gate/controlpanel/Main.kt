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
import com.qurkos.gate.controlpanel.app.ApplicationLogging
import com.qurkos.gate.controlpanel.app.ControlPanelController
import com.qurkos.gate.controlpanel.ui.ControlPanel
import java.util.logging.Level

/** Launches the standalone hardware-only AFC gate control panel. */
@Suppress("TooGenericExceptionCaught") // The process boundary must record every fatal failure before rethrowing it.
fun main() {
    val logger = ApplicationLogging.logger("Main")
    ApplicationLogging.installUncaughtExceptionHandler()
    logger.info("AFC Gate Control Panel starting ${ApplicationLogging.runtimeSummary()}")
    try {
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
    } catch (error: Throwable) {
        logger.log(Level.SEVERE, "AFC Gate Control Panel stopped unexpectedly", error)
        throw error
    } finally {
        logger.info("AFC Gate Control Panel stopped")
        ApplicationLogging.close()
    }
}
