package com.qurkos.gate.controlpanel.app

import com.qurkos.gate.controlpanel.ui.model.ConnectionHealth
import com.qurkos.gate.controlpanel.ui.model.DiagnosticState
import com.qurkos.gate.controlpanel.ui.model.FlapPosition
import com.qurkos.gate.controlpanel.ui.model.GateConfigurationUi
import com.qurkos.gate.controlpanel.ui.model.SensorHealth
import com.qurkos.gate.controlpanel.ui.model.defaultGateSensors
import com.qurkos.gate.sdk.GateCommand
import com.qurkos.gate.sdk.GateDirection
import com.qurkos.gate.sdk.GateError
import com.qurkos.gate.sdk.GateEvent
import com.qurkos.gate.sdk.GateResult
import com.qurkos.gate.sdk.GateSensorId
import com.qurkos.gate.sdk.SerialPortInfo
import com.qurkos.gate.sdk.SerialPortName
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class ControlPanelControllerTest {
    @Test
    fun mapsSemanticSdkTrafficIntoLiveConsoleRows() =
        runTest {
            val fake = FakeGate()
            val controller = controller(fake)
            controller.onConnect()
            advanceUntilIdle()

            fake.emitEvent(GateEvent.CommandSent(41, GateCommand.SENSORS, "Read physical sensor inputs", Clock.System.now()))
            advanceUntilIdle()

            val row =
                controller.state.value.traffic
                    .single()
            assertEquals("41-tx", row.id)
            assertEquals("Sensors", row.command)
            assertEquals("Read physical sensor inputs", row.detail)
            controller.onClearTraffic()
            assertTrue(
                controller.state.value.traffic
                    .isEmpty(),
            )
            controller.close()
        }

    @Test
    fun startsDisconnectedAndRequiresPhysicalConnection() =
        runTest {
            val fake = FakeGate()
            val controller = controller(fake)

            assertEquals(ConnectionHealth.DISCONNECTED, controller.state.value.connectionHealth)
            controller.onAllowEntry()

            assertTrue(
                controller.state.value.transientMessage
                    .orEmpty()
                    .contains("Connect the physical gate"),
            )
            assertTrue(fake.passageRequests.isEmpty())
            controller.close()
        }

    @Test
    fun connectLoadsIdentityStatusCountersAndAllPhysicalSensors() =
        runTest {
            val fake = FakeGate()
            val controller = controller(fake)

            controller.onConnect()
            advanceUntilIdle()

            val state = controller.state.value
            assertEquals(ConnectionHealth.CONNECTED, state.connectionHealth)
            assertEquals("Test GCU", state.controllerName)
            assertEquals("9.9.9", state.firmware)
            assertEquals(12, state.passengerCount)
            assertEquals(16, state.gateTwin.sensors.size)
            assertEquals(SensorHealth.ACTIVE, state.gateTwin.sensors[1].health)
            assertEquals(SensorHealth.ACTIVE, state.gateTwin.sensors[14].health)
            assertTrue(fake.statusReads > 0)
            controller.close()
        }

    @Test
    fun failedConnectionReturnsToDisconnectedAndReleasesTheGate() =
        runTest {
            val fake = FakeGate(GateResult.Failure(GateError.Transport("port unavailable")))
            val controller = controller(fake)

            controller.onConnect()
            advanceUntilIdle()

            assertEquals(ConnectionHealth.DISCONNECTED, controller.state.value.connectionHealth)
            assertFalse(controller.state.value.commandInProgress)
            assertEquals(1, fake.disconnectCalls)
            assertTrue(
                controller.state.value.transientMessage
                    .orEmpty()
                    .contains("port unavailable"),
            )
            controller.close()
        }

    @Test
    fun entryExitAndRejectRouteToGateAndReturnVisualTwinToClosed() =
        runTest {
            val fake = FakeGate()
            val controller = controller(fake)
            controller.onConnect()
            advanceUntilIdle()

            controller.onAllowEntry()
            advanceUntilIdle()
            controller.onAllowExit()
            advanceUntilIdle()
            controller.onReject()
            advanceUntilIdle()

            assertEquals(listOf(GateDirection.ENTRY, GateDirection.EXIT, GateDirection.ENTRY), fake.passageRequests.map { it.direction })
            assertTrue(fake.passageRequests.last().invalidTicket)
            assertEquals(FlapPosition.CLOSED, controller.state.value.gateTwin.leftFlap)
            assertEquals(0f, controller.state.value.gateTwin.leftFlapProgress)
            assertFalse(controller.state.value.commandInProgress)
            controller.close()
        }

    @Test
    fun emergencyAndResetAreSentToHardwareAndUpdateConfirmedState() =
        runTest {
            val fake = FakeGate()
            val controller = controller(fake)
            controller.onConnect()
            advanceUntilIdle()

            controller.onEmergencyStop()
            advanceUntilIdle()
            assertEquals(listOf(true), fake.emergencyRequests)
            assertTrue(controller.state.value.gateTwin.emergencyActive)
            assertEquals(FlapPosition.OPEN, controller.state.value.gateTwin.leftFlap)

            controller.onEmergencyReset()
            advanceUntilIdle()
            assertEquals(listOf(true, false), fake.emergencyRequests)
            assertFalse(controller.state.value.gateTwin.emergencyActive)
            assertEquals(FlapPosition.CLOSED, controller.state.value.gateTwin.leftFlap)
            controller.close()
        }

    @Test
    fun diagnosticsUseReadOnlyCommandsWithoutMaintenanceAndGateActuationWithOptIn() =
        runTest {
            val fake = FakeGate()
            val controller = controller(fake)
            controller.onConnect()
            advanceUntilIdle()

            controller.onDiagnosticRun("sensor-bank")
            advanceUntilIdle()
            assertEquals(1, fake.sensorReads)
            assertEquals(
                DiagnosticState.PASSED,
                controller.state.value.diagnostics
                    .first { it.id == "sensor-bank" }
                    .state,
            )

            controller.onDiagnosticRun("buzzer")
            assertTrue(fake.diagnostics.isEmpty())
            controller.onConfigurationChanged(GateConfigurationUi(maintenanceOperationsEnabled = true))
            controller.onDiagnosticRun("buzzer")
            advanceUntilIdle()
            assertEquals(1, fake.diagnostics.size)
            controller.close()
        }

    @Test
    fun configurationSaveRoutesEveryTypedControllerSetting() =
        runTest {
            val fake = FakeGate()
            val controller = controller(fake)
            controller.onConnect()
            advanceUntilIdle()

            controller.onConfigurationChanged(GateConfigurationUi(hasUnsavedChanges = true))
            controller.onSaveConfiguration()
            advanceUntilIdle()

            assertEquals(1, fake.passModes.size)
            assertEquals(1, fake.safetyRegions.size)
            assertEquals(listOf(30), fake.upsDelays)
            assertEquals(1, fake.standbyWrites.size)
            assertEquals(1, fake.timingWrites.size)
            assertEquals(8, fake.settingWrites.single().size)
            assertFalse(controller.state.value.configuration.hasUnsavedChanges)
            controller.close()
        }

    @Test
    fun fullOperationsGridRoutesReadMaintenanceRtcCounterAndResetCommands() =
        runTest {
            val fake = FakeGate()
            val controller = controller(fake)
            controller.onConnect()
            advanceUntilIdle()
            controller.onConfigurationChanged(GateConfigurationUi(maintenanceOperationsEnabled = true))

            controller.onDiagnosticRunAll()
            advanceUntilIdle()

            assertEquals(1, fake.initializeCalls)
            assertEquals(1, fake.clockWrites.size)
            assertEquals(1, fake.clearCounterCalls)
            assertEquals(1, fake.resetCalls)
            assertTrue(
                controller.state.value.diagnostics
                    .all { it.state == DiagnosticState.PASSED },
            )
            controller.close()
        }

    @Test
    fun sensorInventoryContainsEightOrderedLeftRightPairs() {
        val sensors = defaultGateSensors()

        assertEquals(16, sensors.size)
        sensors.chunked(2).forEach { pair ->
            assertTrue(pair[0].code.endsWith("-L"))
            assertTrue(pair[1].code.endsWith("-R"))
        }
        assertEquals((1..16).toList(), sensors.map { it.id })
    }

    @Test
    fun statusFaultMarksEverySensorFaulted() =
        runTest {
            val fake = FakeGate()
            val controller = controller(fake)
            controller.onConnect()
            advanceUntilIdle()

            fake.publishStatus(FakeGate.sampleStatus(setOf(GateSensorId(4)), sensorFault = true))
            advanceUntilIdle()

            assertTrue(
                controller.state.value.gateTwin.sensors
                    .all { it.health == SensorHealth.FAULT },
            )
            controller.close()
        }

    private fun kotlinx.coroutines.test.TestScope.controller(fake: FakeGate): ControlPanelController =
        ControlPanelController(
            dispatcher = StandardTestDispatcher(testScheduler),
            gateFactory = { GateResult.Success(fake) },
            serialPortProvider = {
                GateResult.Success(listOf(SerialPortInfo(SerialPortName("/dev/ttyUSB0"), "Test serial port")))
            },
        )
}
