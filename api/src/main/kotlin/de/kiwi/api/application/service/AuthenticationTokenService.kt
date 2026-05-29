package de.kiwi.api.application.service

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import java.time.Duration
import java.time.Instant

/**
 * Application service generating and using tokens for authentication.
 * This can be [JWTs](https://jwt.io/), opaque tokens, or other kinds of bearer tokens.
 */
interface AuthenticationTokenService {
    /**
     * Generate a compact JWT from the given authentication
     */
    fun generateToken(authentication: Authentication): String

    /** Generate a compact JWT from the given authentication which expires after a given duration */
    fun generateTemporaryToken(authentication: Authentication, duration: Duration): TemporaryToken

    /**
     * Extract the authentication represented by the given JWT,
     * returning a fully authenticated authentication if successful
     */
    fun authenticateToken(bearerToken: String): UsernamePasswordAuthenticationToken

    data class TemporaryToken(val token: String, val expiresOn: Instant?)
}
