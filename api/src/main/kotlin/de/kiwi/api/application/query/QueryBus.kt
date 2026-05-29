package de.kiwi.api.application.query

import de.kiwi.api.domain.Query
import de.kiwi.api.domain.UserDescriptor

/**
 * Bus aggregating [QueryHandler]s to resolve [Query] requests.
 * This is a central interface in the core CQRS ("Command-Query Responsibility Segregation") system.
 */
interface QueryBus {
    /**
     * Resolve a `Query<R>` of type [Q], returning a result of type [R].
     */
    fun <Q : Query<R>, R> resolve(query: Q, user: UserDescriptor): R

    interface Configurable : QueryBus {
        /**
         * Register a new [QueryHandler] in this bus to handle [Query] requests.
         */
        fun register(handler: QueryHandler<*, *>)
    }
}
