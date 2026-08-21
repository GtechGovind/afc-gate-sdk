package com.qurkos.gate.sdk

import com.qurkos.gate.sdk.internal.SerialGateController
import com.qurkos.gate.sdk.internal.puloon.PuloonAdapter
import com.qurkos.gate.sdk.internal.puloon.PuloonFrameCodec
import com.qurkos.gate.sdk.internal.puloon.ascii
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class GateContractTest {
    @Test
    fun transportOpenDoesNotPublishConnectedBeforeStatusHandshake() =
        runBlocking {
            val statusRequested = CompletableDeferred<Unit>()
            val releaseStatus = CompletableDeferred<Unit>()
            val transport =
                TestSerialTransport { request, fake ->
                    if (request.command == ascii('S')) {
                        statusRequested.complete(Unit)
                        releaseStatus.await()
                    }
                    fake.respond(request, responseFor(request, baseStatus()))
                }
            val gate = createGate(transport, GateHardwareProfile())

            val connecting = async { gate.connect() }
            statusRequested.await()
            assertEquals(GateConnectionState.CONNECTING, gate.connectionState.value)
            releaseStatus.complete(Unit)
            assertIs<GateResult.Success<Unit>>(connecting.await())
            assertEquals(GateConnectionState.CONNECTED, gate.connectionState.value)
            gate.disconnect()
        }

    @Test
    fun emitsCorrelatedSemanticCommandAndResponseTraffic() =
        runBlocking {
            val gate = createGate(verifiedTransport(), GateHardwareProfile())
            val events = async(start = CoroutineStart.UNDISPATCHED) { gate.events.take(2).toList() }

            val connected = gate.connect()
            assertIs<GateResult.Success<Unit>>(connected, connected.toString())

            val sent = assertIs<GateEvent.CommandSent>(events.await()[0])
            val received = assertIs<GateEvent.ResponseReceived>(events.await()[1])
            assertEquals(GateCommand.CONNECT, sent.command)
            assertEquals(sent.sequence, received.sequence)
            assertEquals(GateCommandOutcome.SUCCESS, received.outcome)
            gate.disconnect()
        }

    @Test
    fun oneGateInterfaceSendsEntryExitInvalidTicketAndEmergency() =
        runBlocking {
            val transport =
                TestSerialTransport { request, fake ->
                    fake.respond(request, responseFor(request, baseStatus()))
                }
            val gate =
                createGate(
                    transport,
                    GateHardwareProfile(site = GateSite.INDIA, controllerVariant = GateControllerVariant.BLDC),
                )
            assertSuccess(gate.connect())

            assertSuccess(gate.allowEntry())
            assertSuccess(gate.allowExit(passengerCount = 2, lampColor = GateLampColor.YELLOW))
            assertSuccess(gate.rejectPassage(GateDirection.ENTRY))
            assertSuccess(gate.setEmergency(true))
            assertSuccess(gate.setEmergency(false))

            val payloads =
                transport.writes
                    .map(PuloonFrameCodec::decode)
                    .filter { it.command == ascii('A') || it.command == ascii('E') }
                    .map { it.payload }
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
            val transport = verifiedTransport()
            val gate = createGate(transport, GateHardwareProfile())
            gate.connect()
            val writesBefore = transport.writes.size

            val result = assertIs<GateResult.Failure>(gate.rejectPassage(GateDirection.ENTRY))

            assertEquals(GateError.UnsupportedCapability(GateCapability.INVALID_TICKET), result.error)
            assertEquals(writesBefore, transport.writes.size)
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
            assertEquals(GateEmergencyState.TWENTY_FOUR_VOLT, result.value.emergency)
            assertEquals(result.value, gate.status.value)
            gate.disconnect()
            Unit
        }

    @Test
    fun concurrentCallersReceiveUniqueSerializedTransactions() =
        runBlocking {
            val transport =
                TestSerialTransport { request, fake ->
                    fake.respond(request, responseFor(request, baseStatus()))
                }
            val gate = createGate(transport, GateHardwareProfile(site = GateSite.INDIA))
            assertIs<GateResult.Success<Unit>>(gate.connect())

            val results = coroutineScope { List(25) { async { gate.allowEntry() } }.awaitAll() }

            assertEquals(25, results.count { it is GateResult.Success })
            val sequences =
                transport.writes
                    .map(PuloonFrameCodec::decode)
                    .filter { it.command == ascii('A') }
                    .map { it.sequence }
            assertEquals(25, sequences.distinct().size)
            gate.disconnect()
            Unit
        }

    @Test
    fun gateCanReconnectAfterIntentionalDisconnect() =
        runBlocking {
            val transport = verifiedTransport()
            val gate = createGate(transport, GateHardwareProfile())

            assertIs<GateResult.Success<Unit>>(gate.connect())
            assertIs<GateResult.Success<Unit>>(gate.disconnect())
            assertIs<GateResult.Success<Unit>>(gate.connect())

            assertEquals(2, transport.openCount)
            gate.disconnect()
            Unit
        }

    @Test
    fun automaticReconnectPerformsStatusHandshakeWhenPollingIsDisabled() =
        runBlocking {
            val transport = verifiedTransport()
            val gate =
                createGate(
                    transport,
                    GateHardwareProfile(),
                    GateRuntimeOptions(
                        responseTimeout = 100.milliseconds,
                        readRetries = 0,
                        statusPollInterval = null,
                        reconnectPolicy = ReconnectPolicy.ExponentialBackoff(10.milliseconds, 20.milliseconds),
                    ),
                )
            assertIs<GateResult.Success<Unit>>(gate.connect())

            transport.fail()
            withTimeout(2.seconds) {
                while (transport.openCount < 2 || gate.connectionState.value != GateConnectionState.CONNECTED) {
                    delay(10.milliseconds)
                }
            }

            assertTrue(transport.writes.map(PuloonFrameCodec::decode).count { it.command == ascii('S') } >= 2)
            gate.disconnect()
        }

    @Test
    fun explicitConnectCancelsAnAutomaticReconnectHandshakeBeforeReportingSuccess() =
        runBlocking {
            val reconnectHandshakeStarted = CompletableDeferred<Unit>()
            val holdReconnectHandshake = CompletableDeferred<Unit>()
            var statusRequests = 0
            val transport =
                TestSerialTransport { request, fake ->
                    if (request.command == ascii('S')) {
                        statusRequests += 1
                        if (statusRequests == 2) {
                            reconnectHandshakeStarted.complete(Unit)
                            holdReconnectHandshake.await()
                        }
                    }
                    fake.respond(request, responseFor(request, baseStatus()))
                }
            val gate =
                createGate(
                    transport,
                    GateHardwareProfile(),
                    GateRuntimeOptions(
                        responseTimeout = 100.milliseconds,
                        readRetries = 0,
                        statusPollInterval = null,
                        reconnectPolicy = ReconnectPolicy.ExponentialBackoff(10.milliseconds, 20.milliseconds),
                    ),
                )
            assertIs<GateResult.Success<Unit>>(gate.connect())
            transport.fail()
            withTimeout(2.seconds) { reconnectHandshakeStarted.await() }

            assertIs<GateResult.Success<Unit>>(withTimeout(2.seconds) { gate.connect() })
            delay(50.milliseconds)

            assertEquals(GateConnectionState.CONNECTED, gate.connectionState.value)
            assertEquals(2, transport.openCount)
            assertEquals(3, statusRequests)
            gate.disconnect()
        }

    @Test
    fun concurrentLifecycleAndStatusTrafficUsesUniqueTraceSequences() =
        runBlocking {
            val gate = createGate(verifiedTransport(), GateHardwareProfile())
            val observed = mutableListOf<GateEvent>()
            val collector = launch { gate.events.collect(observed::add) }
            yield()
            assertIs<GateResult.Success<Unit>>(gate.connect())

            coroutineScope {
                listOf(
                    async { gate.refreshStatus() },
                    async { gate.disconnect() },
                ).awaitAll()
            }
            yield()
            collector.cancelAndJoin()

            val sent = observed.filterIsInstance<GateEvent.CommandSent>()
            val received = observed.filterIsInstance<GateEvent.ResponseReceived>()
            assertEquals(sent.size, sent.map { it.sequence }.distinct().size)
            assertEquals(sent.map { it.sequence }.toSet(), received.map { it.sequence }.toSet())
        }

    private fun createGate(
        transport: TestSerialTransport,
        hardware: GateHardwareProfile,
        runtime: GateRuntimeOptions =
            GateRuntimeOptions(statusPollInterval = null, reconnectPolicy = ReconnectPolicy.Disabled),
    ): Gate =
        SerialGateController(
            config =
                GateDeviceConfig(
                    vendor = GateVendor.PULOON,
                    serial = SerialConnectionConfig(SerialPortName("fake"), SerialParameters(57_600)),
                    hardware = hardware,
                    runtime = runtime,
                ),
            adapter = PuloonAdapter(hardware, maintenanceOperationsEnabled = false),
            transport = transport,
            dispatcher = Dispatchers.Default,
        )

    private fun verifiedTransport(): TestSerialTransport =
        TestSerialTransport { request, fake -> fake.respond(request, responseFor(request, baseStatus())) }

    private fun responseFor(
        request: com.qurkos.gate.sdk.internal.puloon.PuloonFrame,
        status: ByteArray,
    ): ByteArray =
        byteArrayOf(request.command, ascii('0'), ascii('0')) +
            if (request.command == ascii('S')) status else ByteArray(0)

    private fun baseStatus(): ByteArray =
        byteArrayOf(ascii('0')) + "0000".encodeToByteArray() +
            byteArrayOf(ascii('0'), ascii('0'), ascii('0'), 0x40, 0x80.toByte()) +
            "@@@00000000".encodeToByteArray() + byteArrayOf(ascii('0'), ascii('0'))

    private fun assertSuccess(result: GateResult<Unit>) {
        assertIs<GateResult.Success<Unit>>(result, result.toString())
    }
}
