package de.kiwi.framework.infrastructure.schema.engine

import de.kiwi.api.schema.*
import de.kiwi.framework.infrastructure.schema.JpaQuerySortBuilder
import jakarta.persistence.EntityManager
import jakarta.persistence.criteria.Expression
import jakarta.persistence.criteria.JoinType
import jakarta.persistence.criteria.Predicate
import jakarta.persistence.criteria.Selection
import jakarta.persistence.metamodel.MapAttribute
import jakarta.persistence.metamodel.PluralAttribute
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.jpa.repository.query.QueryUtils

/**
 * Factory creating [RootQueryNode] and [ChildQueryNode] instances
 * for querying [EntitySchema] data based on a [Fieldset].
 */
internal class QueryNodeFactory<E : Any>(
    private val rootFieldset: Fieldset<E>,
    private val entityManager: EntityManager,
    private val selectSpanFactory: SelectSpanFactory,
) {
    private val builder = entityManager.criteriaBuilder
    private val metamodel = entityManager.metamodel

    private val rootSchema = rootFieldset.entitySchema
        ?: throw IllegalArgumentException("Can't create query using $rootFieldset")

    fun createRootQuery(
        specification: Specification<E>,
        sort: Sort,
    ): RootQueryNode {
        val query = builder.createTupleQuery()
        val entityRoot = query.from(rootSchema.entityClass)

        val expressions = ExpressionContext()
        val selectSpan = selectSpanFactory.fieldsetSelectSpan(entityRoot, rootFieldset, expressions)
        val selectPrimaryKeys = rootSchema.selectPrimaryKeys(builder, entityRoot) { i -> "root_$i" }
        val selectAdditional = selectSpanFactory
            .additionalToManyFieldsAccessControlSelections(entityRoot, rootFieldset, expressions)

        val selections = selectPrimaryKeys + selectSpan.selections() + selectAdditional

        // SELECT <...pk>, <...column>, (...<nested.column>) FROM <entity> WHERE <filter>
        query.multiselect(selections.distinct())
        specification.toPredicate(entityRoot, query, builder)
            ?.let { query.where(it) }

        if (sort.isSorted) {
            // Try to parse
            val sortBuilder = JpaQuerySortBuilder(entityRoot, rootSchema, builder)
            val orderBy = sortBuilder
                .createOrderBy(sort)
                .takeIf { it.isNotEmpty() }
                ?: QueryUtils.toOrders(sort, entityRoot, builder)

            query.orderBy(orderBy)
        }

        // Create child query nodes for to-many relations
        val childQueries = createChildQueries(
            parentSourcePath = QueryJoinPath.RootPath(),
            parentFieldset = rootFieldset,
        )

        // Create root query node
        return RootQueryNode(
            tupleQuery = entityManager.createQuery(query),
            primaryKey = selectPrimaryKeys.singleOrNull(),
            selectSpan = selectSpan,
            childQueries = childQueries,
            expressionContext = expressions,
        )
    }

    fun queryTotalCount(specification: Specification<E>): Long {
        val countQuery = builder.createQuery(Long::class.java)
        val fromRoot = countQuery.from(rootSchema.entityClass)
        val predicate = specification.toPredicate(fromRoot, countQuery, builder)
        val selectCount = if (countQuery.isDistinct) builder::countDistinct else builder::count

        // select count(*) from <entity> where <specification>
        countQuery.select(selectCount(fromRoot))
        predicate?.let { countQuery.where(it) }
        countQuery.orderBy(emptyList()) // Remove sorting, irrelevant for count(*) query

        val totals = entityManager.createQuery(countQuery).resultList
        return totals.sum()
    }

    fun <P : Any> createChildQueries(
        parentSourcePath: QueryJoinPath<E, P>,
        parentFieldset: Fieldset<P>,
    ): Map<String, ChildQueryNode> {
        // Only entities can have to-many relations
        if (parentFieldset !is EntityFieldset<P>) {
            return emptyMap()
        }
        val parentSchema = parentFieldset.schema

        @Suppress("UNCHECKED_CAST")
        val toManyChildren = parentFieldset.toMany.entries.associate { (toMany, childFieldset) ->
            // Cast to-many relation to be used without generic type restrictions
            toMany.fieldName to createToManyQuery(
                parentSourcePath = parentSourcePath,
                parentFieldset = parentFieldset,
                toMany = toMany as ToManyField<P, out Collection<Any>, Any, *>,
                childFieldset = childFieldset as Fieldset<Any>,
                accessControl = parentSchema.fieldAccessControl(toMany),
            )
        }

        @Suppress("UNCHECKED_CAST")
        val toManyMapChildren = parentFieldset.toManyMap.entries.associate { (toManyMap, childFieldsets) ->
            // Cast to-many-map relation to be used without generic type restrictions
            toManyMap.fieldName to createToManyMapQuery(
                parentSourcePath = parentSourcePath,
                parentFieldset = parentFieldset,
                toManyMap = toManyMap as ToManyMapField<P, Any, Any, *, *>,
                childFieldsets = childFieldsets as FieldsetPair<Any, Any>,
                accessControl = parentSchema.fieldAccessControl(toManyMap),
            )
        }

        return toManyChildren + toManyMapChildren
    }

    private fun <P : Any, C : Any> createToManyQuery(
        parentSourcePath: QueryJoinPath<E, P>,
        parentFieldset: EntityFieldset<P>,
        toMany: ToManyField<P, out Collection<C>, C, *>,
        childFieldset: Fieldset<C>,
        accessControl: FieldAccessControl<P>?,
    ): SimpleChildQueryNode {
        val query = builder.createTupleQuery()

        @Suppress("UNCHECKED_CAST")
        val childAttribute = metamodel
            .entity(toMany.parentSchema.entityClass)
            .getAttribute(toMany.attribute.name)
                as PluralAttribute<in P, *, C>

        // Append list join to path
        val childSourcePath = QueryJoinPath.CollectionPath<E, P, C>(
            parent = parentSourcePath,
            child = childAttribute,
            joinType = JoinType.INNER
        )

        // Join path: Root<E> to Join<E, [...]> to [...] to From<[...], P> to PluralJoin<P, C>
        val entityRoot = query.from(rootSchema.entityClass)
        val childJoin = childSourcePath.joinFrom(entityRoot)
        val parentJoin = childJoin.parent

        // Select child primary keys for lookup during execution of grandchildren
        val selectPrimaryKeys = childFieldset.entitySchema
            ?.let { schema -> schema.selectPrimaryKeys(builder, childJoin) { i -> "child_$i" } }
            ?: emptyList()

        // Select parent primary keys as foreign keys for grouping during execution
        val selectParentKeys = parentFieldset.schema.selectPrimaryKeys(builder, parentJoin) { i -> "parent_$i" }

        // Create selections for child fieldset
        val expressions = ExpressionContext()
        val selectSpan = selectSpanFactory.fieldsetSelectSpan(childJoin, childFieldset, expressions)
        val selectColumns = selectSpan.selections()
        val selectAdditional =
            selectSpanFactory.additionalToManyFieldsAccessControlSelections(childJoin, childFieldset, expressions)

        // SELECT <...primaryKey>, <...parentForeignKey>, <...column>
        val selection = selectPrimaryKeys + selectParentKeys + selectColumns + selectAdditional
        query.multiselect(selection.distinct())

        // Only query child rows within the scope of the parent row, determined by the parent's primary key.
        // WHERE <fk> IN (:parentPkValues)
        val selectRootKeys = rootSchema.selectPrimaryKeys(builder, entityRoot) { i -> "root_$i" }
        query.where(rootKeyInScope(selectRootKeys))

        // If present, order query by index-expression to ensure children are ordered correctly
        childSourcePath.indexExpression(childJoin)?.let { indexExpression ->
            query.orderBy(builder.asc(indexExpression))
        }

        // If this child is an entity fieldset, create queries for grandchildren
        val grandchildQueries = if (childFieldset is EntityFieldset<C>) {
            createChildQueries(childSourcePath, childFieldset)
        } else emptyMap()

        return SimpleChildQueryNode(
            tupleQuery = entityManager.createQuery(query),
            primaryKey = selectPrimaryKeys.firstOrNull(),
            parentKeyExpression = selectParentKeys.single(),
            selectSpan = selectSpan,
            childQueries = grandchildQueries,
            fieldAccessControl = accessControl,
            expressionContext = expressions,
        )
    }

    private fun <P : Any, K : Any, V : Any> createToManyMapQuery(
        parentSourcePath: QueryJoinPath<E, P>,
        parentFieldset: EntityFieldset<P>,
        toManyMap: ToManyMapField<P, K, V, *, *>,
        childFieldsets: FieldsetPair<K, V>,
        accessControl: FieldAccessControl<P>?,
    ): KeyValueChildQueryNode {
        val (keyFieldset, valueFieldset) = childFieldsets
        val query = builder.createTupleQuery()

        @Suppress("UNCHECKED_CAST")
        val childMapAttribute = metamodel
            .entity(toManyMap.parentSchema.entityClass)
            .getMap(toManyMap.attribute.name)
                as MapAttribute<in P, K, V>

        // Append map join to path
        val childSourcePath = QueryJoinPath.MapValuePath(parentSourcePath, childMapAttribute)

        // Join path: Root<E> to Join<E, [...]> to [...] to From<[...], P> to MapJoin<P, K, V>
        val entityRoot = query.from(rootSchema.entityClass)
        val childJoin = childSourcePath.joinFrom(entityRoot)
        val parentJoin = childJoin.parent

        // Select child key- and value primary keys for lookup during execution of grandchildren
        val selectKeyPrimaryKeys = keyFieldset.entitySchema
            ?.let { keySchema -> keySchema.selectPrimaryKeys(builder, childJoin.key()) { i -> "child_key_$i" } }
            ?: emptyList()
        val selectValuePrimaryKeys = valueFieldset.entitySchema
            ?.let { valueSchema -> valueSchema.selectPrimaryKeys(builder, childJoin.value()) { i -> "child_value_$i" } }
            ?: emptyList()

        // Select parent primary keys as foreign keys for lookup during execution
        val selectForeignKeys = parentFieldset.schema.selectPrimaryKeys(builder, parentJoin) { i -> "parent_$i" }

        // Create selections for child fieldset
        val expressions = ExpressionContext()
        val selectKeySpan = selectSpanFactory.fieldsetSelectSpan(childJoin.key(), keyFieldset, expressions)
        val selectValueSpan = selectSpanFactory.fieldsetSelectSpan(childJoin.value(), valueFieldset, expressions)
        val selectColumns = selectKeySpan.selections() + selectValueSpan.selections()

        val additionalToManyKeys = selectSpanFactory
            .additionalToManyFieldsAccessControlSelections(childJoin.key(), keyFieldset, expressions)
        val additionalToManyValues = selectSpanFactory
            .additionalToManyFieldsAccessControlSelections(childJoin.value(), valueFieldset, expressions)
        val selectAdditional = additionalToManyKeys + additionalToManyValues

        // SELECT <...keyPK>, <...valuePK>, <...parentFK>, <...keyColumn>, <...valueColumn>
        val selection =
            selectKeyPrimaryKeys + selectValuePrimaryKeys + selectForeignKeys + selectColumns + selectAdditional

        query.multiselect(selection.distinct())

        // Only query child rows within the scope of the parent row, determined by the parent's primary key.
        // The primary key column's field name is used as a parameter name, see FieldsetQueryNode.execute()
        // WHERE <fk> IN (:parentPkValues)
        val selectRootKeys = rootSchema.selectPrimaryKeys(builder, entityRoot) { i -> "root_$i" }
        query.where(rootKeyInScope(selectRootKeys))

        // If this child is an entity fieldset, create queries for grandchildren
        val valueGrandchildQueries = if (valueFieldset is EntityFieldset<V>) {
            createChildQueries(childSourcePath, valueFieldset)
        } else emptyMap()

        val keyGrandchildQueries = if (keyFieldset is EntityFieldset<K>) {
            val keySourcePath = QueryJoinPath.MapKeyPath(childSourcePath)
            createChildQueries(keySourcePath, keyFieldset)
        } else emptyMap()

        val tupleQuery = entityManager.createQuery(query)
        val parentKey = selectForeignKeys.single()
        return KeyValueChildQueryNode(
            tupleQuery = tupleQuery,
            parentKeyExpression = parentKey,
            keyChildNode = SimpleChildQueryNode(
                tupleQuery = tupleQuery,
                parentKeyExpression = parentKey,
                primaryKey = selectKeyPrimaryKeys.firstOrNull(),
                selectSpan = selectKeySpan,
                childQueries = keyGrandchildQueries,
                fieldAccessControl = accessControl,
                expressionContext = expressions,
            ),
            valChildNode = SimpleChildQueryNode(
                tupleQuery = tupleQuery,
                parentKeyExpression = parentKey,
                primaryKey = selectValuePrimaryKeys.firstOrNull(),
                selectSpan = selectValueSpan,
                childQueries = valueGrandchildQueries,
                fieldAccessControl = accessControl,
                expressionContext = expressions,
            ),
            fieldAccessControl = accessControl,
        )
    }

    private fun rootKeyInScope(rootPrimaryKeys: List<Selection<*>>): Predicate {
        val rootId = rootPrimaryKeys.singleOrNull() as? Expression<*>
            ?: throw IllegalStateException("Child query requires single root primary-key expression")

        return rootId.`in`(builder.parameter(Collection::class.java, ROOT_SCOPE_PARAM))
    }
}

/**
 * Get the EntitySchema associated with the given fieldset,
 * or null if the fieldset represents a single value.
 */
private val <E : Any> Fieldset<E>.entitySchema: EntitySchema<E>?
    get() = when (this) {
        is EntityFieldset<E> -> schema
        is SingleColumnFieldset<E, *, *> -> singleColumn.parentSchema
        is SingleValueFieldset<E, *> -> null
    }
