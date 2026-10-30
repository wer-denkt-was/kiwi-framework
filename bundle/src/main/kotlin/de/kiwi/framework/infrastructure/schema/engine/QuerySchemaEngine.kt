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

    /**
     * Query all items matching the given [specification], limited by the given [pagination] if paged.
     * The [totalLimit] is a hard cap on all items, i.e. pages are windows within the first [totalLimit] items.
     */
    fun findItems(
        specification: Specification<E>,
        pagination: Pageable,
        sort: Sort,
        totalLimit: Long?,
    ): List<N> {
        // Choose sort by priority: Sort parameter, Pagination sort property, EntitySchema default sort, or unsorted
        val actualSort = sort.takeIf { it.isSorted }
            ?: pagination.sort.takeIf { it.isSorted }
            ?: rootFieldset.defaultSort?.takeIf { it.isSorted }
            ?: Sort.unsorted()

        // Create root query node for sort/specification/limit
        val queryNode = queryNodeFactory.createRootQuery(specification, actualSort)

        // Execute root query, either paginated or with all items, capped by the total limit
        queryNode.limit(pagination, totalLimit)
        return queryNode.execute(serializer, user)
    }

    /**
     * Count all items matching the given [specification], capped by the given [totalLimit].
     */
    fun queryTotalCount(specification: Specification<E>, totalLimit: Long? = null): Long {
        val totalCount = queryNodeFactory.queryTotalCount(specification)
        return if (totalLimit == null) totalCount else minOf(totalCount, totalLimit.coerceAtLeast(0))
    }
}

/**
 * Query a [Page] of items matching the given [specification].
 * Defined as an extension, since Spring Data [Page] requires a non-nullable item type [N].
 */
internal fun <E : Any, N : Any> QuerySchemaEngine<E, N>.findPage(
    specification: Specification<E>,
    pagination: Pageable,
    sort: Sort,
    totalLimit: Long?,
): Page<N> {
    val items = findItems(specification, pagination, sort, totalLimit)
    return if (pagination.isUnpaged) PageImpl(items)
    else PageableExecutionUtils.getPage(items, pagination) { queryTotalCount(specification, totalLimit) }
}

private val Fieldset<*>.defaultSort
    get() = when (this) {
        is EntityFieldset<*> -> schema.defaultSort
        is SingleColumnFieldset<*, *, *> -> singleColumn.parentSchema.defaultSort
        is SingleValueFieldset<*, *> -> null
    }
