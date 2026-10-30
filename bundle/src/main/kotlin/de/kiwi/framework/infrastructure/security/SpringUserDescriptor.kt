package de.kiwi.framework.infrastructure.security

import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.domain.UserIdDetails
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.core.userdetails.UserDetails

/**
 * [UserDescriptor] of Spring Security [UserDetails].
 * The [roles] are the names of the granted authorities, without the given [rolePrefix] (e.g. `ROLE_ADMIN` is `ADMIN`),
 * as Spring Security's `User.roles(...)` and `hasRole(...)` add the prefix `ROLE_` by default.
 */
class SpringUserDescriptor(
    private val userDetails: UserDetails,
    private val rolePrefix: String = DEFAULT_ROLE_PREFIX,
) : UserDescriptor {
    companion object {
        const val DEFAULT_ROLE_PREFIX = "ROLE_"
    }

    override val userId: Long? get() = if (userDetails is UserIdDetails) userDetails.userId else null
    override val identity: Any get() = userId ?: userDetails.username
    override val username: String get() = userDetails.username
    override val authenticated: Boolean get() = userDetails.isEnabled
    override val roles: Set<String>
        get() = AuthorityUtils.authorityListToSet(userDetails.authorities).mapTo(LinkedHashSet()) { authority ->
            authority.removePrefix(rolePrefix)
        }
    override val principal: Any get() = userDetails
}
