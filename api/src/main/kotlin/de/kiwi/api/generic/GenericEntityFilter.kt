package de.kiwi.api.generic

import de.kiwi.api.domain.BaseEntity
import de.kiwi.api.domain.BaseEntity_
import de.kiwi.api.domain.EntityId
import de.kiwi.api.domain.EntityId_
import de.kiwi.api.schema.ColumnFilterConversion
import de.kiwi.api.schema.ColumnFilterOperator
import de.kiwi.api.schema.ColumnField
import de.kiwi.api.util.CriteriaDsl
import de.kiwi.api.util.DslSpecification
import de.kiwi.api.util.reusableJoin
import de.kiwi.api.util.where
import jakarta.persistence.criteria.*
import jakarta.persistence.metamodel.*
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.jpa.repository.query.EscapeCharacter
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import de.kiwi.api.util.where as specification

/**
 * Overridable type representing custom filter attributes defined for an entity.
 * Filter attributes are defined using the `by filter(...)` delegate, building a filter specification.
 * See [GenericEntityFilter.Companion] for available filter specification operators.
 *
 * @param E Entity type the filter specification applies to.
 */
abstract class GenericEntityFilter<E : Any> : Specification<E> {
    private val predicates = LinkedHashMap<String, ValueAndSpecification<E, *>>()

    /**
     * Return the attributes and values thereof set in this filter.
     */
    fun filterValues(): Map<String, *> {
        return predicates.entries.associate { (name, predicate) -> name to predicate.value }
    }

    /**
     * Set a [de.kiwi.api.schema.ColumnField] filter property with a specific [de.kiwi.api.schema.ColumnFilterOperator] to a given parameter value-list.
     * The key of this property will be `"{column.fieldName}:{operator.symbol}"`.
     */
    fun <T> setColumnFilter(
        values: List<String>,
        column: ColumnField<out E, T, *>,
        operator: ColumnFilterOperator,
        conversion: ColumnFilterConversion,
    ) {
        val key = "${column.fieldName}:${operator.symbol}"
        @Suppress("UNCHECKED_CAST") val invariantColumn = column as ColumnField<E, T, *>
        val specification = where<E> { root ->
            val expression = invariantColumn.selectFrom(root, this) as? Expression<T>
                ?: throw AssertionError("Invalid filter column expression: $column")

            operator.filter(values, expression, invariantColumn, this, conversion)
        }

        predicates[key] = ValueAndSpecification(values, specification)
    }

    /**
     * Create a JPA [Predicate] for the entity type [E] of this filter,
     * containing a conjunction of all assigned filter attributes and filter columns.
     * @see Specification.toPredicate
     */
    override fun toPredicate(
        root: Root<E>,
        query: CriteriaQuery<*>?,
        criteriaBuilder: CriteriaBuilder,
    ): Predicate? {
        return Specification
            .allOf(predicates.values)
            .toPredicate(root, query, criteriaBuilder)
    }

    /**
     * Define a filter attribute property for this filter.
     * See [Companion] object for available filter operators.
     * ```kt
     * val myAttribute by filter( ... )
     * ```
     */
    protected fun <T> filter(createFilter: CreateFilter<E, T>) = FilterProperty(createFilter)

    protected inner class FilterProperty<T>(
        private val createFilter: CreateFilter<E, T>,
    ) : ReadWriteProperty<GenericEntityFilter<E>, T?> {

        private fun toSpecification(value: T): Specification<E> {
            return createFilter(value)
        }

        @Suppress("UNCHECKED_CAST")
        override fun getValue(thisRef: GenericEntityFilter<E>, property: KProperty<*>): T? {
            return predicates[property.name]?.let { it.value as T }
        }

        override fun setValue(thisRef: GenericEntityFilter<E>, property: KProperty<*>, value: T?) {
            if (value == null) predicates.remove(property.name)
            else predicates[property.name] = ValueAndSpecification(value, toSpecification(value))
        }
    }

    protected fun interface CreateFilter<E : Any, T> {
        fun filter(value: T): Specification<E>

        infix fun and(other: CreateFilter<E, T>) = CreateFilter<E, T> { this(it).and(other(it)) }
        infix fun or(other: CreateFilter<E, T>) = CreateFilter<E, T> { this(it).or(other(it)) }
        operator fun not() = CreateFilter<E, T> { Specification.not(this(it)) }
        operator fun invoke(value: T) = filter(value)
    }

    protected fun interface CreateExpression<E : Any, T> {
        fun CriteriaDsl.expression(root: Root<E>): Expression<T>

        // Note: This is required for the interface to be used in contexts with multiple receivers
        operator fun invoke(builder: CriteriaDsl, root: Root<E>) = builder.expression(root)

        fun isNull() = specification { root -> expression(root).isNull }
        fun isNotNull() = specification { root -> expression(root).isNotNull }
    }

    private class ValueAndSpecification<E, T>(
        val value: T,
        val specification: Specification<E>,
    ) : Specification<E> by specification

    protected interface LikeOperator {
        fun likePredicate(
            expression: Expression<String>,
            pattern: String,
            escapeChar: Char,
            builder: CriteriaBuilder,
        ): Predicate

        data object Verbatim : LikeOperator {
            override fun likePredicate(
                expression: Expression<String>,
                pattern: String,
                escapeChar: Char,
                builder: CriteriaBuilder,
            ): Predicate =
                builder.like(expression, pattern, escapeChar)
        }

        data object IgnoreCase : LikeOperator {
            override fun likePredicate(
                expression: Expression<String>,
                pattern: String,
                escapeChar: Char,
                builder: CriteriaBuilder,
            ): Predicate =
                builder.like(builder.lower(expression), pattern.lowercase(), escapeChar)
        }
    }

    /**
     * [GenericEntityFilter] companion object providing functions for composing filter operations
     */
    protected companion object {
        //region Expressions

        /**
         * Use a [SingularAttribute] to define a [CreateExpression] for building a filter attribute.
         */
        fun <E : Any, T> attribute(
            attribute: SingularAttribute<in E, T>,
        ) = CreateExpression<E, T> { it.get(attribute) }

        /**
         * Use a chain of [SingularAttribute]s to define a [CreateExpression] for building a filter attribute.
         */
        fun <E : Any, Z0, T> attribute(
            attribute0: SingularAttribute<in E, Z0>,
            attribute1: SingularAttribute<in Z0, T>,
        ) = CreateExpression<E, T> { it.getReusable(attribute0).get(attribute1) }

        /**
         * Use a chain of [SingularAttribute]s to define a [CreateExpression] for building a filter attribute.
         */
        fun <E : Any, Z0, Z1, T> attribute(
            attribute0: SingularAttribute<E, Z0>,
            attribute1: SingularAttribute<Z0, Z1>,
            attribute2: SingularAttribute<Z1, T>,
        ) = CreateExpression<E, T> { it.getReusable(attribute0).getReusable(attribute1).get(attribute2) }

        /**
         * Use a chain of [SingularAttribute]s to define a [CreateExpression] for building a filter attribute.
         */
        fun <E : Any, Z0, Z1, Z2, T> attribute(
            attribute0: SingularAttribute<E, Z0>,
            attribute1: SingularAttribute<Z0, Z1>,
            attribute2: SingularAttribute<Z1, Z2>,
            attribute3: SingularAttribute<Z2, T>,
        ) = CreateExpression<E, T> {
            it.getReusable(attribute0).getReusable(attribute1).getReusable(attribute2).get(attribute3)
        }

        /**
         * Use a chain of [SingularAttribute]s to define a [CreateExpression] for building a filter attribute.
         */
        fun <E : Any, Z0, Z1, Z2, Z3, T> attribute(
            attribute0: SingularAttribute<E, Z0>,
            attribute1: SingularAttribute<Z0, Z1>,
            attribute2: SingularAttribute<Z1, Z2>,
            attribute3: SingularAttribute<Z2, Z3>,
            attribute4: SingularAttribute<Z3, T>,
        ) = CreateExpression<E, T> {
            it.getReusable(attribute0).getReusable(attribute1).getReusable(attribute2).getReusable(attribute3)
                .get(attribute4)
        }

        /**
         * Cast the [BaseEntity.id] attribute for a specific [BaseEntity] ID type.
         */
        @Suppress("UNCHECKED_CAST")
        fun <E : BaseEntity<ID>, ID : EntityId> entityIdAttribute() = BaseEntity_.id as SingularAttribute<E, ID>

        private fun <E, R> Path<E>.getReusable(attr: SingularAttribute<*, R>) =
            reusableJoin<E, R>(attr.name, JoinType.INNER)

        //endregion
        //region Conversion

        /** Parse a given filter value into a different type before delegating it to a downstream filter operation */
        fun <E : Any, T, V> parseValue(parse: (T) -> V?, filter: CreateFilter<E, V>) =
            CreateFilter<E, T> { value -> parse(value)?.let { filter(it) } ?: Specification.unrestricted() }

        /** Parse a given filter string into an integer before delegating it to a downstream filter operation */
        fun <E : Any> parseInt(filter: CreateFilter<E, Int>) =
            parseValue<E, String, Int>({ it.toIntOrNull() }, filter)

        /** Parse a given filter string into a long before delegating it to a downstream filter operation */
        fun <E : Any> parseLong(filter: CreateFilter<E, Long>) =
            parseValue<E, String, Long>({ it.toLongOrNull() }, filter)

        /** Parse a given filter string into a double before delegating it to a downstream filter operation */
        fun <E : Any> parseDouble(filter: CreateFilter<E, Double>) =
            parseValue<E, String, Double>({ it.toDoubleOrNull() }, filter)

        //endregion
        //region Filter Predicates

        /**
         * Use a function to define a JPA [Predicate] for a given filter value,
         * using a [Specification]-like function.
         */
        fun <E : Any, T> where(condition: CriteriaDsl.(Root<E>, value: T) -> Predicate) = CreateFilter<E, T> { value ->
            specification { root -> condition(root, value) }
        }

        /**
         * Filter this attribute by equality to the entity's [EntityId].
         */
        fun <E : BaseEntity<ID>, ID : EntityId> entityId(): CreateFilter<E, Set<ID>> =
            where { root, ids ->
                val idPath = root[BaseEntity_.id][EntityId_.idValue]
                val idValues = ids.map { it.value }
                idPath.`in`(idValues)
            }

        /**
         * Filter this attribute by equality to a given expression.
         */
        fun <E : Any, T> equalTo(expr: CreateExpression<E, T>) =
            binaryOperator(expr, CriteriaBuilder::equal)

        /**
         * Filter this attribute by inequality to a given expression.
         */
        fun <E : Any, T> notEqualTo(expr: CreateExpression<E, T>) = not(equalTo(expr))

        /**
         * Filter this attribute by a greater-than comparison with a given expression.
         */
        fun <E : Any, T : Comparable<T>> greaterThan(expr: CreateExpression<E, T>): CreateFilter<E, T> =
            binaryOperator(expr, CriteriaBuilder::greaterThan)

        /**
         * Filter this attribute by a less-than comparison with a given expression.
         */
        fun <E : Any, T : Comparable<T>> lessThan(expr: CreateExpression<E, T>): CreateFilter<E, T> =
            binaryOperator(expr, CriteriaBuilder::lessThan)

        /**
         * Filter this attribute by a greater-or-equal comparison with a given expression.
         */
        fun <E : Any, T : Comparable<T>> greaterOrEqual(expr: CreateExpression<E, T>): CreateFilter<E, T> =
            binaryOperator(expr, CriteriaBuilder::greaterThanOrEqualTo)

        /**
         * Filter this attribute by a less-or-equal comparison with a given expression.
         */
        fun <E : Any, T : Comparable<T>> lessOrEqual(expr: CreateExpression<E, T>): CreateFilter<E, T> =
            binaryOperator(expr, CriteriaBuilder::lessThanOrEqualTo)

        private inline fun <E : Any, T> binaryOperator(
            expr: CreateExpression<E, T>,
            crossinline operator: CriteriaBuilder.(Expression<T>, T) -> Predicate,
        ) = where<E, T> { root, value -> operator(expr(this, root), value) }

        /**
         * Filter this attribute, containing a minimum and a maximum value, by a between comparison with a given expression.
         */
        fun <E : Any, T : Comparable<T>> between(expr: CreateExpression<E, T>) =
            where<E, Pair<T, T>> { root, bounds -> between(expr(this, root), bounds.first, bounds.second) }

        /**
         * Filter this attribute, containing possible values, by containing the value of a given expression.
         */
        fun <E : Any, T> inList(expr: CreateExpression<E, T>) =
            where<E, Collection<T>> { root, values -> expr(this, root).`in`(values) }

        /**
         * Filter this attribute using a "like" operator against a given expression.
         */
        fun <E : Any> like(
            expr: CreateExpression<E, String>,
            likeOperator: LikeOperator = LikeOperator.Verbatim,
            escapeCharacter: EscapeCharacter = EscapeCharacter.DEFAULT,
            escapeValue: Boolean = true,
            valueToPattern: (String) -> String = { it },
        ) = CreateFilter<E, String> { value ->
            val escaped = if (escapeValue) escapeCharacter.escape(value) ?: "" else value
            val pattern = valueToPattern(escaped)
            specification { root ->
                likeOperator.likePredicate(
                    expression = expr(this, root),
                    pattern = pattern,
                    escapeChar = escapeCharacter.escapeCharacter,
                    builder = this,
                )
            }
        }

        /**
         * Filter this attribute using a case-insensitive "like" operator against a given expression.
         */
        fun <E : Any> likeIgnoreCase(
            expr: CreateExpression<E, String>,
            escapeCharacter: EscapeCharacter = EscapeCharacter.DEFAULT,
            escapeValue: Boolean = true,
            valueToPattern: (String) -> String = { it },
        ): CreateFilter<E, String> =
            like(
                expr = expr,
                likeOperator = LikeOperator.IgnoreCase,
                escapeCharacter = escapeCharacter,
                valueToPattern = valueToPattern,
                escapeValue = escapeValue,
            )

        fun <E : Any> equalIgnoreCase(
            toLowercase: (String) -> String = { it.lowercase() },
            expr: CreateExpression<E, String>,
        ): CreateFilter<E, String> =
            where { root, value -> equal(lower(expr(this, root)), toLowercase(value)) }

        /**
         * Create a filter attribute which, if and only if true, adds the given [DslSpecification] to the filter.
         */
        fun <E : Any> ifTrue(then: DslSpecification<E>) = ifTrue(specification(then))

        /**
         * Create a filter attribute which, if and only if true, adds the given [Specification] to the filter.
         */
        fun <E : Any> ifTrue(then: Specification<E>) = CreateFilter<E, Boolean> { value ->
            if (value) then else Specification.unrestricted()
        }

        //endregion
        //region Filter Composition

        /**
         * Logically invert a given filter.
         */
        fun <E : Any, T> not(other: CreateFilter<E, T>) =
            CreateFilter<E, T> { value -> Specification.not(other.invoke(value)) }

        /**
         * Logically "or" the given filters in a disjunction.
         */
        fun <E : Any, T> or(vararg others: CreateFilter<E, T>) =
            compose(others.toList()) { Specification.anyOf(it) }

        /**
         * Logically "and" the given filters in a conjunction.
         */
        fun <E : Any, T> and(vararg others: CreateFilter<E, T>) =
            compose(others.toList()) { Specification.allOf(it) }

        private inline fun <E : Any, T> compose(
            others: List<CreateFilter<E, T>>,
            crossinline combinator: (List<Specification<E>>) -> Specification<E>,
        ) = CreateFilter<E, T> { value ->
            combinator(others.map { it.invoke(value) })
        }

        /**
         * Evaluate a requirement before applying this filter attribute.
         * This filter attribute is only added to the filter only when the requirement returns true for the given value.
         */
        fun <E : Any, T> onlyWhen(requirement: (T) -> Boolean, then: CreateFilter<E, T>) =
            CreateFilter<E, T> { value ->
                if (requirement(value)) then(value)
                else Specification.unrestricted() // Always true (no predicate)
            }

        /**
         * Evaluate a precondition before applying this filter attribute.
         * This filter attribute will never return any results unless the precondition returns true for the given value.
         */
        fun <E : Any, T> nothingUnless(precondition: (T) -> Boolean, then: CreateFilter<E, T>) =
            CreateFilter<E, T> { value ->
                if (precondition(value)) then(value)
                else specification { disjunction() } // Always false (empty disjunction)
            }

        //endregion
        //region Subquery Predicates

        fun <E : Any, T> existsSubquery(existsFilter: CreateExistsFilter<E, T>) =
            CreateFilter<E, T> { value ->
                specification { parentRoot ->
                    val subquery = query.subquery(Int::class.java)
                    val correlatedRoot = subquery.correlate(parentRoot)

                    subquery.select(literal(1))
                    subquery.where(existsFilter(subquery, correlatedRoot, value))

                    exists(subquery)
                }
            }

        fun <E : Any, C : Any, T> hasChild(
            childAttribute: PluralAttribute<E, *, C>,
            childFilter: CreateChildFilter<E, C, T>,
        ) = existsSubquery<E, T> { _, parentRoot, value ->
            val childJoin = when (childAttribute) {
                is SetAttribute<E, C> -> parentRoot.join(childAttribute)
                is ListAttribute<E, C> -> parentRoot.join(childAttribute)
                is CollectionAttribute<E, C> -> parentRoot.join(childAttribute)
                else -> return@existsSubquery null //TODO: This should never happen, but make some assertion anyways?
            }
            childFilter(childJoin, value)
        }

        fun <E : Any, C : BaseEntity<ID>, ID : EntityId> childEntityId(): CreateChildFilter<E, C, Set<ID>> =
            { childJoin, ids ->
                val idValues = ids.map { it.value }
                val childIdExpression = childJoin[BaseEntity_.id]
                childIdExpression[EntityId_.idValue].`in`(idValues)
            }

        //endregion
    }
}

private typealias CreateExistsFilter<E, T> = CriteriaDsl.(Subquery<*>, Root<E>, value: T) -> Predicate?

private typealias CreateChildFilter<E, C, T> = CriteriaDsl.(PluralJoin<E, *, C>, value: T) -> Predicate?
