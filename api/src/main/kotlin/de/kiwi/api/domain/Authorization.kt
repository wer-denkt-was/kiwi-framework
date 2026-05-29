package de.kiwi.api.domain

/**
 * DomainException to throw when access for a given [UserDescriptor] is denied.
 */
class AccessDenied(user: UserDescriptor) : DomainException(
    status = Status.Forbidden,
    code = "access-denied",
    args = mapOf("authenticated" to user.authenticated),
)

/**
 * Global object providing available roles throughout the system and modules.
 * Additional roles should be declared as extension members of type String.
 *
 * __Example__:
 * ```kt
 * val Roles.CUSTOM_ROLE get() = "CUSTOM_ROLE"
 * ```
 */
object Roles {
    /**
     * Standard role for any authenticated user of the application.
     */
    const val USER = "USER"

    /**
     * Standard role for an administrator user. This role is a __special case__,
     * as all authorization checks will succeed if a [UserDescriptor] is assigned this role.
     */
    const val ADMIN = "ADMIN"
}

/**
 * Evaluate if the [UserDescriptor] is authorized using the given [authorize] condition,
 * and throw an [AccessDenied] exception if the condition is not satisfied.
 */
@Throws(AccessDenied::class)
inline fun UserDescriptor.requireAuthorization(authorize: UserDescriptor.() -> Boolean): UserDescriptor =
    if (authorize()) this
    else throw AccessDenied(this)

/**
 * Pass if the [UserDescriptor] is an administrator using [UserDescriptor.isAdmin], i.e. has the [Roles.ADMIN] role,
 * otherwise evaluate if the user is authorized using the given [authorizeIfNotAdmin] condition,
 * and throw an [AccessDenied] exception if the condition is not satisfied.
 * @see UserDescriptor.requireAuthorization
 */
@Throws(AccessDenied::class)
inline fun UserDescriptor.requireAdminOr(authorizeIfNotAdmin: UserDescriptor.() -> Boolean): UserDescriptor =
    requireAuthorization { isAdmin || authorizeIfNotAdmin() }

/**
 * Pass if the [UserDescriptor] is an administrator using [UserDescriptor.isAdmin], i.e. has the [Roles.ADMIN] role,
 * otherwise throw an [AccessDenied] exception.
 * @see UserDescriptor.requireAuthorization
 */
@Throws(AccessDenied::class)
fun UserDescriptor.requireAdmin(): UserDescriptor =
    requireAuthorization { isAdmin }
