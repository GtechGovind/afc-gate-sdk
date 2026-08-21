package com.qurkos.gate.sdk

import com.qurkos.gate.sdk.internal.GateOperation
import com.qurkos.gate.sdk.internal.SerialSession
import com.qurkos.gate.sdk.internal.SerialTransaction
import com.qurkos.gate.sdk.internal.SerialTransport
import com.qurkos.gate.sdk.internal.SerialTransportState
import com.qurkos.gate.sdk.internal.puloon.PuloonAdapter
import com.qurkos.gate.sdk.internal.puloon.PuloonFrame
import com.qurkos.gate.sdk.internal.puloon.PuloonFrameCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SerialSessionTest {
    @Test
    fun idempotentReadRetriesWithSameSequenceAndIncrementedAttempt() =
        runBlocking {
            var calls = 0
            val transport =
                TestSerialTransport { request, fake ->
                    calls += 1
                    if (calls == 2) fake.respond(request, "V0001.23".encodeToByteArray())
                }
            val adapter = PuloonAdapter(GateHardwareProfile(), maintenanceOperationsEnabled = false)
            val session = createSession(transport, adapter, readRetries = 2)
            assertIs<GateResult.Success<Unit>>(session.connect())

            val transaction =
                assertIs<GateResult.Success<SerialTransaction>>(adapter.transaction(GateOperation.Firmware)).value
            assertIs<GateResult.Success<*>>(session.transact(transaction))

            val frames = transport.writes.map(PuloonFrameCodec::decode)
            assertEquals(2, frames.size)
            assertEquals(frames.first().sequence, frames.last().sequence)
            assertEquals(listOf(0, 1), frames.map(PuloonFrame::retry))
            session.disconnect()
            Unit
        }

    @Test
    fun lostTcuStatusResponseIsNotRetriedBecauseReadResetsCounters() =
        runBlocking {
            val transport = TestSerialTransport()
            val adapter =
                PuloonAdapter(
                    GateHardwareProfile(
                        site = GateSite.INDIA,
                        modules = setOf(GateModule.TOKEN_CONTROL_UNIT),
                    ),
                    maintenanceOperationsEnabled = false,
                )
            val session = createSession(transport, adapter, readRetries = 3)
            assertIs<GateResult.Success<Unit>>(session.connect())
            val status = assertIs<GateResult.Success<SerialTransaction>>(adapter.transaction(GateOperation.Status)).value

            assertIs<GateResult.Failure>(session.transact(status))

            assertEquals(1, transport.writes.size)
            session.disconnect()
        }

    @Test
    fun reconnectDoesNotReplaySuccessfulStateChangingCommand() =
        runBlocking {
            val transport =
                TestSerialTransport { request, fake ->
                    fake.respond(request, byteArrayOf(request.command, '0'.code.toByte(), '0'.code.toByte()))
                }
            val adapter = PuloonAdapter(GateHardwareProfile(), maintenanceOperationsEnabled = false)
            val session = createSession(transport, adapter, readRetries = 0, reconnect = true)
            assertIs<GateResult.Success<Unit>>(session.connect())
            val passage =
                assertIs<GateResult.Success<SerialTransaction>>(
                    adapter.transaction(GateOperation.Passage(GatePassageRequest(GateDirection.ENTRY))),
                ).value
            assertIs<GateResult.Success<*>>(session.transact(passage))

            transport.fail()
            withTimeout(2.seconds) {
                while (transport.openCount < 2) delay(10.milliseconds)
            }

            assertEquals(1, transport.writes.size)
            session.disconnect()
            Unit
        }

    @Test
    fun explicitConnectCancelsPendingAutomaticReconnect() =
        runBlocking {
            val transport = TestSerialTransport()
            val adapter = PuloonAdapter(GateHardwareProfile(), maintenanceOperationsEnabled = false)
            val session =
                SerialSession(
                    serialConfig = SerialConnectionConfig(SerialPortName("fake"), adapter.defaultSerialParameters),
                    runtime =
                        GateRuntimeOptions(
                            responseTimeout = 30.milliseconds,
                            readRetries = 0,
                            statusPollInterval = null,
                            reconnectPolicy = ReconnectPolicy.ExponentialBackoff(200.milliseconds, 200.milliseconds),
                        ),
                    adapter = adapter,
                    transport = transport,
                    eventSink = {},
                    dispatcher = Dispatchers.Default,
                )
            assertIs<GateResult.Success<Unit>>(session.connect())
            transport.fail()
            withTimeout(2.seconds) {
                while (session.connectionState.value != GateConnectionState.RECONNECTING) delay(10.milliseconds)
            }

            assertIs<GateResult.Success<Unit>>(session.connect())
            delay(300.milliseconds)

            assertEquals(2, transport.openCount)
            session.disconnect()
            Unit
        }

    @Test
    fun initialOpenFailureDoesNotSeizePortInUnusableBackgroundSession() =
        runBlocking {
            val transport = TestSerialTransport(openFailuresRemaining = 1)
            val adapter = PuloonAdapter(GateHardwareProfile(), maintenanceOperationsEnabled = false)
            val session = createSession(transport, adapter, readRetries = 0, reconnect = true)

            assertIs<GateResult.Failure>(session.connect())
            delay(100.milliseconds)

            assertEquals(GateConnectionState.FAILED, session.connectionState.value)
            assertEquals(1, transport.openCount)
            assertEquals(0, transport.writes.size)
            session.disconnect()
            Unit
        }

    @Test
    fun callerCancellationStopsReadRetriesImmediately() =
        runBlocking {
            val transport = TestSerialTransport()
            val adapter = PuloonAdapter(GateHardwareProfile(), maintenanceOperationsEnabled = false)
            val session = createSession(transport, adapter, readRetries = 10)
            assertIs<GateResult.Success<Unit>>(session.connect())
            val transaction = assertIs<GateResult.Success<SerialTransaction>>(adapter.transaction(GateOperation.Firmware)).value

            assertFailsWith<TimeoutCancellationException> {
                withTimeout(10.milliseconds) { session.transact(transaction) }
            }

            assertEquals(1, transport.writes.size)
            session.disconnect()
            Unit
        }

    @Test
    fun timeoutDiagnosticsRecordEveryAttemptTimeoutAndNextAction() =
        runBlocking {
            val events = mutableListOf<GateEvent>()
            val transport = TestSerialTransport()
            val adapter = PuloonAdapter(GateHardwareProfile(), maintenanceOperationsEnabled = false)
            val session = createSession(transport, adapter, readRetries = 1, eventSink = events::add)
            assertIs<GateResult.Success<Unit>>(session.connect())
            val transaction = assertIs<GateResult.Success<SerialTransaction>>(adapter.transaction(GateOperation.Firmware)).value

            assertIs<GateResult.Failure>(session.transact(transaction))

            val warnings = events.filterIsInstance<GateEvent.ProtocolWarning>().map { it.message }
            assertTrue(warnings.any { it.contains("attempt=1/2") && it.contains("nextAction=retry") })
            assertTrue(warnings.any { it.contains("attempt=2/2") && it.contains("nextAction=fail") })
            assertTrue(warnings.all { it.contains("timeoutMs=30") })
            session.disconnect()
            Unit
        }

    @Test
    fun disconnectWaitsForInFlightTransactionBeforeClosingTransport() =
        runBlocking {
            val writeStarted = CompletableDeferred<Unit>()
            val releaseResponse = CompletableDeferred<Unit>()
            val transport =
                TestSerialTransport { request, fake ->
                    writeStarted.complete(Unit)
                    releaseResponse.await()
                    fake.respond(request, "V0001.23".encodeToByteArray())
                }
            val adapter = PuloonAdapter(GateHardwareProfile(), maintenanceOperationsEnabled = false)
            val session = createSession(transport, adapter, readRetries = 0)
            assertIs<GateResult.Success<Unit>>(session.connect())
            val transaction = assertIs<GateResult.Success<SerialTransaction>>(adapter.transaction(GateOperation.Firmware)).value

            val operation = async { session.transact(transaction) }
            writeStarted.await()
            val disconnect = async { session.disconnect() }
            delay(20.milliseconds)
            assertEquals(0, transport.closeCount)

            releaseResponse.complete(Unit)
            assertIs<GateResult.Success<*>>(operation.await())
            assertIs<GateResult.Success<Unit>>(disconnect.await())
            assertEquals(1, transport.closeCount)
        }

    @Test
    fun uncorrelatedFrameDiagnosticIncludesSafeProtocolMetadata() =
        runBlocking {
            val events = mutableListOf<GateEvent>()
            val transport =
                TestSerialTransport { request, fake ->
                    fake.sendRaw(
                        PuloonFrameCodec.encode(
                            PuloonFrame(request.sequence + 1, request.retry, "V0001.23".encodeToByteArray()),
                        ),
                    )
                    fake.respond(request, "V0001.23".encodeToByteArray())
                }
            val adapter = PuloonAdapter(GateHardwareProfile(), maintenanceOperationsEnabled = false)
            val session = createSession(transport, adapter, readRetries = 0, eventSink = events::add)
            assertIs<GateResult.Success<Unit>>(session.connect())
            val transaction = assertIs<GateResult.Success<SerialTransaction>>(adapter.transaction(GateOperation.Firmware)).value

            assertIs<GateResult.Success<*>>(session.transact(transaction))

            val warning = events.filterIsInstance<GateEvent.ProtocolWarning>().single().message
            assertTrue(warning.contains("Discarded uncorrelated response"))
            assertTrue(warning.contains("command=V"))
            assertTrue(warning.contains("sequence=1"))
            assertTrue(warning.contains("payloadBytes=8"))
            session.disconnect()
            Unit
        }

    private fun createSession(
        transport: TestSerialTransport,
        adapter: PuloonAdapter,
        readRetries: Int,
        reconnect: Boolean = false,
        eventSink: (GateEvent) -> Unit = {},
    ): SerialSession =
        SerialSession(
            serialConfig = SerialConnectionConfig(SerialPortName("fake"), adapter.defaultSerialParameters),
            runtime =
                GateRuntimeOptions(
                    responseTimeout = 30.milliseconds,
                    readRetries = readRetries,
                    statusPollInterval = null,
                    reconnectPolicy =
                        if (reconnect) {
                            ReconnectPolicy.ExponentialBackoff(10.milliseconds, 20.milliseconds)
                        } else {
                            ReconnectPolicy.Disabled
                        },
                ),
            adapter = adapter,
            transport = transport,
            eventSink = eventSink,
            dispatcher = Dispatchers.Default,
        )
}

internal class TestSerialTransport(
    private var openFailuresRemaining: Int = 0,
    private val responder: suspend (PuloonFrame, TestSerialTransport) -> Unit = { _, _ -> },
) : SerialTransport {
    private val incomingChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow(SerialTransportState.CLOSED)

    override val incoming: Flow<ByteArray> = incomingChannel.receiveAsFlow()
    override val state: StateFlow<SerialTransportState> = mutableState.asStateFlow()
    val writes = mutableListOf<ByteArray>()
    var openCount = 0
        private set
    var closeCount = 0
        private set

    override suspend fun open(config: SerialConnectionConfig) {
        openCount += 1
        if (openFailuresRemaining > 0) {
            openFailuresRemaining -= 1
            mutableState.value = SerialTransportState.FAILED
            error("Simulated open failure")
        }
        mutableState.value = SerialTransportState.OPEN
    }

    override suspend fun close() {
        closeCount += 1
        mutableState.value = SerialTransportState.CLOSED
    }

    override suspend fun write(bytes: ByteArray) {
        writes += bytes.copyOf()
        responder(PuloonFrameCodec.decode(bytes), this)
    }

    suspend fun respond(
        request: PuloonFrame,
        payload: ByteArray,
    ) {
        incomingChannel.send(
            PuloonFrameCodec.encode(PuloonFrame(request.sequence, request.retry, payload)),
        )
    }

    suspend fun sendRaw(bytes: ByteArray) {
        incomingChannel.send(bytes.copyOf())
    }

    fun fail() {
        mutableState.value = SerialTransportState.FAILED
    }
}
