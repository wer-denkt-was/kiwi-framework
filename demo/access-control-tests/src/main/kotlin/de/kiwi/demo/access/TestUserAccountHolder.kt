package de.kiwi.demo.access

import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.schema.FieldAccessControl
import de.kiwi.api.schema.FieldAccessRequirementBuilder
import de.kiwi.api.util.slf4j
import de.kiwi.demo.access.domain.UserAccount
import de.kiwi.demo.access.domain.UserAccountDescriptor
import org.springframework.beans.factory.InitializingBean
import java.io.Closeable

private val log = slf4j<TestUserAccountHolder>()

class TestUserAccountHolder : InitializingBean, Closeable {
    companion object {
        // Global thread-local variable of the currently authenticated user-account
        private val currentUserAccount = InheritableThreadLocal<UserAccount?>()

        fun <E : Any> fieldAccess(
            allowAdmin: Boolean = true,
            require: FieldAccessRequirementBuilder<E> = { /* No requirements */ },
            checkAccess: (context: FieldAccessControl.Context<E>, account: UserAccount) -> Boolean,
        ) = FieldAccessControl(require = require) { context, _ ->
            val account = currentUserAccount.get()
            if (account == null) {
                log.warn("No current UserAccount available", Exception("currentUserAccount is empty"))
                false
            } else {
                (allowAdmin && account.isAdmin) || checkAccess(context, account)
            }
        }

        fun <E : Any> fieldAdminOnly() = fieldAccess<E>(allowAdmin = true, checkAccess = { _, _ -> false })
    }

    override fun afterPropertiesSet() {
        FieldAccessControl.provideFieldAccessUserContext {
            currentUserAccount.get()
                ?.let { UserAccountDescriptor(it) }
                ?: UserDescriptor.ANONYMOUS
        }
    }

    fun <T> withUserAccount(account: UserAccount, block: (user: UserDescriptor) -> T): T {
        try {
            currentUserAccount.set(account)
            return block(UserAccountDescriptor(account))
        } finally {
            currentUserAccount.remove()
        }
    }

    override fun close() {
        currentUserAccount.remove()
    }
}
