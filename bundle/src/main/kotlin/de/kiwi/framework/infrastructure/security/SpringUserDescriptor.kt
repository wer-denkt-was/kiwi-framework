package de.kiwi.framework.infrastructure.security

import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.domain.UserIdDetails
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.core.userdetails.UserDetails

class SpringUserDescriptor(
    private val userDetails: UserDetails,
) : UserDescriptor {
    override val userId: Long? get() = if (userDetails is UserIdDetails) userDetails.userId else null
    override val identity: Any get() = userId ?: userDetails.username
    override val username: String get() = userDetails.username
    override val authenticated: Boolean get() = userDetails.isEnabled
    override val roles: Set<String> get() = AuthorityUtils.authorityListToSet(userDetails.authorities)
    override val principal: Any get() = userDetails
}
