package de.kiwi.api.application.service

import tools.jackson.databind.JsonNode
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.schema.EntityFieldset
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification

/**
 * Convenience service for building an entity query for a given
 * [EntityFieldset], using [Specification] for filtering, [Sort] for sorting, and [Pageable] for pagination.
 */
interface QueryBuilder {
    fun <E : Any> select(fieldset: EntityFieldset<E>): Select<E>

    interface Select<E : Any> : Executor<JsonNode> {
        fun where(specification: Specification<E>): Select<E>
        fun sort(sort: Sort): Select<E>
    }

    interface Executor<R : Any> {
        fun page(pagination: Pageable, user: UserDescriptor? = null): Page<R>
        fun all(user: UserDescriptor? = null): List<R>
        fun single(user: UserDescriptor? = null): R?
    }
}
