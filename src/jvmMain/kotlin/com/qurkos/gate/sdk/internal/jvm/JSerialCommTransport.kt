package com.qurkos.gate.sdk.internal.jvm

import com.fazecast.jSerialComm.SerialPort
import com.qurkos.gate.sdk.SerialConnectionConfig
import com.qurkos.gate.sdk.SerialParameters
import com.qurkos.gate.sdk.SerialParity
import com.qurkos.gate.sdk.SerialStopBits
import com.qurkos.gate.sdk.internal.SerialTransport
import com.qurkos.gate.sdk.internal.SerialTransportState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Testable JVM-only facade that keeps jSerialComm types out of transport lifecycle logic. */
internal interface JvmSerialPort {
    /** Whether the native library still reports an acquired open handle. */
    val isOpen: Boolean

    /** Applies all resolved wire parameters before the port is opened. */
    fun configure(parameters: SerialParameters)

    /** Attempts to acquire the native port. */
    fun open(): Boolean

    /** Attempts to release the native port; false means ownership may remain. */
    fun close(): Boolean

    /** Deasserts DTR because Puloon uses no hardware flow-control lines. */
    fun clearDtr()

    /** Deasserts RTS because Puloon uses no hardware flow-control lines. */
    fun clearRts()

    /** Performs one bounded blocking read into [bytes]. */
    fun read(bytes: ByteArray): Int

    /** Writes up to [length] bytes starting at [offset]. */
    fun write(
        bytes: ByteArray,
        length: Int,
        offset: Int,
    ): Int
}

/** Thin adapter containing every direct jSerialComm call required by the SDK. */
private class JSerialCommPort(
    private val delegate: SerialPort,
) : JvmSerialPort {
    override val isOpen: Boolean
        get() = delegate.isOpen

    override fun configure(parameters: SerialParameters) {
        check(
            delegate.setComPortParameters(
                parameters.baudRate,
                parameters.dataBits,
                parameters.stopBits.toJSerialComm(),
                parameters.parity.toJSerialComm(),
            ),
        ) { "Unable to configure serial port parameters" }
        check(delegate.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED)) { "Unable to disable serial flow control" }
        check(
            delegate.setComPortTimeouts(
                SerialPort.TIMEOUT_READ_SEMI_BLOCKING or SerialPort.TIMEOUT_WRITE_BLOCKING,
                JSerialCommTransport.READ_TIMEOUT_MILLISECONDS,
                JSerialCommTransport.WRITE_TIMEOUT_MILLISECONDS,
            ),
        ) { "Unable to configure serial port timeouts" }
    }

    override fun open(): Boolean = delegate.openPort()

    override fun close(): Boolean = !delegate.isOpen || delegate.closePort()

    override fun clearDtr() {
        check(delegate.clearDTR()) { "Unable to clear serial DTR" }
    }

    override fun clearRts() {
        check(delegate.clearRTS()) { "Unable to clear serial RTS" }
    }

    override fun read(bytes: ByteArray): Int = delegate.readBytes(bytes, bytes.size)

    override fun write(
        bytes: ByteArray,
        length: Int,
        offset: Int,
    ): Int = delegate.writeBytes(bytes, length, offset)
}

private fun SerialStopBits.toJSerialComm(): Int =
    when (this) {
        SerialStopBits.ONE -> SerialPort.ONE_STOP_BIT
        SerialStopBits.ONE_POINT_FIVE -> SerialPort.ONE_POINT_FIVE_STOP_BITS
        SerialStopBits.TWO -> SerialPort.TWO_STOP_BITS
    }

private fun SerialParity.toJSerialComm(): Int =
    when (this) {
        SerialParity.NONE -> SerialPort.NO_PARITY
        SerialParity.ODD -> SerialPort.ODD_PARITY
        SerialParity.EVEN -> SerialPort.EVEN_PARITY
        SerialParity.MARK -> SerialPort.MARK_PARITY
        SerialParity.SPACE -> SerialPort.SPACE_PARITY
    }

/**
 * JVM serial transport backed by jSerialComm.
 *
 * Every blocking/native call executes on [ioDispatcher]. Writes verify that every requested byte was accepted, reads use
 * a bounded channel, and Java exceptions are allowed to cross only the internal transport boundary.
 */
@Suppress("TooGenericExceptionCaught")
internal class JSerialCommTransport(
    private val ioDispatcher: CoroutineDispatcher,
    private val portResolver: (String) -> JvmSerialPort = { name -> JSerialCommPort(SerialPort.getCommPort(name)) },
) : SerialTransport {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val incomingBytes =
        Channel<ByteArray>(
            capacity = INCOMING_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private val mutableState = MutableStateFlow(SerialTransportState.CLOSED)

    private var port: JvmSerialPort? = null
    private var readerJob: Job? = null

    override val incoming: Flow<ByteArray> = incomingBytes.receiveAsFlow()
    override val state: StateFlow<SerialTransportState> = mutableState.asStateFlow()

    /** Closes any previous port, validates resolved parameters, opens the device, and starts one reader. */
    override suspend fun open(config: SerialConnectionConfig) {
        close()
        mutableState.value = SerialTransportState.OPENING
        var candidate: JvmSerialPort? = null
        try {
            val parameters = requireNotNull(config.parameters) { "Serial parameters were not resolved" }
            val resolved =
                withContext(ioDispatcher) {
                    portResolver(config.port.value).apply { configure(parameters) }
                }
            candidate = resolved
            if (!withContext(ioDispatcher) { resolved.open() }) {
                error("Unable to open serial port ${config.port.value}")
            }
            withContext(ioDispatcher) {
                resolved.clearDtr()
                resolved.clearRts()
            }
            drainIncomingBytes()
            port = resolved
            mutableState.value = SerialTransportState.OPEN
            readerJob = startReader(resolved)
            candidate = null
        } catch (cancellation: CancellationException) {
            val closed = cleanupFailedOpen(candidate)
            mutableState.value = if (closed) SerialTransportState.CLOSED else SerialTransportState.FAILED
            throw cancellation
        } catch (error: Exception) {
            cleanupFailedOpen(candidate)
            mutableState.value = SerialTransportState.FAILED
            throw error
        }
    }

    /** Releases a native handle acquired before [open] completed and reports whether native ownership ended. */
    private suspend fun cleanupFailedOpen(candidate: JvmSerialPort?): Boolean {
        val opened = port ?: candidate
        port = null
        val closed =
            withContext(NonCancellable) {
                readerJob?.cancelAndJoin()
                readerJob = null
                withContext(ioDispatcher) { opened?.close() ?: true }
            }
        if (!closed) port = opened
        return closed
    }

    /** Closes the native handle, joins the reader, and publishes the closed state. */
    override suspend fun close() {
        val currentPort = port
        val closed =
            withContext(NonCancellable) {
                port = null
                val released = withContext(ioDispatcher) { currentPort?.close() ?: true }
                readerJob?.cancelAndJoin()
                readerJob = null
                drainIncomingBytes()
                released
            }
        if (!closed) {
            port = currentPort
            mutableState.value = SerialTransportState.FAILED
            error("Unable to close serial port")
        }
        mutableState.value = SerialTransportState.CLOSED
        currentCoroutineContext().ensureActive()
    }

    /** Writes all non-empty [bytes], handling partial native writes without truncation. */
    override suspend fun write(bytes: ByteArray) {
        require(bytes.isNotEmpty()) { "Serial write must not be empty" }
        val currentPort = port?.takeIf(JvmSerialPort::isOpen) ?: error("Serial port is not open")
        try {
            withContext(ioDispatcher) {
                var offset = 0
                while (offset < bytes.size) {
                    val written = currentPort.write(bytes, bytes.size - offset, offset)
                    check(written > 0) { "Serial write failed after $offset of ${bytes.size} bytes" }
                    offset += written
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            mutableState.value = SerialTransportState.FAILED
            throw error
        }
    }

    /** Reads bounded chunks until cancellation, closure, or a terminal native error. */
    private fun startReader(serialPort: JvmSerialPort): Job =
        scope.launch {
            val buffer = ByteArray(READ_BUFFER_SIZE)
            try {
                while (isActive && serialPort.isOpen) {
                    val count = serialPort.read(buffer)
                    if (count > 0) incomingBytes.send(buffer.copyOf(count))
                    if (count < 0) error("Serial read failed")
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (isActive) mutableState.value = SerialTransportState.FAILED
            }
        }

    private fun drainIncomingBytes() {
        while (incomingBytes.tryReceive().isSuccess) {
            // Discard bytes captured by an earlier native serial session.
        }
    }

    internal companion object {
        /** Maximum native read chunk accepted before forwarding an immutable copy to the session. */
        const val READ_BUFFER_SIZE = 512

        /** Bounded native read wait so cancellation and port failure are observed promptly. */
        const val READ_TIMEOUT_MILLISECONDS = 250

        /** Maximum time jSerialComm may block while accepting a write chunk. */
        const val WRITE_TIMEOUT_MILLISECONDS = 1_000

        /** Number of read chunks retained while a slower protocol decoder catches up. */
        const val INCOMING_BUFFER_CAPACITY = 64
    }
}
