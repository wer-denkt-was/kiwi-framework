package de.kiwi.demo.access.domain.query

import de.kiwi.api.domain.EntityId
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.generic.GenericEntityQuery
import de.kiwi.demo.access.domain.UserAccount
import de.kiwi.demo.access.domain.accessControl.readUserAccount

class UserAccountQuery : GenericEntityQuery<UserAccount>() {
    override val accessControl = readUserAccount
    override val accessControlAllowAdmin = true

    override fun filterById(id: EntityId) = baseEntityFilter<UserAccount>(id)
    override fun preAuthorize(user: UserDescriptor) = Unit
}
