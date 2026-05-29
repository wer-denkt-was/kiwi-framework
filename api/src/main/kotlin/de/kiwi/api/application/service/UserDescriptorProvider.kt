package de.kiwi.api.application.service

import de.kiwi.api.domain.UserDescriptor

/**
 * Service providing a [UserDescriptor] of the authenticated user for the current request.
 */
fun interface UserDescriptorProvider {
    /**
     * Get the authenticated [UserDescriptor] of the current request.
     * If no user is authenticated, [UserDescriptor.ANONYMOUS] is returned.
     * If the request is executed internally by the system itself, [UserDescriptor.SYSTEM] is returned.
     */
    fun currentUser(): UserDescriptor
}
