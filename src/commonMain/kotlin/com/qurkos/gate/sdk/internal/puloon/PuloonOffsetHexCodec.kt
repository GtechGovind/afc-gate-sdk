package com.qurkos.gate.sdk.internal.puloon

/** Encodes and decodes Puloon hexadecimal nibbles represented by the contiguous byte range `0x30..0x3F`. */
internal object PuloonOffsetHexCodec {
    /** Encodes one unsigned byte without converting values ten through fifteen to ASCII letters. */
    fun encode(value: Int): ByteArray {
        require(value in 0..MAX_UNSIGNED_BYTE) { "Puloon offset-hex value must be between 0 and 255" }
        return byteArrayOf(
            (NIBBLE_BASE + value / HEX_BASE).toByte(),
            (NIBBLE_BASE + value % HEX_BASE).toByte(),
        )
    }

    /** Decodes two offset-hex nibbles at [offset], reporting the owning field [name] on failure. */
    fun decode(
        bytes: ByteArray,
        offset: Int,
        name: String,
    ): Int {
        require(offset >= 0 && offset + 1 < bytes.size) { "Puloon $name requires two bytes at offset $offset" }
        val high = decodeNibble(bytes[offset], offset, name)
        val low = decodeNibble(bytes[offset + 1], offset + 1, name)
        return high * HEX_BASE + low
    }

    private fun decodeNibble(
        byte: Byte,
        offset: Int,
        name: String,
    ): Int {
        val value = byte.toInt() and MAX_UNSIGNED_BYTE
        require(value in NIBBLE_BASE..NIBBLE_MAX) {
            "Invalid Puloon $name at payload offset $offset: actual ${value.hexByte()}, expected 0x30..0x3F"
        }
        return value - NIBBLE_BASE
    }

    private fun Int.hexByte(): String = "0x${toString(HEX_BASE).uppercase().padStart(2, '0')}"

    private const val NIBBLE_BASE = 0x30
    private const val NIBBLE_MAX = 0x3F
    private const val HEX_BASE = 16
    private const val MAX_UNSIGNED_BYTE = 0xFF
}
