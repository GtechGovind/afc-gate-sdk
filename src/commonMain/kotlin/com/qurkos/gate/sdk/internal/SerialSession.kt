package com.qurkos.gate.sdk.internal

import com.qurkos.gate.sdk.GateConnectionState
import com.qurkos.gate.sdk.GateError
import com.qurkos.gate.sdk.GateEvent
import com.qurkos.gate.sdk.GateResult
import com.qurkos.gate.sdk.GateRuntimeOptions
import com.qurkos.gate.sdk.ReconnectPolicy
import com.qurkos.gate.sdk.SerialConnectionConfig
import com.qurkos.gate.sdk.SerialPortInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Raw lifecycle state exposed by a platform [SerialTransport]. */
internal enum class SerialTransportState {
    CLOSED,
    OPENING,
    OPEN,
    FAILED,
}

/**
 * Platform boundary for serial byte transport.
 *
 * Implementations must preserve byte order, emit defensive byte-array instances, and surface terminal I/O failures via
 * [state]. Protocol framing does not belong at this layer.
 */
internal interface SerialTransport {
    /** Ordered chunks received from the port. Chunk boundaries have no protocol meaning. */
    val incoming: Flow<ByteArray>

    /** Current low-level transport state. */
    val state: StateFlow<SerialTransportState>

    /** Opens [config], closing any prior port and discarding pending bytes from the previous session first. */
    suspend fun open(config: SerialConnectionConfig)

    /** Releases the current platform port and reader resources. */
    suspend fun close()

    /** Writes every byte in [bytes] in order or throws without claiming success. */
    suspend fun write(bytes: ByteArray)
}

/** Creates the platform transport without opening hardware. */
internal expect fun createPlatformSerialTransport(): SerialTransport

/** Enumerates platform ports without opening or probing them. */
internal expect fun availablePlatformSerialPorts(): GateResult<List<SerialPortInfo>>

/** Supplies the dispatcher used for common session orchestration. */
internal expect fun platformSessionDispatcher(): CoroutineDispatcher

/**
 * Owns one gate's serial lifecycle, request serialization, retries, correlation, monitoring, and reconnection.
 *
 * The session permits a single request on the wire. Only transactions declared idempotent are retried. Reconnection
 * restores the byte session but never stores or replays a state-changing operation.
 */
@Suppress("TooGenericExceptionCaught")
internal class SerialSession(
    private val serialConfig: SerialConnectionConfig,
    private val runtime: GateRuntimeOptions,
    adapter: GateProtocolAdapter,
    private val transport: SerialTransport,
    private val eventSink: (GateEvent) -> Unit,
    dispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lifecycleMutex = Mutex()
    private val commandMutex = Mutex()
    private val reconnectMutex = Mutex()
    private val frames =
        Channel<ProtocolFrame>(
            capacity = FRAME_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private val mutableConnectionState = MutableStateFlow(GateConnectionState.DISCONNECTED)

    private val decoder = adapter.newDecoder()
    private var receiverJob: Job? = null
    private var transportObserverJob: Job? = null
    private var reconnectJob: Job? = null
    private var monitoringJob: Job? = null
    private var reconnectHandshake: (suspend () -> GateResult<*>)? = null
    private var intentionalDisconnect = true
    private var hasEstablishedConnection = false

    /** Read-only common lifecycle state observed by the public controller. */
    val connectionState: StateFlow<GateConnectionState> = mutableConnectionState.asStateFlow()

    /** Opens the transport idempotently and starts receive/state observers. */
    suspend fun connect(): GateResult<Unit> =
        lifecycleMutex.withLock {
            reconnectJob?.cancelAndJoin()
            reconnectJob = null
            if (connectionState.value == GateConnectionState.CONNECTED) {
                return@withLock GateResult.Success(Unit)
            }
            intentionalDisconnect = false
            startTransportObserver()
            updateConnectionState(GateConnectionState.CONNECTING)
            openTransport()
        }

    /** Cancels session jobs, drains stale responses, and releases the port. */
    suspend fun disconnect(): GateResult<Unit> =
        lifecycleMutex.withLock {
            intentionalDisconnect = true
            hasEstablishedConnection = false
            reconnectJob?.cancelAndJoin()
            reconnectJob = null
            monitoringJob?.cancelAndJoin()
            monitoringJob = null
            reconnectHandshake = null
            commandMutex.withLock {
                receiverJob?.cancelAndJoin()
                receiverJob = null
                transportObserverJob?.cancelAndJoin()
                transportObserverJob = null
                drainFrames()
                try {
                    transport.close()
                    updateConnectionState(GateConnectionState.DISCONNECTED)
                    GateResult.Success(Unit)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    updateConnectionState(GateConnectionState.FAILED)
                    GateResult.Failure(GateError.Transport(error.message ?: "Unable to close serial port"))
                }
            }
        }

    /** Starts or replaces periodic monitoring using the configured interval. */
    fun startMonitoring(block: suspend () -> GateResult<*>) {
        reconnectHandshake = block
        val interval = runtime.statusPollInterval ?: return
        monitoringJob?.cancel()
        monitoringJob =
            scope.launch {
                while (isActive) {
                    delay(interval)
                    if (connectionState.value == GateConnectionState.CONNECTED) {
                        block()
                    }
                }
            }
    }

    /**
     * Writes and awaits one correlated [transaction].
     *
     * Safe reads use the configured retry count; state-changing transactions receive exactly one write attempt.
     */
    suspend fun transact(transaction: SerialTransaction): GateResult<GateResponse> =
        commandMutex.withLock {
            if (connectionState.value != GateConnectionState.CONNECTED) {
                return@withLock GateResult.Failure(GateError.NotConnected)
            }
            val attempts = if (transaction.idempotent) runtime.readRetries + 1 else 1
            repeat(attempts) { attempt ->
                val writeResult = write(transaction.encode(attempt))
                if (writeResult is GateResult.Failure) {
                    return@withLock writeResult
                }
                val response = awaitResponse(transaction)
                if (response != null) {
                    return@withLock transaction.decode(response)
                }
                val nextAction = if (attempt + 1 < attempts) "retry" else "fail"
                eventSink(
                    GateEvent.ProtocolWarning(
                        "Response timeout operation=${transaction.operationName} attempt=${attempt + 1}/$attempts " +
                            "timeoutMs=${runtime.responseTimeout.inWholeMilliseconds} nextAction=$nextAction",
                    ),
                )
            }
            GateResult.Failure(GateError.Timeout(transaction.operationName))
        }

    /** Resets protocol state and opens the transport; only a previously established session may auto-recover. */
    private suspend fun openTransport(): GateResult<Unit> =
        try {
            receiverJob?.cancelAndJoin()
            receiverJob = null
            transport.open(serialConfig)
            decoder.reset()
            drainFrames()
            startReceiver()
            hasEstablishedConnection = true
            updateConnectionState(GateConnectionState.CONNECTED)
            GateResult.Success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            updateConnectionState(GateConnectionState.FAILED)
            if (hasEstablishedConnection) scheduleReconnect()
            GateResult.Failure(GateError.Transport(error.message ?: "Unable to open serial port"))
        }

    /** Performs one defensive transport write and translates expected I/O exceptions. */
    private suspend fun write(bytes: ByteArray): GateResult<Unit> =
        try {
            transport.write(bytes.copyOf())
            GateResult.Success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            updateConnectionState(GateConnectionState.FAILED)
            scheduleReconnect()
            GateResult.Failure(GateError.Transport(error.message ?: "Unable to write to serial port"))
        }

    /** Waits until timeout for the next frame correlated to [transaction], discarding unrelated frames. */
    private suspend fun awaitResponse(transaction: SerialTransaction): ProtocolFrame? =
        withTimeoutOrNull(runtime.responseTimeout) {
            var frame: ProtocolFrame
            do {
                frame = frames.receive()
                if (!transaction.matches(frame)) {
                    eventSink(
                        GateEvent.ProtocolWarning(
                            "Discarded uncorrelated response while waiting for ${transaction.operationName}; " +
                                frame.diagnosticSummary,
                        ),
                    )
                }
            } while (!transaction.matches(frame))
            frame
        }

    /** Launches the single streaming decode pipeline when it is not already active. */
    private fun startReceiver() {
        if (receiverJob?.isActive == true) return
        receiverJob =
            scope.launch {
                transport.incoming.collect { bytes ->
                    decoder.feed(bytes.copyOf()).forEach { result ->
                        when (result) {
                            is FrameDecodeResult.Frame -> frames.send(result.value)
                            is FrameDecodeResult.Error -> eventSink(GateEvent.ProtocolWarning(result.message))
                        }
                    }
                }
            }
    }

    /** Observes terminal platform failures and initiates configured recovery. */
    private fun startTransportObserver() {
        if (transportObserverJob?.isActive == true) return
        transportObserverJob =
            scope.launch {
                transport.state.collect { state ->
                    if (
                        state == SerialTransportState.FAILED &&
                        !intentionalDisconnect &&
                        connectionState.value != GateConnectionState.FAILED
                    ) {
                        updateConnectionState(GateConnectionState.FAILED)
                        scheduleReconnect()
                    }
                }
            }
    }

    /** Launches at most one bounded-backoff reconnect loop. */
    private suspend fun scheduleReconnect() {
        reconnectMutex.withLock {
            if (intentionalDisconnect || !hasEstablishedConnection || reconnectJob?.isActive == true) return
            val policy = runtime.reconnectPolicy
            if (policy is ReconnectPolicy.Disabled) return
            check(policy is ReconnectPolicy.ExponentialBackoff)
            reconnectJob =
                scope.launch {
                    updateConnectionState(GateConnectionState.RECONNECTING)
                    var attempt = 0
                    var wait = policy.initialDelay
                    while (isActive && !intentionalDisconnect) {
                        attempt += 1
                        eventSink(GateEvent.ReconnectAttempt(attempt))
                        delay(wait)
                        val result = openTransport()
                        if (result is GateResult.Success) {
                            val handshake = reconnectHandshake
                            if (handshake == null || handshake() is GateResult.Success) return@launch
                            updateConnectionState(GateConnectionState.RECONNECTING)
                            closeAfterFailedHandshake()
                        }
                        wait = nextDelay(wait, policy)
                    }
                }
        }
    }

    /** Closes a transport that reopened but failed the controller-status handshake before another retry. */
    private suspend fun closeAfterFailedHandshake() {
        try {
            transport.close()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            eventSink(GateEvent.ProtocolWarning("Unable to close transport after failed reconnect handshake: ${error.message}"))
        }
    }

    /** Calculates the next finite reconnect delay and clamps it to the policy maximum. */
    private fun nextDelay(
        current: Duration,
        policy: ReconnectPolicy.ExponentialBackoff,
    ): Duration {
        val milliseconds = (current.inWholeMilliseconds * policy.multiplier).toLong()
        return milliseconds.milliseconds.coerceAtMost(policy.maximumDelay)
    }

    /** Publishes a changed lifecycle state and its matching observational event. */
    private fun updateConnectionState(state: GateConnectionState) {
        if (mutableConnectionState.value == state) return
        mutableConnectionState.value = state
        eventSink(GateEvent.ConnectionChanged(state))
    }

    /** Removes responses that belong to an earlier transaction or serial session. */
    private fun drainFrames() {
        while (frames.tryReceive().isSuccess) {
            // Drain responses belonging to an earlier serial session.
        }
    }

    private companion object {
        const val FRAME_BUFFER_CAPACITY = 64
    }
}
