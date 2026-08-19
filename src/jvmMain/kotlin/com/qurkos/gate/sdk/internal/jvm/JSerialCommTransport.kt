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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * JVM serial transport backed by jSerialComm.
 *
 * Every blocking/native call executes on [ioDispatcher]. Writes verify that every requested byte was accepted, reads use
 * a bounded channel, and Java exceptions are allowed to cross only the internal transport boundary.
 */
@Suppress("TooGenericExceptionCaught")
internal class JSerialCommTransport(
    private val ioDispatcher: CoroutineDispatcher,
) : SerialTransport {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val incomingBytes =
        Channel<ByteArray>(
            capacity = INCOMING_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private val mutableState = MutableStateFlow(SerialTransportState.CLOSED)

    private var port: SerialPort? = null
    private var readerJob: Job? = null

    override val incoming: Flow<ByteArray> = incomingBytes.receiveAsFlow()
    override val state: StateFlow<SerialTransportState> = mutableState.asStateFlow()

    /** Closes any previous port, validates resolved parameters, opens the device, and starts one reader. */
    override suspend fun open(config: SerialConnectionConfig) {
        close()
        mutableState.value = SerialTransportState.OPENING
        try {
            val parameters = requireNotNull(config.parameters) { "Serial parameters were not resolved" }
            val candidate = configure(SerialPort.getCommPort(config.port.value), parameters)
            if (!withContext(ioDispatcher) { candidate.openPort() }) {
                error("Unable to open serial port ${config.port.value}")
            }
            port = candidate
            mutableState.value = SerialTransportState.OPEN
            readerJob = startReader(candidate)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            mutableState.value = SerialTransportState.FAILED
            throw error
        }
    }

    /** Closes the native handle, joins the reader, and publishes the closed state. */
    override suspend fun close() {
        val currentPort = port
        port = null
        withContext(ioDispatcher) {
            currentPort?.closePort()
        }
        readerJob?.cancelAndJoin()
        readerJob = null
        mutableState.value = SerialTransportState.CLOSED
    }

    /** Writes all non-empty [bytes], handling partial native writes without truncation. */
    override suspend fun write(bytes: ByteArray) {
        require(bytes.isNotEmpty()) { "Serial write must not be empty" }
        val currentPort = port?.takeIf(SerialPort::isOpen) ?: error("Serial port is not open")
        try {
            withContext(ioDispatcher) {
                var offset = 0
                while (offset < bytes.size) {
                    val written = currentPort.writeBytes(bytes, bytes.size - offset, offset)
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

    /** Applies line parameters and finite read/write timeouts before opening the port. */
    private fun configure(
        port: SerialPort,
        parameters: SerialParameters,
    ): SerialPort =
        port.apply {
            setComPortParameters(
                parameters.baudRate,
                parameters.dataBits,
                parameters.stopBits.toJSerialComm(),
                parameters.parity.toJSerialComm(),
            )
            setComPortTimeouts(
                SerialPort.TIMEOUT_READ_SEMI_BLOCKING or SerialPort.TIMEOUT_WRITE_BLOCKING,
                READ_TIMEOUT_MILLISECONDS,
                WRITE_TIMEOUT_MILLISECONDS,
            )
        }

    /** Reads bounded chunks until cancellation, closure, or a terminal native error. */
    private fun startReader(serialPort: SerialPort): Job =
        scope.launch {
            val buffer = ByteArray(READ_BUFFER_SIZE)
            try {
                while (isActive && serialPort.isOpen) {
                    val count = serialPort.readBytes(buffer, buffer.size)
                    if (count > 0) incomingBytes.send(buffer.copyOf(count))
                    if (count < 0) error("Serial read failed")
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (isActive) mutableState.value = SerialTransportState.FAILED
            }
        }

    /** Maps the common stop-bit enum to jSerialComm constants. */
    private fun SerialStopBits.toJSerialComm(): Int =
        when (this) {
            SerialStopBits.ONE -> SerialPort.ONE_STOP_BIT
            SerialStopBits.ONE_POINT_FIVE -> SerialPort.ONE_POINT_FIVE_STOP_BITS
            SerialStopBits.TWO -> SerialPort.TWO_STOP_BITS
        }

    /** Maps the common parity enum to jSerialComm constants. */
    private fun SerialParity.toJSerialComm(): Int =
        when (this) {
            SerialParity.NONE -> SerialPort.NO_PARITY
            SerialParity.ODD -> SerialPort.ODD_PARITY
            SerialParity.EVEN -> SerialPort.EVEN_PARITY
            SerialParity.MARK -> SerialPort.MARK_PARITY
            SerialParity.SPACE -> SerialPort.SPACE_PARITY
        }

    private companion object {
        const val READ_BUFFER_SIZE = 512
        const val READ_TIMEOUT_MILLISECONDS = 250
        const val WRITE_TIMEOUT_MILLISECONDS = 1_000
        const val INCOMING_BUFFER_CAPACITY = 64
    }
}
