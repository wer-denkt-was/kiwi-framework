package de.kiwi.framework.infrastructure.schema.engine

import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.schema.FieldAccessControl
import de.kiwi.api.schema.FieldsetSerializer
import jakarta.persistence.Tuple
import jakarta.persistence.TypedQuery
import jakarta.persistence.criteria.Selection
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.support.PageableUtils

/**
 * Parameter name bound to queries specifying the scope of root primary-key values
 */
internal const val ROOT_SCOPE_PARAM = "rootScope"

/**
 * Root of a recursive querying plan.
 * This will execute a `TypedQuery<Tuple>`, and then execute its child queries
 * recursively using the queried values of the primary key as the root scope for all children and grandchildren.
 *
 * Example schema:
 * - One `A` has many `B`
 * - One `A` has many `C`
 * - One `C` has many `X`
 *
 * Results in:
 * - Query `A`, giving primary-key values `A.id` of `(1, 2, 3)`
 * - Query `B`, using `JOIN B.a WHERE a.id IN (1, 2, 3)`
 * - Query `C`, using `JOIN C.a` WHERE a.id IN (1, 2, 3)`
 * - Query `X`, using `JOIN X.c JOIN c.a WHERE a.id IN (1, 2, 3)`
 */
internal class RootQueryNode(
    val tupleQuery: TypedQuery<Tuple>,
    override val primaryKey: Selection<*>?,
    override val selectSpan: SelectSpan,
    override val childQueries: Map<String, ChildQueryNode>,
    override val expressionContext: ExpressionContext,
) : SimpleQueryNode() {

    /**
     * Limit the query results to the given [pagination] window, if paged.
     * The [totalLimit] acts as a hard cap on the overall result set, pages are windows within this capped result set:
     * Pages beyond the total limit are empty, and a page overlapping the total limit is truncated.
     */
    fun limit(pagination: Pageable, totalLimit: Long?) {
        val offset = if (pagination.isPaged) PageableUtils.getOffsetAsInteger(pagination) else 0
        val pageSize = if (pagination.isPaged) pagination.pageSize.toLong() else null
        val remaining = totalLimit?.let { (it - offset).coerceAtLeast(0) }
        val maxResults = listOfNotNull(pageSize, remaining).minOrNull()

        if (offset > 0) tupleQuery.firstResult = offset
        if (maxResults != null) tupleQuery.maxResults = maxResults.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        exhausted = maxResults == 0L
    }

    /** Set if the query is known to return no results, e.g. if the page lies beyond the total limit */
    private var exhausted = false

    fun <N> execute(serializer: FieldsetSerializer<N>, user: UserDescriptor?): List<N> {
        // Execute the parent query: This will retrieve rows containing primary key values, column values,
        // and nested to-one values of parent entities.
        if (exhausted) return emptyList()
        val resultList = tupleQuery.resultListOrNullIfEmpty ?: return emptyList()

        val childValuesByKeyByField = if (primaryKey == null) {
            if (childQueries.isEmpty()) emptyMap<String, Nothing>()
            else throw UnsupportedOperationException("Failed to load to-many child records for entity without primary key")
        } else {
            // Retrieve primary key values of root,
            // and use this range to restrict child queries into the root entity's range.
            val rootPrimaryValues = resultList.mapTo(HashSet()) { row -> row[primaryKey] }

            // Execute the child queries: This will retrieve rows within the range of the root primary keys,
            // child column values, and recursive child to-many values.
            childQueries.mapValues { (_, childNode) ->
                // Only retrieve children that fall within the boundary of the previously retrieved parent rows.
                // This is done by child queries having a predicate "<parentPk> IN (:rootPkValues)".
                // This now retrieves all child rows that fall within the root scope.
                // They will be grouped by the parent foreign key they belong to, in order to associate them with
                // their parent result.
                childNode.executeChildGroupedByParent(rootPrimaryValues, serializer, user)
            }
        }

        // Iterate root tuple list, assemble the final result by looking up associated child query results.
        selectSpan.prepare(resultList, serializer, user)
        return resultList.map { row ->
            assembleResultRecord(row, childValuesByKeyByField, serializer, user)
        }
    }
}

internal interface ChildQueryNode {
    val fieldAccessControl: FieldAccessControl<*>?

    /**
     * @param rootPrimaryValues Range of root-primary-key values to scope child rows
     * @return Map with parent-primary-key values as keys, and groups of associated child values
     */
    fun <N> executeChildGroupedByParent(
        rootPrimaryValues: Set<*>,
        serializer: FieldsetSerializer<N>,
        user: UserDescriptor?,
    ): Map<*, List<N>>
}

@Suppress("UNCHECKED_CAST")
internal fun ChildQueryNode.isFieldAccessAllowed(context: TupleFieldAccessContext<*>, user: UserDescriptor?): Boolean {
    val control = (fieldAccessControl ?: return true) as FieldAccessControl<Any>
    context as FieldAccessControl.Context<Any>
    return user
        ?.let { control.checkAccess(context, user) }
        ?: control.checkAccess(context)
}

/**
 * Assembles a child query and its grandchildren into results,
 * grouped by the foreign-key value of its given parent.
 */
internal class SimpleChildQueryNode(
    val tupleQuery: TypedQuery<Tuple>,
    val parentKeyExpression: Selection<*>,
    override val primaryKey: Selection<*>?,
    override val selectSpan: SelectSpan,
    override val childQueries: Map<String, ChildQueryNode>,
    override val fieldAccessControl: FieldAccessControl<*>?,
    override val expressionContext: ExpressionContext,
) : SimpleQueryNode(), ChildQueryNode {

    override fun <N> executeChildGroupedByParent(
        rootPrimaryValues: Set<*>,
        serializer: FieldsetSerializer<N>,
        user: UserDescriptor?,
    ): Map<*, List<N>> {
        // Restrict the selectivity of this child to fall within the range of the root scope
        tupleQuery.setParameter(ROOT_SCOPE_PARAM, rootPrimaryValues)

        // Execute query and transform tuple rows into results
        val resultList = tupleQuery.resultListOrNullIfEmpty ?: return emptyMap<Any, List<N>>()

        // Execute grandchild queries: This will retrieve child rows of this child within the range of the root entity.
        val grandchildValuesByKeyByField = executeGrandchildQueries(rootPrimaryValues, serializer, user)

        // Convert rows into results
        selectSpan.prepare(resultList, serializer, user)
        return resultList.groupBy(
            keySelector = { row -> row[parentKeyExpression] },
            valueTransform = { row ->
                assembleResultRecord(row, grandchildValuesByKeyByField, serializer, user)
            }
        )
    }

    fun <N> executeGrandchildQueries(
        rootPrimaryValues: Set<*>,
        serializer: FieldsetSerializer<N>,
        user: UserDescriptor?,
    ): Map<String, Map<*, List<N>>> {
        return if (primaryKey == null) emptyMap()
        else childQueries.mapValues { (_, grandchildNode) ->
            grandchildNode.executeChildGroupedByParent(rootPrimaryValues, serializer, user)
        }
    }
}

/**
 * Separately assembles a key- and a value-child as a pair of results
 */
internal class KeyValueChildQueryNode(
    val tupleQuery: TypedQuery<Tuple>,
    val parentKeyExpression: Selection<*>,
    val keyChildNode: SimpleChildQueryNode,
    val valChildNode: SimpleChildQueryNode,
    override val fieldAccessControl: FieldAccessControl<*>?,
) : ChildQueryNode {
    override fun <N> executeChildGroupedByParent(
        rootPrimaryValues: Set<*>,
        serializer: FieldsetSerializer<N>,
        user: UserDescriptor?,
    ): Map<*, List<N>> {
        // Restrict the selectivity of this child to fall within the range of the root of the query
        tupleQuery.setParameter(ROOT_SCOPE_PARAM, rootPrimaryValues)

        // Execute the child query: This will retrieve child rows within the root scope
        val resultList = tupleQuery.resultListOrNullIfEmpty ?: return emptyMap<Nothing, List<N>>()

        // Execute grandchild queries: This will retrieve child rows of this child within the range of the root entity.
        val keyGrandchildValuesByKeyByField = keyChildNode.executeGrandchildQueries(rootPrimaryValues, serializer, user)
        val valGrandchildValuesByKeyByField = valChildNode.executeGrandchildQueries(rootPrimaryValues, serializer, user)

        // Convert rows into results
        keyChildNode.selectSpan.prepare(resultList, serializer, user)
        valChildNode.selectSpan.prepare(resultList, serializer, user)
        return resultList.groupBy(
            keySelector = { row -> row[parentKeyExpression] },
            valueTransform = { row ->
                val key = keyChildNode.assembleResultRecord(row, keyGrandchildValuesByKeyByField, serializer, user)
                val value = valChildNode.assembleResultRecord(row, valGrandchildValuesByKeyByField, serializer, user)
                serializer.serializePair(key, value)
            }
        )
    }
}

internal abstract class SimpleQueryNode {
    abstract val primaryKey: Selection<*>?
    abstract val selectSpan: SelectSpan
    abstract val childQueries: Map<String, ChildQueryNode>
    abstract val expressionContext: ExpressionContext

    fun <N> assembleResultRecord(
        row: Tuple,
        childValuesByParentKeyByField: Map<String, Map<*, List<*>>>,
        serializer: FieldsetSerializer<N>,
        user: UserDescriptor?,
    ): N {
        // Extract the select-span values from the row, shortcut if the result is null
        val result = selectSpan.extract(row, serializer, user)
            .takeUnless { serializer.isNull(it) }
            ?: return serializer.serializeNull

        // If no primary key exists for this node, no children can be queried
        if (primaryKey == null) {
            return result
        }

        val fieldAccessControlContext = TupleFieldAccessContext<Any>(row, expressionContext)

        // If select-span only extracted a single value instead of an association,
        // extension is skipped and this atomic result is returned immediately, since it can not have child fields
        return serializer.extendObject(result) {
            // Extract the primary key's value of this parent row,
            // and use these keys to look up which of the queried children belong to this parent
            val primaryValue = row[primaryKey]
            val childFieldValues = childQueries.mapValues { (fieldName, childQuery) ->
                // Check if access to child field is allowed using child-query field access control,
                // otherwise discard child values and always add an empty list
                if (childQuery.isFieldAccessAllowed(fieldAccessControlContext, user)) {
                    val childValuesByKey = childValuesByParentKeyByField[fieldName] ?: emptyMap<Any, List<*>>()
                    childValuesByKey[primaryValue] ?: emptyList<Nothing>()
                } else {
                    emptyList<Nothing>()
                }
            }

            // Join child values and column values to create one result record
            childFieldValues.forEach { (key, childValues) ->
                set(key, serializer.serializeList(childValues))
            }
        }
    }
}

/**
 * Helper for getting a TypedQuery resultList, or `null` if the returned list is empty
 */
private val TypedQuery<Tuple>.resultListOrNullIfEmpty: List<Tuple>?
    get() = resultList?.takeIf { it.isNotEmpty() }
