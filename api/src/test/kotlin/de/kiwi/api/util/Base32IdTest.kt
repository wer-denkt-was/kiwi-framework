package de.kiwi.api.util

import de.kiwi.api.util.Base32Id
import de.kiwi.api.util.InvalidBase32Decode
import de.kiwi.api.util.InvalidBase32Encode
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.function.Executable
import java.security.SecureRandom

private val random = SecureRandom()

class Base32IdTest {
    @Test
    fun encodeAndDecode32() = assertEncodeDecodeValid(Base32Id(bits = 32, reverse = false))

    @Test
    fun encodeAndDecode50() = assertEncodeDecodeValid(Base32Id(bits = 50, reverse = false))

    @Test
    fun encodeAndDecode63() = assertEncodeDecodeValid(Base32Id(bits = 63, reverse = false))

    @Test
    fun encodeAndDecode32reversed() = assertEncodeDecodeValid(Base32Id(bits = 32, reverse = true))

    @Test
    fun encodeAndDecode50reversed() = assertEncodeDecodeValid(Base32Id(bits = 50, reverse = true))

    @Test
    fun encodeAndDecode63reversed() = assertEncodeDecodeValid(Base32Id(bits = 63, reverse = true))

    @Test
    fun decodeInvalidLength() {
        assertThrows(InvalidBase32Decode::class.java) {
            Base32Id(bits = 16, reverse = false).decode("This code is way too long")
        }
    }

    @Test
    fun decodeInvalidChars() {
        assertThrows(InvalidBase32Decode::class.java) {
            Base32Id(bits = 63, reverse = false).decode("ÎŊǛÁłÌð")
        }
    }

    @Test
    fun encodeInvalidValue() {
        assertThrows(InvalidBase32Encode::class.java) {
            Base32Id(bits = 8, reverse = false).encode(256) // max 2^8-1 = 255
        }
    }

    private fun assertEncodeDecodeValid(base32Id: Base32Id) {
        val execute = Executable {
            val value = random.nextLong(base32Id.maxValue)
            val encoded = base32Id.encode(value)
            val decoded = base32Id.decode(encoded)
            //val original = value.toString().padStart(20)
            //println("$original => $encoded => $decoded")
            assertEquals(value, decoded, "Base32 encode/decode discrepancy")
        }

        println("Base64 encode/decode test for ${base32Id.bits} bits, reversed=${base32Id.reverse}")
        assertAll(List(10_000) { execute })
    }
}
