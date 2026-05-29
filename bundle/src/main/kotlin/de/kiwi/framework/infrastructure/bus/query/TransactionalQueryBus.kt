package de.kiwi.framework.infrastructure.bus.query

import de.kiwi.api.application.service.TransactionService
import de.kiwi.api.domain.Query
import de.kiwi.api.application.query.QueryBus
import de.kiwi.api.domain.UserDescriptor

class TransactionalQueryBus(
    private val transactionService: TransactionService,
    private val delegate: QueryBus.Configurable,
) : QueryBus.Configurable by delegate {
    override fun <Q : Query<R>, R> resolve(query: Q, user: UserDescriptor): R {
        val resolve = { delegate.resolve(query, user) }
        return when {
            query.transactional && query.readOnly -> transactionService.read { resolve() }
            query.transactional -> transactionService.write { resolve() }
            else -> resolve()
        }
    }
}
