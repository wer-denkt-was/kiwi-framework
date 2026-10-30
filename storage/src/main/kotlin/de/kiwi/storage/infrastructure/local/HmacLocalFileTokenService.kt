package de.kiwi.storage.infrastructure.local

import de.kiwi.api.application.service.ClockProvider
import de.kiwi.storage.infrastructure.local.LocalFileTokenService.FileAccessToken
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Default [LocalFileTokenService] generating compact, URL-safe tokens of the form `{payload}.{signature}`:
 * - `payload` is the Base64URL-encoded string `v1:{fileUuid}:{expiresOnEpochSeconds}`
 * - `signature` is the Base64URL-encoded HMAC-SHA256 of the encoded payload, using the configured secret key
 *
 * Tokens are stateless, so all application instances sharing the same secret key accept each other's tokens.
 */
class HmacLocalFileTokenService(
    secretKey: ByteArray,
    private val clockProvider: ClockProvider,
) : LocalFileTokenService {

    private val key = SecretKeySpec(secretKey.copyOf(), HMAC_ALGORITHM)

    init {
        require(secretKey.size >= MIN_SECRET_KEY_BYTES) {
            "Local file token secret key must have at least ${MIN_SECRET_KEY_BYTES * 8} bits"
        }
    }

    companion object {
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val MIN_SECRET_KEY_BYTES = 32

        private const val TOKEN_VERSION = "v1"
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()
    }

    override fun generateToken(fileUuid: UUID, duration: Duration): FileAccessToken {
        val clock = clockProvider.clock()
        val expiresOn = Instant.now(clock).plus(duration)
        val payload = encoder.encodeToString("$TOKEN_VERSION:$fileUuid:${expiresOn.epochSecond}".toByteArray())
        val signature = encoder.encodeToString(sign(payload))
        val token = "$payload.$signature"
        return FileAccessToken(token, expiresOn)
    }

    override fun verifyToken(token: String, fileUuid: UUID): Boolean {
        val clock = clockProvider.clock()
        val (payload, signature) = token.split('.')
            .takeIf { it.size == 2 }
            ?: return false

        // Verify signature before interpreting the payload, using a constant-time comparison
        val actualSignature = decodeOrNull(signature) ?: return false
        if (!MessageDigest.isEqual(sign(payload), actualSignature)) return false

        val (version, uuid, expiresOn) = decodeOrNull(payload)?.decodeToString()
            ?.split(':')
            ?.takeIf { it.size == 3 }
            ?: return false

        val expiresOnEpochSecond = expiresOn.toLongOrNull() ?: return false

        return version == TOKEN_VERSION &&
                uuid == fileUuid.toString() &&
                Instant.now(clock).epochSecond < expiresOnEpochSecond
    }

    private fun sign(payload: String): ByteArray {
        return Mac.getInstance(HMAC_ALGORITHM).run {
            init(key)
            doFinal(payload.toByteArray())
        }
    }

    private fun decodeOrNull(value: String): ByteArray? = try {
        decoder.decode(value)
    } catch (_: IllegalArgumentException) {
        null
    }
}
