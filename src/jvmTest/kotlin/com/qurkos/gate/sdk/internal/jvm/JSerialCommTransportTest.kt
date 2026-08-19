package com.qurkos.gate.sdk.internal.jvm

import com.qurkos.gate.sdk.internal.SerialTransportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JSerialCommTransportTest {
    @Test
    fun closedTransportRejectsWritesWithoutHardware() =
        runBlocking {
            val transport = JSerialCommTransport(Dispatchers.Unconfined)

            assertFailsWith<IllegalStateException> { transport.write(byteArrayOf(1)) }
            assertEquals(SerialTransportState.CLOSED, transport.state.value)
        }
}
