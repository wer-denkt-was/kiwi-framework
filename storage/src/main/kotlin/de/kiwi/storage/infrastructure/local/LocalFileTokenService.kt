package de.kiwi.storage.infrastructure.local

import java.time.Duration
import java.time.Instant
import java.util.*

/**
 * Service provider interface for generating and verifying tokens which grant temporary access to files
 * of the [LocalFileStorageService], e.g. as part of a signed file URI.
 * A default implementation is provided by [HmacLocalFileTokenService],
 * which can be replaced by declaring a bean of this type.
 */
interface LocalFileTokenService {
    /**
     * Generate a token granting access to the files identified by the given [fileUuid] for the given [duration].
     */
    fun generateToken(fileUuid: UUID, duration: Duration): FileAccessToken

    /**
     * Verify that the given [token] is valid, i.e. it is authentic, not expired and grants access
     * to the files identified by the given [fileUuid].
     */
    fun verifyToken(token: String, fileUuid: UUID): Boolean

    data class FileAccessToken(val token: String, val expiresOn: Instant)
}
