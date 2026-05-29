package de.kiwi.demo.access.domain

import de.kiwi.api.domain.UserDescriptor

data class UserAccountDescriptor(
    override val principal: UserAccount,
) : UserDescriptor {
    override val authenticated = true
    override val userId = principal.id.value
    override val identity = principal.externalIdentity ?: ""
    override val username = principal.username
    override val roles = principal.userRoles.toSet()
}
