package de.kiwi.framework.infrastructure.schema.engine

import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.schema.*
import jakarta.persistence.EntityManager
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.support.PageableExecutionUtils

internal class QuerySchemaEngine<E : Any, N>(
    private val rootFieldset: Fieldset<E>,
    private val serializer: FieldsetSerializer<N>,
    private val entityManager: EntityManager,
    private val user: UserDescriptor?,
) {
    private val queryNodeFactory = QueryNodeFactory(
        rootFieldset = rootFieldset,
        entityManager = entityManager,
        selectSpanFactory = SelectSpanFactory(entityManager),
    )

    fun findOne(specification: Specification<E>): N? {
        val query = queryNodeFactory.createRootQuery(specification, Sort.unsorted())
        val found = query.execute(serializer, user)
        return found.firstOrNull()
    }

    fun findPage(
        specification: Specification<E>,
        pagination: Pageable,
        sort: Sort,
        totalLimit: Long?,
    ): Page<N> {
        // Choose sort by priority: Sort parameter, Pagination sort property, EntitySchema default sort, or unsorted
        val actualSort = sort.takeIf { it.isSorted }
            ?: pagination.sort.takeIf { it.isSorted }
            ?: rootFieldset.defaultSort?.takeIf { it.isSorted }
            ?: Sort.unsorted()

        // Create root query node for sort/specification/limit
        val queryNode = queryNodeFactory.createRootQuery(specification, actualSort)
        totalLimit?.let { queryNode.totalLimit(it) }

        // Execute root query, either paginated or with all items
        return if (pagination.isUnpaged) queryNode.executeUnpaginated()
        else queryNode.executeWithPagination(specification, pagination)
    }

    private fun RootQueryNode.executeUnpaginated(): Page<N> {
        val items = execute(serializer, user)
        return PageImpl(items)
    }

    private fun RootQueryNode.executeWithPagination(specification: Specification<E>, pagination: Pageable): Page<N> {
        limitAndOffset(pagination)
        val items = execute(serializer, user)
        return PageableExecutionUtils.getPage(items, pagination) {
            queryNodeFactory.queryTotalCount(specification)
        }
    }
}

private val Fieldset<*>.defaultSort
    get() = when (this) {
        is EntityFieldset<*> -> schema.defaultSort
        is SingleColumnFieldset<*, *, *> -> singleColumn.parentSchema.defaultSort
        is SingleValueFieldset<*, *> -> null
    }
