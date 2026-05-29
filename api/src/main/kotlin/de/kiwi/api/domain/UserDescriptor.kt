package de.kiwi.api.domain

import java.io.Serializable
import kotlin.reflect.KClass
import kotlin.reflect.safeCast

/**
 * Descriptor of the currently authenticated user for the current request.
 */
interface UserDescriptor : Serializable {
    /** Numeric ID associated with the user, or `null` if the identity can not be expressed as a [Long] */
    val userId: Long?

    /** Unique identity value associated with the user, which can be of any type */
    val identity: Any

    /** Unique username associated with the user */
    val username: String

    /** True if the user is authenticated, false if anonymous */
    val authenticated: Boolean

    /** Roles granted to the user */
    val roles: Set<String>

    /** Underlying principal of the user's authentication, like `UserDetails` or `JWT` */
    val principal: Any?

    /** Flag if the user is an administrator, by default if the user is granted the [Roles.ADMIN] role */
    val isAdmin get() = roles.contains(Roles.ADMIN)

    /** Check if the user's granted roles contain the given role, case-insensitive */
    fun hasRole(role: String) = roles.any { it.equals(role, ignoreCase = true) }

    /** Check if the user's granted roles contain all given roles, case-insensitive */
    fun hasAllRoles(vararg roles: String) = roles.all(::hasRole)

    /** Check if the user's granted roles contain any of the given roles, case-insensitive */
    fun hasAnyRole(vararg roles: String) = roles.any(::hasRole)

    companion object {
        const val SYSTEM_USERNAME = "SYSTEM"
        const val ANONYMOUS_USERNAME = "ANONYMOUS"
    }

    data object SYSTEM : UserDescriptor {
        override val userId = null
        override val identity = this
        override val username = SYSTEM_USERNAME
        override val authenticated = true
        override val roles = emptySet<String>()
        override val principal = null

        override fun hasRole(role: String) = true
        override fun hasAllRoles(vararg roles: String) = true
        override fun hasAnyRole(vararg roles: String) = true

        private fun readResolve(): Any = SYSTEM
    }

    data object ANONYMOUS : UserDescriptor {
        override val userId = null
        override val identity = this
        override val username = ANONYMOUS_USERNAME
        override val authenticated = false
        override val roles = emptySet<String>()
        override val principal = null

        override fun hasRole(role: String) = false
        override fun hasAllRoles(vararg roles: String) = false
        override fun hasAnyRole(vararg roles: String) = false

        private fun readResolve(): Any = ANONYMOUS
    }
}

fun <T : Any> UserDescriptor.principal(type: KClass<T>): T? = principal?.let { type.safeCast(it) }

inline fun <reified T : Any> UserDescriptor.principal(): T? = principal(T::class)

fun UserDescriptor.hasRoleOrAdmin(role: String) = isAdmin || hasRole(role)
fun UserDescriptor.hasAllRolesOrAdmin(vararg roles: String) = isAdmin || hasAllRoles(*roles)
fun UserDescriptor.hasAnyRoleOrAdmin(vararg roles: String) = isAdmin || hasAnyRole(*roles)

fun UserDescriptor.requireAuthenticated() =
    require(authenticated)

fun UserDescriptor.requireRole(role: String, orAdmin: Boolean = true) =
    require(if (orAdmin) hasRoleOrAdmin(role) else hasRole(role))

fun UserDescriptor.requireAllRoles(vararg roles: String, orAdmin: Boolean = true) =
    require(if (orAdmin) hasAllRolesOrAdmin(*roles) else hasAllRoles(*roles))

fun UserDescriptor.requireAnyRole(vararg roles: String, orAdmin: Boolean = true) =
    require(if (orAdmin) hasAnyRoleOrAdmin(*roles) else hasAnyRole(*roles))

private fun UserDescriptor.require(authorized: Boolean) {
    if (!authorized) throw AccessDenied(this)
}
