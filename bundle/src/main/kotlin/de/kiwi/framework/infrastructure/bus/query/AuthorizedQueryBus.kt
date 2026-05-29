package de.kiwi.framework.infrastructure.bus.query

import de.kiwi.api.application.query.QueryBus
import de.kiwi.api.domain.Authorized
import de.kiwi.api.domain.Query
import de.kiwi.api.domain.UserDescriptor

class AuthorizedQueryBus(private val delegate: QueryBus.Configurable) : QueryBus.Configurable by delegate {
    override fun <Q : Query<R>, R> resolve(query: Q, user: UserDescriptor): R {
        if (query is Authorized) query.preAuthorize(user)
        val result = delegate.resolve(query, user)
        if (query is Authorized) result?.let { query.postAuthorize(it, user) }
        return result
    }
}
