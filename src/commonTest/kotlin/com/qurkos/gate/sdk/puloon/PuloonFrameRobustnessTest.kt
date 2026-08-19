package com.qurkos.gate.sdk.puloon

import com.qurkos.gate.sdk.GateError
import com.qurkos.gate.sdk.GateResult
import com.qurkos.gate.sdk.internal.FrameDecodeResult
import com.qurkos.gate.sdk.internal.GateResponse
import com.qurkos.gate.sdk.internal.puloon.PuloonFrame
import com.qurkos.gate.sdk.internal.puloon.PuloonFrameCodec
import com.qurkos.gate.sdk.internal.puloon.PuloonFrameDecoder
import com.qurkos.gate.sdk.internal.puloon.PuloonTransaction
import com.qurkos.gate.sdk.internal.puloon.ascii
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PuloonFrameRobustnessTest {
    @Test
    fun frameFieldAndPayloadBoundsAreEnforced() {
        assertFailsWith<IllegalArgumentException> { PuloonFrame(-1, 0, byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { PuloonFrame(0x1_0000, 0, byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { PuloonFrame(0, -1, byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { PuloonFrame(0, 0x100, byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { PuloonFrame(0, 0, ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { PuloonFrame(0, 0, ByteArray(4_097)) }

        val maximum = PuloonFrame(0xFFFF, 0xFF, ByteArray(4_096) { 0x55 })
        val decoded = PuloonFrameCodec.decode(PuloonFrameCodec.encode(maximum))
        assertEquals(0xFFFF, decoded.sequence)
        assertEquals(0xFF, decoded.retry)
        assertEquals(4_096, decoded.payload.size)
    }

    @Test
    fun malformedFrameEnvelopeAndCrcTextAreRejected() {
        assertFailsWith<IllegalArgumentException> { PuloonFrameCodec.decode(ByteArray(9)) }
        assertFailsWith<IllegalArgumentException> {
            PuloonFrameCodec.decode(byteArrayOf(0, 0, 0, 0, ascii('S'), ascii('0'), ascii('0'), 0, 0, 0))
        }
        val frame = PuloonFrameCodec.encode(PuloonFrame(1, 0, "S00".encodeToByteArray()))
        frame[frame.lastIndex - 1] = ascii('Z')
        assertFailsWith<IllegalArgumentException> { PuloonFrameCodec.decode(frame) }
    }

    @Test
    fun reusableDecoderHandlesSustainedFragmentedTrafficInOrder() {
        val decoder = PuloonFrameDecoder()
        val expected =
            List(1_000) { index ->
                PuloonFrameCodec.encode(PuloonFrame(index, 0, "S00".encodeToByteArray()))
            }
        val stream = expected.fold(ByteArray(0), ByteArray::plus)
        val results = mutableListOf<FrameDecodeResult>()

        stream.asList().chunked(37).forEach { chunk -> results += decoder.feed(chunk.toByteArray()) }

        assertEquals(1_000, results.size)
        val sequences = results.map { assertIs<PuloonFrame>(assertIs<FrameDecodeResult.Frame>(it).value).sequence }
        assertEquals((0 until 1_000).toList(), sequences)
    }

    @Test
    fun decoderRecoversAfterBoundedBufferOverflow() {
        val decoder = PuloonFrameDecoder()
        assertIs<FrameDecodeResult.Error>(decoder.feed(ByteArray(9_000)).single())
        val valid = PuloonFrameCodec.encode(PuloonFrame(7, 0, "V00".encodeToByteArray()))

        val recovered = assertIs<FrameDecodeResult.Frame>(decoder.feed(valid).single())

        assertEquals(7, assertIs<PuloonFrame>(recovered.value).sequence)
    }

    @Test
    fun transactionCorrelationAndDeviceErrorsAreStrict() {
        val transaction =
            PuloonTransaction(
                sequence = 42,
                command = 'S',
                requestData = byteArrayOf(1),
                responseCommands = setOf(ascii('S')),
                idempotent = true,
                responseDecoder = { GateResponse.Acknowledged },
            )
        assertTrue(transaction.matches(PuloonFrame(42, 0, "S00".encodeToByteArray())))
        assertTrue(!transaction.matches(PuloonFrame(41, 0, "S00".encodeToByteArray())))
        assertTrue(!transaction.matches(PuloonFrame(42, 0, "V00".encodeToByteArray())))
        assertIs<GateResult.Success<GateResponse>>(transaction.decode(PuloonFrame(42, 0, "S00".encodeToByteArray())))

        val failure = assertIs<GateResult.Failure>(transaction.decode(PuloonFrame(42, 0, "S03".encodeToByteArray())))
        assertEquals(GateError.Device("03", "Controller busy"), failure.error)
    }

    @Test
    fun transactionDefensivelyCopiesRequestData() {
        val data = byteArrayOf(1, 2)
        val transaction = PuloonTransaction(1, 'U', data, setOf(ascii('U')), false) { GateResponse.Acknowledged }
        data.fill(9)

        val encoded = PuloonFrameCodec.decode(transaction.encode(0)).payload

        assertContentEquals(byteArrayOf(ascii('U'), 1, 2), encoded)
    }
}
