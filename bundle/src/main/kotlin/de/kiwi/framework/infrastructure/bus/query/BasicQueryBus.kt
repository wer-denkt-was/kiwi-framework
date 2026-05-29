package de.kiwi.framework.infrastructure.bus.query

import de.kiwi.api.application.query.QueryBus
import de.kiwi.api.application.query.QueryHandler
import de.kiwi.api.domain.Query
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.framework.infrastructure.bus.HandlerCache

class BasicQueryBus(private val enableCaching: Boolean) : QueryBus.Configurable {
    private val handlers = ArrayList<QueryHandler<*, *>>()
    private val cache = HandlerCache(::findHandler)

    val cacheHitRate: Double get() = cache.hitRate()

    override fun register(handler: QueryHandler<*, *>) {
        synchronized(handlers) { handlers.add(handler) }
    }

    override fun <Q : Query<R>, R> resolve(query: Q, user: UserDescriptor): R {
        val handler = if (enableCaching) cache.compute(query) else findHandler(query)
        @Suppress("UNCHECKED_CAST") val typedHandler = handler as QueryHandler<Q, R>
        return typedHandler.handle(query, user)
    }

    private fun findHandler(query: Query<*>): QueryHandler<*, *> = handlers
        .find { it.canHandle(query) }
        ?: throw AssertionError("Missing QueryHandler for ${query.javaClass.name}")
}
