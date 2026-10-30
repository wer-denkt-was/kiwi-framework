package de.kiwi.storage.infrastructure.local

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.*

class HmacLocalFileTokenServiceTest {
    private val secretKey = ByteArray(32) { it.toByte() }
    private val now = Instant.parse("2026-01-01T12:00:00Z")
    private var clock = Clock.fixed(now, ZoneOffset.UTC)
    private val tokenService = HmacLocalFileTokenService(secretKey, clockProvider = { clock })

    private val fileUuid = UUID.randomUUID()

    @Test
    fun validToken() {
        val (token, expiresOn) = tokenService.generateToken(fileUuid, Duration.ofMinutes(30))

        assertEquals(now.plus(Duration.ofMinutes(30)), expiresOn)
        assertTrue(tokenService.verifyToken(token, fileUuid))
        assertTrue(token.matches(Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")), "Token must be URL-safe: $token")
    }

    @Test
    fun expiredToken() {
        val (token, _) = tokenService.generateToken(fileUuid, Duration.ofMinutes(30))

        clock = Clock.fixed(now.plus(Duration.ofMinutes(30)), ZoneOffset.UTC)
        assertFalse(tokenService.verifyToken(token, fileUuid))
    }

    @Test
    fun tokenForOtherFile() {
        val (token, _) = tokenService.generateToken(fileUuid, Duration.ofMinutes(30))

        assertFalse(tokenService.verifyToken(token, UUID.randomUUID()))
    }

    @Test
    fun tokenWithOtherSecretKey() {
        val otherSecretKey = ByteArray(32) { 42 }
        val otherTokenService = HmacLocalFileTokenService(otherSecretKey, clockProvider = { clock })
        val (token, _) = otherTokenService.generateToken(fileUuid, Duration.ofMinutes(30))

        assertFalse(tokenService.verifyToken(token, fileUuid))
    }

    @Test
    fun tamperedToken() {
        val (token, _) = tokenService.generateToken(fileUuid, Duration.ofMinutes(30))
        val (_, signature) = token.split('.')
        val extendedPayload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("v1:$fileUuid:${now.plus(Duration.ofDays(365)).epochSecond}".toByteArray())

        assertFalse(tokenService.verifyToken("$extendedPayload.$signature", fileUuid))
    }

    @Test
    fun malformedTokens() {
        listOf("", ".", "a.b.c", "not-a-token", "!!!.???").forEach { token ->
            assertFalse(tokenService.verifyToken(token, fileUuid), "Malformed token [$token] must be rejected")
        }
    }

    @Test
    fun secretKeyTooShort() {
        assertThrows<IllegalArgumentException> {
            val tooShortKey = ByteArray(16)
            HmacLocalFileTokenService(tooShortKey, clockProvider = { clock })
        }
    }
}
