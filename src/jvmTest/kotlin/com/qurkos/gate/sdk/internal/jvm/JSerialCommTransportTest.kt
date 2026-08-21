package com.qurkos.gate.sdk.internal.jvm

import com.qurkos.gate.sdk.SerialConnectionConfig
import com.qurkos.gate.sdk.SerialParameters
import com.qurkos.gate.sdk.SerialPortName
import com.qurkos.gate.sdk.internal.SerialTransportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JSerialCommTransportTest {
    @Test
    fun closedTransportRejectsWritesWithoutHardware() =
        runBlocking {
            val transport = JSerialCommTransport(Dispatchers.Unconfined)

            assertFailsWith<IllegalStateException> { transport.write(byteArrayOf(1)) }
            assertEquals(SerialTransportState.CLOSED, transport.state.value)
        }

    @Test
    fun nativeCloseFailureIsReportedAndDoesNotClaimThePortWasReleased() =
        runBlocking {
            val port = FakeJvmSerialPort(closeResult = false)
            val transport = JSerialCommTransport(Dispatchers.Default) { port }
            transport.open(testConfig())

            assertFailsWith<IllegalStateException> { transport.close() }

            assertEquals(1, port.closeCalls)
            assertEquals(SerialTransportState.FAILED, transport.state.value)
        }

    @Test
    fun successfulNativeClosePublishesClosedState() =
        runBlocking {
            val port = FakeJvmSerialPort(closeResult = true)
            val transport = JSerialCommTransport(Dispatchers.Default) { port }
            transport.open(testConfig())

            transport.close()

            assertEquals(1, port.closeCalls)
            assertEquals(SerialTransportState.CLOSED, transport.state.value)
        }

    @Test
    fun callerCancellationStillReleasesTheNativeHandleAndReader() =
        runBlocking {
            val closeStarted = CountDownLatch(1)
            val allowClose = CountDownLatch(1)
            val port = FakeJvmSerialPort(closeResult = true, closeStarted = closeStarted, allowClose = allowClose)
            val transport = JSerialCommTransport(Dispatchers.IO) { port }
            transport.open(testConfig())

            val closing = launch(Dispatchers.Default) { transport.close() }
            assertTrue(closeStarted.await(2, TimeUnit.SECONDS))
            closing.cancel()
            allowClose.countDown()
            closing.join()

            assertTrue(closing.isCancelled)
            assertEquals(1, port.closeCalls)
            assertEquals(SerialTransportState.CLOSED, transport.state.value)
            assertFalse(port.isOpen)
        }

    private fun testConfig(): SerialConnectionConfig = SerialConnectionConfig(SerialPortName("fake"), SerialParameters(57_600))
}

private class FakeJvmSerialPort(
    private val closeResult: Boolean,
    private val closeStarted: CountDownLatch? = null,
    private val allowClose: CountDownLatch? = null,
) : JvmSerialPort {
    private var opened = false
    var closeCalls = 0
        private set

    override val isOpen: Boolean
        get() = opened

    override fun configure(parameters: SerialParameters) = Unit

    override fun open(): Boolean {
        opened = true
        return true
    }

    override fun close(): Boolean {
        closeCalls += 1
        closeStarted?.countDown()
        check(allowClose?.await(2, TimeUnit.SECONDS) != false) { "Timed out waiting to complete fake close" }
        if (closeResult) opened = false
        return closeResult
    }

    override fun clearDtr() = Unit

    override fun clearRts() = Unit

    override fun read(bytes: ByteArray): Int = -1

    override fun write(
        bytes: ByteArray,
        length: Int,
        offset: Int,
    ): Int = length
}
