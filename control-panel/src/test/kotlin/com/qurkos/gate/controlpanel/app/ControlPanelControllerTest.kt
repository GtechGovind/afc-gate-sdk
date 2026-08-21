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
            assertEquals(18, state.gateTwin.sensors.size)
            assertEquals(
                SensorHealth.ACTIVE,
                state.gateTwin.sensors
                    .first { it.id == 2 }
                    .health,
            )
            assertEquals(
                SensorHealth.ACTIVE,
                state.gateTwin.sensors
                    .first { it.id == 15 }
                    .health,
            )
            assertEquals(1, fake.statusReads)
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

            val readsBeforeDiagnostic = fake.sensorReads
            controller.onDiagnosticRun("sensor-bank")
            advanceUntilIdle()
            assertEquals(readsBeforeDiagnostic + 1, fake.sensorReads)
            assertEquals(
                DiagnosticState.PASSED,
                controller.state.value.diagnostics
                    .first { it.id == "sensor-bank" }
                    .state,
            )

            controller.onDiagnosticRun("buzzer-1-on")
            assertTrue(fake.diagnostics.isEmpty())
            controller.onDisconnect()
            advanceUntilIdle()
            controller.onConfigurationChanged(GateConfigurationUi(maintenanceOperationsEnabled = true))
            controller.onConnect()
            advanceUntilIdle()
            controller.onDiagnosticRun("buzzer-1-on")
            advanceUntilIdle()
            assertEquals(1, fake.diagnostics.size)
            controller.close()
        }

    @Test
    fun configurationSaveWritesOnlyTheControllerGroupEditedByTheOperator() =
        runTest {
            val fake = FakeGate()
            val controller = controller(fake)
            controller.onConnect()
            advanceUntilIdle()

            controller.onConfigurationChanged(
                controller.state.value.configuration
                    .copy(safetyRegion = "2"),
            )
            controller.onSaveConfiguration()
            advanceUntilIdle()

            assertTrue(fake.passModes.isEmpty())
            assertEquals(1, fake.safetyRegions.size)
            assertTrue(fake.upsDelays.isEmpty())
            assertTrue(fake.standbyWrites.isEmpty())
            assertTrue(fake.timingWrites.isEmpty())
            assertTrue(fake.settingWrites.isEmpty())
            assertFalse(controller.state.value.configuration.hasUnsavedChanges)
            controller.close()
        }

    @Test
    fun failedConfigurationWriteKeepsUnconfirmedFieldsDirtyAndPreservesConfirmedFields() =
        runTest {
            val fake = FakeGate(passModeResult = GateResult.Failure(GateError.Device("1", "rejected")))
            val controller = controller(fake)
            controller.onConnect()
            advanceUntilIdle()
            val connected = controller.state.value.configuration
            controller.onConfigurationChanged(
                connected.copy(
                    noEntryTimeoutSeconds = "11",
                    passageMode = "FREE_ENTRY_LOCKED_EXIT_NORMAL_CLOSED",
                ),
            )

            controller.onSaveConfiguration()
            advanceUntilIdle()

            assertEquals(1, fake.settingWrites.size)
            assertEquals(1, fake.passModes.size)
            assertTrue(controller.state.value.configuration.hasUnsavedChanges)
            controller.onDiscardConfiguration()
            assertEquals("11", controller.state.value.configuration.noEntryTimeoutSeconds)
            assertEquals("CONTROLLED_BOTH", controller.state.value.configuration.passageMode)
            controller.close()
        }

    @Test
    fun bulkRunIsReadOnlyWhileIndividualProtectedOperationsRemainAvailable() =
        runTest {
            val fake = FakeGate()
            val controller = controller(fake)
            controller.onConfigurationChanged(
                GateConfigurationUi(
                    site = "KOLKATA_INDIA",
                    upsInstalled = true,
                    tokenControlUnitInstalled = true,
                    maintenanceOperationsEnabled = true,
                ),
            )
            controller.onConnect()
            advanceUntilIdle()

            controller.onDiagnosticRunAll()
            advanceUntilIdle()

            assertEquals(0, fake.initializeCalls)
            assertEquals(0, fake.clockWrites.size)
            assertEquals(0, fake.clearCounterCalls)
            assertEquals(0, fake.resetCalls)
            assertTrue(
                controller.state.value.diagnostics
                    .filterNot { it.requiresMaintenance }
                    .all { it.state == DiagnosticState.PASSED },
            )

            listOf("initialize", "clock-sync", "clear-counters", "reset").forEach { id ->
                controller.onDiagnosticRun(id)
                advanceUntilIdle()
            }
            assertEquals(1, fake.initializeCalls)
            assertEquals(1, fake.clockWrites.size)
            assertEquals(1, fake.clearCounterCalls)
            assertEquals(1, fake.resetCalls)
            controller.close()
        }

    @Test
    fun sensorInventoryMatchesDocumentedSectorInputs() {
        val sensors = defaultGateSensors()

        assertEquals(18, sensors.size)
        assertEquals((1..19).filter { it != 10 }, sensors.map { it.id })
        assertEquals("S01", sensors.first().code)
        assertEquals("S19", sensors.last().code)
    }

    @Test
    fun exposesAndOpensThePersistentLogDirectory() =
        runTest {
            var opened = false
            val controller =
                ControlPanelController(
                    dispatcher = StandardTestDispatcher(testScheduler),
                    gateFactory = { GateResult.Success(FakeGate()) },
                    serialPortProvider = { GateResult.Success(emptyList()) },
                    logDirectoryProvider = { "test-log-directory" },
                    logDirectoryOpener = { opened = true },
                )

            assertEquals("test-log-directory", controller.state.value.logDirectory)
            controller.onOpenLogDirectory()
            assertTrue(opened)
            assertEquals("Opened persistent log folder", controller.state.value.transientMessage)
            controller.close()
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

            val sensors = controller.state.value.gateTwin.sensors
            assertEquals(SensorHealth.FAULT, sensors.first { it.id == 4 }.health)
            assertTrue(sensors.filterNot { it.id == 4 }.none { it.health == SensorHealth.FAULT })
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
