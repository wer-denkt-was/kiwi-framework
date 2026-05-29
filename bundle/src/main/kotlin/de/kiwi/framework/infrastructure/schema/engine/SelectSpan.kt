package de.kiwi.framework.infrastructure.schema.engine

import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.schema.*
import jakarta.persistence.Tuple
import jakarta.persistence.TupleElement
import jakarta.persistence.criteria.Path
import jakarta.persistence.criteria.Selection
import jakarta.persistence.metamodel.Attribute
import jakarta.persistence.metamodel.SingularAttribute
import org.springframework.beans.BeanUtils
import org.springframework.util.ClassUtils
import java.lang.reflect.Modifier

private typealias ResultList = List<Tuple>

/**
 * A named span of one or more columns being selected from a row.
 * This span declares a list of selections to be queried,
 * and can extract a result value from a row given as a [Tuple].
 */
internal sealed interface SelectSpan {
    /** If true, this span encapsulates a complete primary key */
    val isPrimary: Boolean

    /** Tuple-elements selected by this span */
    fun selections(): List<Selection<*>>

    /**
     * Initialize this span for extracting the given rows,
     * which will then be passed one-by-one to [extract].
     */
    fun <N> prepare(rows: ResultList, serializer: FieldsetSerializer<N>, user: UserDescriptor?)

    /**
     * Extract and serialize a value representing this span from a queried row.
     * All rows being passed here *must* have previously been included in the list passed to [prepare].
     */
    fun <N> extract(row: Tuple, serializer: FieldsetSerializer<N>, user: UserDescriptor?): N

    /**
     * Represents a span with a single value from a single expression,
     * mapped from a selected expression value using the given mapping function
     */
    class Single<T>(
        override val isPrimary: Boolean,
        val isNullable: Boolean,
        val singleSelection: Selection<out T>,
        val columnType: ColumnType<T & Any, *>,
    ) : SelectSpan {
        // Special case: Compound selections are not allowed within a multiselect query.
        // The workaround here is to split the compound selection apart, and later recombine it.
        override fun selections(): List<Selection<*>> = when {
            singleSelection.isCompoundSelection -> singleSelection.compoundSelectionItems
            else -> listOf(singleSelection)
        }

        override fun <N> prepare(rows: ResultList, serializer: FieldsetSerializer<N>, user: UserDescriptor?) =
            Unit // Do nothing

        override fun <N> extract(row: Tuple, serializer: FieldsetSerializer<N>, user: UserDescriptor?): N = when {
            singleSelection.isCompoundSelection -> extractCompound(row, serializer)
            else -> extractScalar(row, serializer)
        }

        private fun <N> extractScalar(row: Tuple, serializer: FieldsetSerializer<N>): N {
            val rawValue = row.get(singleSelection) ?: emplaceNullEmbeddable()
            val mappedValue = rawValue?.let { columnType.format(it) }
            return serializer.serializeValue(mappedValue)
        }

        private fun <N> extractCompound(row: Tuple, serializer: FieldsetSerializer<N>): N {
            val selectionItems = singleSelection.compoundSelectionItems
            val rawValueItems = selectionItems.map { row.get(it) }

            // ASSUMPTION: Compound selection selects using CriteriaBuilder::array,
            // thus the original Java type T of the compound Selection<T> is inferred to always be Object[]
            @Suppress("UNCHECKED_CAST")
            val rawValue = rawValueItems.toTypedArray() as T

            val mappedValue = rawValue?.let { columnType.format(it) }
            return serializer.serializeValue(mappedValue)
        }

        private fun emplaceNullEmbeddable(): T? {
            // Skip emplacement if column is marked as nullable
            if (isNullable) {
                return null
            }

            // Special case: JPA represents embeddable values as null if all fields of the embeddable instance are null.
            // This behavior is not desired here (except for primary keys),
            // so create a default no-arg instance instead to represent the value.
            val path = singleSelection as? Path<out T> ?: return null
            val attribute = path.model as? SingularAttribute<*, out T> ?: return null
            if (attribute.isId || attribute.persistentAttributeType !== Attribute.PersistentAttributeType.EMBEDDED) {
                return null
            }

            // Only instantiate embeddable type if possible (not abstract/interface, has no-args constructor)
            val emplaceConstructor = attribute.javaType
                .takeUnless { it.isInterface || Modifier.isAbstract(it.modifiers) }
                ?.let { ClassUtils.getConstructorIfAvailable(it) }
                ?: return null

            return BeanUtils.instantiateClass(emplaceConstructor)
        }
    }

    /**
     * Represents an object with named fields, each associated with another span.
     */
    class Nested(val fields: Map<String, SelectSpan>) : SelectSpan {
        override val isPrimary = false

        override fun selections(): List<Selection<*>> = fields.values.flatMap { it.selections() }

        override fun <N> prepare(rows: ResultList, serializer: FieldsetSerializer<N>, user: UserDescriptor?) =
            fields.values.forEach { it.prepare(rows, serializer, user) }

        override fun <N> extract(row: Tuple, serializer: FieldsetSerializer<N>, user: UserDescriptor?): N {
            // If null is detected in a primary field, return row as null
            val selectPrimaryKeys = fields.values.filter { it.isPrimary }
            if (selectPrimaryKeys.any { serializer.isNull(it.extract(row, serializer, user)) }) {
                return serializer.serializeNull
            }

            // Otherwise, extract field values from row and return as map
            return serializer.serializeObject {
                fields.entries.forEach { (name, field) ->
                    set(name, value = field.extract(row, serializer, user))
                }
            }
        }
    }

    /**
     * A special SelectSpan, extending a base span, which additionally executes separate child queries.
     * Used when a to-one parent relation contains its own nested to-many child relations.
     */
    class WithChildQueries(
        val baseSpan: SelectSpan,
        val parentPrimaryKey: Selection<*>,
        val additionalSelections: List<Selection<*>>,
        val childQueries: Map<String, ChildQueryNode>,
        val expressions: ExpressionContext,
    ) : SelectSpan by baseSpan {
        // It is assumed this only needs to be initialized once
        private lateinit var childValuesByParentKeyByFieldName: Map<String, Map<*, List<*>>>

        override fun selections(): List<Selection<*>> =
            listOf(parentPrimaryKey) + baseSpan.selections() + additionalSelections

        override fun <N> prepare(rows: ResultList, serializer: FieldsetSerializer<N>, user: UserDescriptor?) {
            // Query all child rows once before extracting individual results.
            // This ensures each child-query is only executed once, and optimizes the otherwise required 1+N executions!
            childValuesByParentKeyByFieldName = executeChildQueries(rows, serializer, user)
            baseSpan.prepare(rows, serializer, user)
        }

        override fun <N> extract(row: Tuple, serializer: FieldsetSerializer<N>, user: UserDescriptor?): N {
            // Extract the base result from underlying SelectSpan for the current row.
            // If the base span returned a nested result, execute the child queries and append them as fields.
            // If the primary key within the base span's result is null, skip executing child queries.
            val baseResult = baseSpan.extract(row, serializer, user)
            val primaryValue = row[parentPrimaryKey] ?: return baseResult

            // Access-control context for current row
            val context = TupleFieldAccessContext<Any>(row, expressions)

            return serializer.extendObject(baseResult) {
                // Lookup result of child queries for the parent primary-key of this row
                // If child field-access is disallowed, always append an empty list
                val childFields = childQueries.mapValues { (childFieldName, childQuery) ->
                    childValuesByParentKeyByFieldName[childFieldName]
                        ?.takeIf { childQuery.isFieldAccessAllowed(context, user) }
                        ?.let { childValuesByParentKey -> childValuesByParentKey[primaryValue] }
                        ?: emptyList<Nothing>()
                }

                // Add child fields to the base result
                childFields.forEach { (key, childValues) ->
                    set(key, serializer.serializeList(childValues))
                }
            }
        }

        /** Returns (Child fieldName) -> ((Parent key) -> (Child values)) */
        private fun <N> executeChildQueries(
            parentRows: ResultList,
            serializer: FieldsetSerializer<N>,
            user: UserDescriptor?,
        ): Map<String, Map<*, List<N>>> {
            val parentPrimaryKeys = parentRows.mapTo(HashSet()) { row -> row[parentPrimaryKey] }
            return childQueries.mapValues { (_, childQuery) ->
                childQuery.executeChildGroupedByParent(parentPrimaryKeys, serializer, user)
            }
        }
    }

    class WithFieldAccessControl<E : Any>(
        private val baseSpan: SelectSpan,
        private val control: FieldAccessControl<E>,
        private val expressions: ExpressionContext,
        private val requiredSelections: List<Selection<*>>,
    ) : SelectSpan by baseSpan {

        override fun selections(): List<Selection<*>> {
            return baseSpan.selections() + requiredSelections
        }

        override fun <N> extract(row: Tuple, serializer: FieldsetSerializer<N>, user: UserDescriptor?): N {
            // Only pass extraction through to baseSpan if access-control succeeds
            // Otherwise, always return a null value instead of the actual (unauthorized) result
            val context = TupleFieldAccessContext<E>(row, expressions)
            val hasAccess = user?.let { control.checkAccess(context, user) } ?: control.checkAccess(context)
            return if (hasAccess) baseSpan.extract(row, serializer, user)
            else serializer.serializeNull
        }
    }

    class ForSubtype(
        private val delegateSpan: SelectSpan,
    ) : SelectSpan by delegateSpan {

        override fun <N> extract(row: Tuple, serializer: FieldsetSerializer<N>, user: UserDescriptor?): N {
            // If the delegate span returns null or an all-null object, then return null for the whole subtree instead.
            // This usually happens when the row is a different subtype, and thus no values can be selected.
            val result = delegateSpan.extract(row, serializer, user)
            return if (serializer.isNull(result) || serializer.isAllNullsObject(result)) serializer.serializeNull
            else result
        }
    }
}

/**
 * Context keeping track of which columns select which expression, including to-one relation sub-contexts
 */
internal class ExpressionContext {
    private val columnExpressions: MutableMap<String, TupleElement<*>> = HashMap()
    private val nestedContexts: MutableMap<String, ExpressionContext> = HashMap()

    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(column: ColumnField<*, T, *>) = columnExpressions[column.fieldName] as TupleElement<T>?

    operator fun get(toOne: ToOneField<*, *>) = nestedContexts[toOne.fieldName]

    operator fun get(subtype: SubtypeField<*, *, *>) = nestedContexts[subtype.fieldName]

    operator fun <T> set(column: ColumnField<*, T, *>, expression: TupleElement<out T>) {
        columnExpressions[column.fieldName] = expression
    }

    fun nested(toOne: ToOneField<*, *>): ExpressionContext {
        return nestedContexts.computeIfAbsent(toOne.fieldName) { ExpressionContext() }
    }

    fun subtype(subtype: SubtypeField<*, *, *>): ExpressionContext {
        return nestedContexts.computeIfAbsent(subtype.fieldName) { ExpressionContext() }
    }
}

/**
 * [FieldAccessControl.Context] implementation reading values from a [Tuple] row,
 * according to the column-to-expression associated provided by an [ExpressionContext].
 */
internal class TupleFieldAccessContext<E : Any>(
    private val row: Tuple,
    private val expressions: ExpressionContext,
) : FieldAccessControl.Context<E> {

    override fun <T> get(column: ColumnField<E, T, *>): T? {
        val expression: TupleElement<T> = expressions[column] ?: return null
        return row.get(expression)
    }

    override fun <R> getOrNull(toOne: ToOneField<E, R>): FieldAccessControl.Context<R & Any>? {
        val subExpressions = expressions[toOne] ?: return null
        return TupleFieldAccessContext(row, subExpressions)
    }

    override fun <C : E> downcast(subtype: SubtypeField<E, C, C>): FieldAccessControl.Context<C> {
        val subExpressions = expressions[subtype] ?: expressions
        return TupleFieldAccessContext(row, subExpressions)
    }
}
