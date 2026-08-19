package com.qurkos.gate.sdk

import com.qurkos.gate.sdk.internal.SerialGateController
import com.qurkos.gate.sdk.internal.puloon.PuloonAdapter
import com.qurkos.gate.sdk.internal.puloon.PuloonFrameCodec
import com.qurkos.gate.sdk.internal.puloon.ascii
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GateContractTest {
    @Test
    fun oneGateInterfaceSendsEntryExitInvalidTicketAndEmergency() =
        runBlocking {
            val transport =
                TestSerialTransport { request, fake ->
                    fake.respond(request, byteArrayOf(request.command, ascii('0'), ascii('0')))
                }
            val gate = createGate(transport, GateHardwareProfile(site = GateSite.INDIA))
            assertIs<GateResult.Success<Unit>>(gate.connect())

            assertIs<GateResult.Success<Unit>>(gate.allowEntry())
            assertIs<GateResult.Success<Unit>>(gate.allowExit(passengerCount = 2, lampColor = GateLampColor.YELLOW))
            assertIs<GateResult.Success<Unit>>(gate.rejectPassage(GateDirection.ENTRY))
            assertIs<GateResult.Success<Unit>>(gate.setEmergency(true))
            assertIs<GateResult.Success<Unit>>(gate.setEmergency(false))

            val payloads = transport.writes.map { PuloonFrameCodec.decode(it).payload }
            assertContentEquals(byteArrayOf(ascii('A'), ascii('0'), ascii('1'), ascii('0'), ascii('1')), payloads[0])
            assertContentEquals(byteArrayOf(ascii('A'), ascii('1'), ascii('5'), ascii('0'), ascii('2')), payloads[1])
            assertContentEquals(byteArrayOf(ascii('A'), ascii('0'), 0x44, ascii('0'), ascii('0')), payloads[2])
            assertContentEquals(byteArrayOf(ascii('E'), ascii('1')), payloads[3])
            assertContentEquals(byteArrayOf(ascii('E'), ascii('0')), payloads[4])
            gate.disconnect()
            Unit
        }

    @Test
    fun unsupportedCapabilityFailsBeforeSerialWrite() =
        runBlocking {
            val transport = TestSerialTransport()
            val gate = createGate(transport, GateHardwareProfile())
            gate.connect()

            val result = assertIs<GateResult.Failure>(gate.rejectPassage(GateDirection.ENTRY))

            assertEquals(GateError.UnsupportedCapability(GateCapability.INVALID_TICKET), result.error)
            assertEquals(0, transport.writes.size)
            gate.disconnect()
            Unit
        }

    @Test
    fun statusResponseUpdatesReturnValueAndStateFlow() =
        runBlocking {
            val statusData =
                byteArrayOf(ascii('0')) + "1207".encodeToByteArray() +
                    byteArrayOf(ascii('0'), ascii('0'), ascii('0'), 0x40, 0x80.toByte()) +
                    "@@@00000000".encodeToByteArray() + byteArrayOf(ascii('2'), ascii('0'))
            val transport =
                TestSerialTransport { request, fake ->
                    fake.respond(request, byteArrayOf(request.command, ascii('0'), ascii('0')) + statusData)
                }
            val gate = createGate(transport, GateHardwareProfile())
            gate.connect()

            val result = assertIs<GateResult.Success<GateStatus>>(gate.refreshStatus())

            assertEquals(12, result.value.entryCount)
            assertEquals(7, result.value.exitCount)
            assertEquals(GateEmergencyState.REMOTE, result.value.emergency)
            assertEquals(result.value, gate.status.value)
            gate.disconnect()
            Unit
        }

    @Test
    fun concurrentCallersReceiveUniqueSerializedTransactions() =
        runBlocking {
            val transport =
                TestSerialTransport { request, fake ->
                    fake.respond(request, byteArrayOf(request.command, ascii('0'), ascii('0')))
                }
            val gate = createGate(transport, GateHardwareProfile(site = GateSite.INDIA))
            assertIs<GateResult.Success<Unit>>(gate.connect())

            val results = coroutineScope { List(25) { async { gate.allowEntry() } }.awaitAll() }

            assertEquals(25, results.count { it is GateResult.Success })
            val sequences = transport.writes.map(PuloonFrameCodec::decode).map { it.sequence }
            assertEquals(25, sequences.distinct().size)
            gate.disconnect()
            Unit
        }

    @Test
    fun gateCanReconnectAfterIntentionalDisconnect() =
        runBlocking {
            val transport = TestSerialTransport()
            val gate = createGate(transport, GateHardwareProfile())

            assertIs<GateResult.Success<Unit>>(gate.connect())
            assertIs<GateResult.Success<Unit>>(gate.disconnect())
            assertIs<GateResult.Success<Unit>>(gate.connect())

            assertEquals(2, transport.openCount)
            gate.disconnect()
            Unit
        }

    private fun createGate(
        transport: TestSerialTransport,
        hardware: GateHardwareProfile,
    ): Gate =
        SerialGateController(
            config =
                GateDeviceConfig(
                    vendor = GateVendor.PULOON,
                    serial = SerialConnectionConfig(SerialPortName("fake"), SerialParameters(57_600)),
                    hardware = hardware,
                    runtime = GateRuntimeOptions(statusPollInterval = null, reconnectPolicy = ReconnectPolicy.Disabled),
                ),
            adapter = PuloonAdapter(hardware, maintenanceOperationsEnabled = false),
            transport = transport,
            dispatcher = Dispatchers.Default,
        )
}
