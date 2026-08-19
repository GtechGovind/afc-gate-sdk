package com.qurkos.gate.sdk.internal.puloon

import com.qurkos.gate.sdk.GateError
import com.qurkos.gate.sdk.GateResult
import com.qurkos.gate.sdk.internal.FrameDecodeResult
import com.qurkos.gate.sdk.internal.GateResponse
import com.qurkos.gate.sdk.internal.ProtocolFrame
import com.qurkos.gate.sdk.internal.SerialTransaction
import com.qurkos.gate.sdk.internal.StreamingFrameDecoder
import com.qurkos.gate.sdk.internal.protocolFailure

/**
 * Validated immutable Puloon GCU frame content, excluding wire delimiters and CRC text.
 *
 * Mutable payload input and output are defensively copied so codec state cannot be modified by callers.
 *
 * @property sequence Unsigned 16-bit little-endian request/response correlation value.
 * @property retry Unsigned 8-bit retry attempt field.
 * @property payload Non-empty command and data bytes, defensively copied on input.
 */
internal class PuloonFrame(
    val sequence: Int,
    val retry: Int,
    payload: ByteArray,
) : ProtocolFrame {
    private val payloadBytes = payload.copyOf()

    init {
        require(sequence in 0..MAX_SEQUENCE)
        require(retry in 0..MAX_RETRY)
        require(payloadBytes.isNotEmpty())
        require(payloadBytes.size <= MAX_PAYLOAD_LENGTH)
    }

    /** Defensive copy of command and data bytes. */
    val payload: ByteArray get() = payloadBytes.copyOf()

    /** First payload byte used as the GCU command identifier. */
    val command: Byte get() = payloadBytes.first()

    /** Frame field bounds defined by the Puloon protocol and defensive memory policy. */
    companion object {
        private const val MAX_SEQUENCE = 0xFFFF
        private const val MAX_RETRY = 0xFF
        private const val MAX_PAYLOAD_LENGTH = 4_096
    }
}

/** Stateless encoder, decoder, and CRC implementation for Puloon GCU wire frames. */
internal object PuloonFrameCodec {
    /** Line-feed start delimiter. */
    const val LF: Byte = 0x0A

    /** Carriage-return end delimiter. */
    const val CR: Byte = 0x0D

    /** Encodes [frame] with LF/CR delimiters and uppercase CRC-16/XMODEM text. */
    fun encode(frame: PuloonFrame): ByteArray {
        val payload = frame.payload
        val protected =
            byteArrayOf(
                frame.sequence.toByte(),
                (frame.sequence shr BITS_PER_BYTE).toByte(),
                frame.retry.toByte(),
            ) + payload
        val crc = crc16Xmodem(protected).toString(RADIX_HEX).uppercase().padStart(CRC_TEXT_LENGTH, '0')
        return byteArrayOf(LF) + protected + crc.encodeToByteArray() + CR
    }

    /** Validates delimiters, length, hexadecimal CRC, and checksum before returning an immutable frame. */
    fun decode(bytes: ByteArray): PuloonFrame {
        require(bytes.size >= MINIMUM_FRAME_LENGTH) { "Puloon frame is too short" }
        require(bytes.first() == LF && bytes.last() == CR) { "Invalid Puloon frame delimiters" }
        val protected = bytes.copyOfRange(1, bytes.size - CRC_TEXT_LENGTH - 1)
        val expected =
            bytes
                .copyOfRange(bytes.size - CRC_TEXT_LENGTH - 1, bytes.lastIndex)
                .decodeToString()
                .toIntOrNull(RADIX_HEX)
                ?: throw IllegalArgumentException("Invalid Puloon CRC text")
        require(crc16Xmodem(protected) == expected) { "Puloon CRC mismatch" }
        val sequence =
            protected[0].unsigned() or
                (protected[1].unsigned() shl BITS_PER_BYTE)
        return PuloonFrame(
            sequence = sequence,
            retry = protected[2].unsigned(),
            payload = protected.copyOfRange(3, protected.size),
        )
    }

    /** Computes the unsigned CRC-16/XMODEM value used by the GCU specification. */
    fun crc16Xmodem(bytes: ByteArray): Int {
        var crc = 0
        for (byte in bytes) {
            crc = crc xor (byte.unsigned() shl BITS_PER_BYTE)
            repeat(BITS_PER_BYTE) {
                crc =
                    if (crc and CRC_HIGH_BIT != 0) {
                        (crc shl 1 xor CRC_POLYNOMIAL) and MAX_UNSIGNED_SHORT
                    } else {
                        (crc shl 1) and MAX_UNSIGNED_SHORT
                    }
            }
        }
        return crc
    }

    /** Converts a signed Kotlin byte to its unsigned integer representation. */
    private fun Byte.unsigned(): Int = toInt() and MAX_UNSIGNED_BYTE

    private const val BITS_PER_BYTE = 8
    private const val MAX_UNSIGNED_BYTE = 0xFF
    private const val MAX_UNSIGNED_SHORT = 0xFFFF
    private const val CRC_HIGH_BIT = 0x8000
    private const val CRC_POLYNOMIAL = 0x1021
    private const val CRC_TEXT_LENGTH = 4
    private const val MINIMUM_FRAME_LENGTH = 10
    private const val RADIX_HEX = 16
}

/**
 * Incremental Puloon decoder tolerant of serial fragmentation, coalescing, and prefix noise.
 *
 * Buffered unterminated input is capped to prevent malformed devices from causing unbounded memory growth.
 */
internal class PuloonFrameDecoder : StreamingFrameDecoder {
    private var buffer = ByteArray(INITIAL_BUFFER_CAPACITY)
    private var bufferedSize = 0

    /** Adds a defensive copy of [bytes] and emits all complete frames/errors currently available. */
    override fun feed(bytes: ByteArray): List<FrameDecodeResult> {
        if (bytes.isNotEmpty()) {
            if (bufferedSize + bytes.size > MAX_BUFFER_LENGTH) {
                reset()
                return listOf(FrameDecodeResult.Error("Puloon receive buffer exceeded $MAX_BUFFER_LENGTH bytes"))
            }
            ensureCapacity(bufferedSize + bytes.size)
            bytes.copyInto(buffer, destinationOffset = bufferedSize)
            bufferedSize += bytes.size
        }
        val results = mutableListOf<FrameDecodeResult>()
        while (true) {
            val result = readNext() ?: break
            results += result
        }
        return results
    }

    /** Discards any partial frame at a serial-session boundary. */
    override fun reset() {
        bufferedSize = 0
    }

    /** Extracts and validates the next delimited candidate, or waits for additional bytes. */
    private fun readNext(): FrameDecodeResult? {
        val start = (0 until bufferedSize).firstOrNull { buffer[it] == PuloonFrameCodec.LF } ?: -1
        if (start < 0) {
            bufferedSize = 0
            return null
        }
        if (start > 0) discardPrefix(start)
        val end =
            (MINIMUM_END_INDEX until bufferedSize).firstOrNull { buffer[it] == PuloonFrameCodec.CR }
                ?: return null
        val candidate = buffer.copyOfRange(0, end + 1)
        discardPrefix(end + 1)
        return try {
            FrameDecodeResult.Frame(PuloonFrameCodec.decode(candidate))
        } catch (error: IllegalArgumentException) {
            FrameDecodeResult.Error(error.message ?: "Invalid Puloon frame")
        }
    }

    /** Grows the reusable receive array geometrically while respecting the hard memory bound. */
    private fun ensureCapacity(required: Int) {
        if (required <= buffer.size) return
        val capacity = maxOf(required, buffer.size * BUFFER_GROWTH_FACTOR).coerceAtMost(MAX_BUFFER_LENGTH)
        buffer = buffer.copyOf(capacity)
    }

    /** Removes [count] consumed/noise bytes in place without retaining a growing chain of arrays. */
    private fun discardPrefix(count: Int) {
        require(count in 0..bufferedSize)
        val remaining = bufferedSize - count
        if (remaining > 0) buffer.copyInto(buffer, destinationOffset = 0, startIndex = count, endIndex = bufferedSize)
        bufferedSize = remaining
    }

    private companion object {
        const val INITIAL_BUFFER_CAPACITY = 256
        const val BUFFER_GROWTH_FACTOR = 2
        const val MINIMUM_END_INDEX = 9
        const val MAX_BUFFER_LENGTH = 8_192
    }
}

/** Converts one documented ASCII wire character to its byte representation. */
internal fun ascii(value: Char): Byte = value.code.toByte()

/**
 * Puloon request/response correlation and response-status decoder for one logical command.
 *
 * Sequence and allowed response commands identify the response. Retry attempts reuse the sequence and update only the
 * documented retry field.
 */
internal class PuloonTransaction(
    private val sequence: Int,
    private val command: Char,
    private val requestData: ByteArray,
    private val responseCommands: Set<Byte>,
    override val idempotent: Boolean,
    private val responseDecoder: (ByteArray) -> GateResponse,
) : SerialTransaction {
    private val immutableRequestData = requestData.copyOf()

    override val operationName: String = command.toString()

    /** Encodes this transaction for zero-based retry [attempt]. */
    override fun encode(attempt: Int): ByteArray =
        PuloonFrameCodec.encode(
            PuloonFrame(
                sequence = sequence,
                retry = attempt,
                payload = byteArrayOf(ascii(command)) + immutableRequestData,
            ),
        )

    /** Accepts only Puloon frames with this sequence and an expected response command. */
    override fun matches(frame: ProtocolFrame): Boolean =
        frame is PuloonFrame && frame.sequence == sequence && frame.command in responseCommands

    /** Maps a validated correlated frame into acknowledgement, device failure, or decoded payload. */
    override fun decode(frame: ProtocolFrame): GateResult<GateResponse> {
        val puloonFrame = frame as? PuloonFrame ?: return protocolFailure("Expected a Puloon response")
        if (puloonFrame.payload.size < MINIMUM_RESPONSE_LENGTH) {
            return protocolFailure("Puloon response is too short")
        }
        val errorCode = puloonFrame.payload.copyOfRange(1, 3).decodeToString()
        return if (errorCode == SUCCESS_CODE) {
            decodeSuccess(puloonFrame)
        } else {
            GateResult.Failure(GateError.Device(errorCode, deviceErrorMessage(errorCode)))
        }
    }

    /** Runs command-specific payload decoding while translating malformed data into a protocol failure. */
    private fun decodeSuccess(frame: PuloonFrame): GateResult<GateResponse> =
        try {
            GateResult.Success(responseDecoder(frame.payload.copyOfRange(3, frame.payload.size)))
        } catch (error: IllegalArgumentException) {
            protocolFailure(error.message ?: "Invalid Puloon response payload")
        }

    /** Returns the specification description for a known two-character GCU error code. */
    private fun deviceErrorMessage(code: String): String? =
        when (code) {
            "01" -> "Invalid command"
            "02" -> "Invalid parameter"
            "03" -> "Controller busy"
            "04" -> "Hardware error"
            else -> null
        }

    private companion object {
        const val SUCCESS_CODE = "00"
        const val MINIMUM_RESPONSE_LENGTH = 3
    }
}
