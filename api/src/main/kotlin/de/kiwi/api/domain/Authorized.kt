package de.kiwi.api.domain

/**
 * Interface for declaring commands/queries as authorized,
 * providing methods for permitting/denying execution of a command or query.
 *
 * __Example__:
 * ```kt
 * class ExampleAction : Command(), Authorizes {
 *   fun preAuthorize(user: UserDescriptor) = user.requireRole(Roles.MY_ROLE)
 *   fun postAuthorize(result: Any, user: UserDescriptor) {/* ... */}
 * }
 * ```
 */
interface Authorized {
    /**
     * Authenticate this command/query **before** execution.
     * To deny access, this method should throw a [DomainException]
     * with [DomainException.Status.Forbidden].
     */
    fun preAuthorize(user: UserDescriptor)

    /**
     * Authenticate this command/query **after** execution with the execution result.
     * To deny access, this method should throw a [DomainException]
     * with [DomainException.Status.Forbidden].
     */
    fun postAuthorize(result: Any, user: UserDescriptor) = Unit
}
