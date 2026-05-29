package de.kiwi.api.domain

/**
 * Abstract base class for queries resolved by a [QueryBus][de.kiwi.api.application.query.QueryBus],
 * handled by a [QueryHandler][de.kiwi.api.application.query.QueryHandler].
 * @param R Result type returned by resolving this query
 */
abstract class Query<out R> : Transactional.ReadOnly<R>
