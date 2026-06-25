package de.kiwi.demo.access.domain.accessControl

import de.kiwi.api.domain.AccessControlBuilder
import de.kiwi.demo.access.domain.UserAccount
import de.kiwi.demo.access.domain.UserAccount_

/**
 * AccessControl builder based on
 * ```
 * FROM UserAccount a
 * WHERE a.identity = :{UserDescriptor.identity}
 * ```
 */
val accessControl = AccessControlBuilder.byUserRelation<UserAccount> { account, user ->
    equal(account[UserAccount_.externalIdentity], user.identity)
}
