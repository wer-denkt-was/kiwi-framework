package de.kiwi.api.application.query

import de.kiwi.api.domain.Command
import de.kiwi.api.domain.Query
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.util.lookupSupertypeArgument

/**
 * Handler processing [Query] instances of a given subtype [Q], returning query results of type [R].
 * Only a single handler must exist for any given query class.
 * @param Q Query type handled by the implementing handler instance
 * @param R Result type returned by the query
 */
interface QueryHandler<Q, R> where Q : Query<R> {
    /**
     * Handle the execution of the given [Query] by the given [UserDescriptor]
     */
    fun handle(query: Q, user: UserDescriptor): R

    fun canHandle(query: Query<*>): Boolean {
        val handledType = javaClass.lookupSupertypeArgument<QueryHandler<*, *>>(0)
        return handledType.isInstance(query)
    }
}
