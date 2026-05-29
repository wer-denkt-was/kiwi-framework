package de.kiwi.api.util

// Base32 ID substitutions:
// - O => 0
// - I => 1
// - Z => 2
// - S => 5

// 5-bit encoding table
private val ENCODE_TABLE = charArrayOf(
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f', // 00-0F
    'g', 'h', 'j', 'k', 'l', 'm', 'n', 'p', 'q', 'r', 't', 'u', 'v', 'w', 'x', 'y', // 10-1F
)

// 5-bit decoding table
private val DECODE_TABLE = byteArrayOf(
    /* --- ASCII Table ---
    "0"   "1"   "2"   "3"   "4"   "5"   "6"   "7"   "8"   "9"                                       // 30-3F
          "A"   "B"   "C"   "D"   "E"   "F"   "G"   "H"   "I"   "J"   "K"   "L"   "M"   "N"   "O"   // 50-5F
    "P"   "Q"   "R"   "S"   "T"   "U"   "V"   "W"   "X"   "Y"   "Z"                                 // 60-6F
          "a"   "b"   "c"   "d"   "e"   "f"   "g"   "h"   "i"   "j"   "k"   "l"   "m"   "n"   "o"   // 90-9F
    "p"   "q"   "r"   "s"   "t"   "u"   "v"   "w"   "x"   "y"   "z"                                 // A0-AF
     */
    -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, // 00-0F
    -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, // 10-1F
    -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, // 20-2F
    0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, // 30-3F
    -0x1, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x01, 0x12, 0x13, 0x14, 0x15, 0x16, 0x00, // 40-4F
    0x17, 0x18, 0x19, 0x05, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F, 0x02, -0x1, -0x1, -0x1, -0x1, -0x1, // 50-5F
    -0x1, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x01, 0x12, 0x13, 0x14, 0x15, 0x16, 0x00, // 60-6F
    0x17, 0x18, 0x19, 0x05, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F, 0x02, -0x1, -0x1, -0x1, -0x1, -0x1, // 70-7F
    -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, // 80-8F
    -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, // 90-9F
    -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, // A0-AF
    -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, // B0-BF
    -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, // C0-CF
    -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, // D0-DF
    -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, // E0-EF
    -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, -0x1, // F0-FF
)

private const val BITS_PER_CHAR = 5
private const val BASE32_MASK = (1L shl BITS_PER_CHAR) - 1 // 0b11111

class InvalidBase32Decode(value: String) : IllegalArgumentException(value)

class InvalidBase32Encode(value: Long, maxValue: Long) : IllegalArgumentException("$value > $maxValue")

/**
 * Utility class for encoding and decoding integer values as compact, unambiguous Base32 strings.
 *
 * This Base32 variant avoids ambiguous characters by automatically substituting:
 * - 'O' with '0'
 * - 'I' with '1'
 * - 'Z' with '2'
 * - 'S' with '5'
 *
 * The class supports encoding/decoding of values with a configurable bit length and optional string reversal.
 * It is useful for generating short, human-friendly, case-insensitive IDs (e.g., for URLs or database keys).
 *
 * Example usage:
 * ```kotlin
 * val codec = Base32Id(bits = 40, reverse = false)
 * val encoded = codec.encode(12345L)
 * val decoded = codec.decode(encoded)
 * ```
 *
 * @property bits Number of bits to encode (determines the maximum value and string length)
 * @property reverse If true, encoded strings are reversed
 */
class Base32Id(
    val bits: Int,
    val reverse: Boolean,
) {
    val maxValue = (1L shl bits) - 1
    private val length = (bits / BITS_PER_CHAR) + if ((bits % BITS_PER_CHAR) > 0) 1 else 0

    fun encode(value: Long): String {
        if (value > maxValue) {
            throw InvalidBase32Encode(value, maxValue)
        }

        var remainder = value
        val chars = CharArray(length)
        for (i in 0..<length) {
            val byte = (remainder and BASE32_MASK).toByte()
            remainder = remainder ushr BITS_PER_CHAR
            chars[i] = ENCODE_TABLE[byte.toInt()]
        }
        return String(chars).turn().trimStart('0')
    }

    fun decode(string: String): Long {
        val value = string.padStart(length, '0').turn()
        if (value.length != length) {
            throw InvalidBase32Decode(string)
        }

        var result = 0L
        for (i in 0..<length) {
            val char = value[length - i - 1]
            val byte = DECODE_TABLE[char.code and 0xFF]
            if (byte < 0) {
                throw InvalidBase32Decode(string)
            }
            result = (result shl BITS_PER_CHAR) or byte.toLong()
        }
        return result
    }

    fun isValid(string: String): Boolean {
        val value = string.turn().padStart(length, '0')
        if (value.length != length) {
            return false
        }

        for (i in 0..<length) {
            val char = value[length - i - 1]
            val byte = DECODE_TABLE[char.code and 0xFF]
            if (byte < 0) {
                return false
            }
        }
        return true
    }

    private fun String.turn() = if (reverse) reversed() else this
}
