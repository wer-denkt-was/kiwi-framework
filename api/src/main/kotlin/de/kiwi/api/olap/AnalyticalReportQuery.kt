package de.kiwi.api.olap

import de.kiwi.api.domain.*
import de.kiwi.api.schema.ColumnField
import de.kiwi.api.util.CriteriaDsl
import de.kiwi.api.util.reusableJoin
import jakarta.persistence.criteria.*
import jakarta.persistence.metamodel.*
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.reflect.KClass
import kotlin.reflect.cast
import java.sql.Date as SqlDate

/**
 * Abstract query for creating analytical reports based on an entity.
 * Analytical reports define a number of dimensions, metrics and slices to be queried.
 *
 * __Example__:
 * ```kt
 * class ExampleReport : AnalyticalReportQuery.kt<ExampleEntity>() {
 *     override val root: EntityType<ExampleEntity> = ExampleEntity_.class_
 *
 *     override val fields by defineFields {
 *         dimension("first", attribute(ExampleEntity_.first))
 *         dimension("second", attribute(ExampleEntity_.second))
 *         // ... other dimensions ...
 *
 *         metric("total", count())
 *         metric("firstFoo", countWhere { equal(it[ExampleEntity_.first], "foo") }
 *         metric("firstBar", countWhere { equal(it[ExampleEntity_.first], "bar") })
 *         metric("sumSecond", sum(attribute(ExampleEntity_.second)))
 *         // ... other metrics ...
 *     }
 * }
 * ```
 *
 * @param E Root entity type of report
 */
abstract class AnalyticalReportQuery<E : Any> : Query<AnalyticalReport>(), Authorized {
    val dimensions: MutableList<String> = ArrayList()
    val metrics: MutableList<String> = ArrayList()
    val slices: MutableMap<String, Slice> = LinkedHashMap()
    var sort: Sort = Sort.unsorted()

    abstract val root: EntityType<E>
    abstract val fields: DimensionAndMetricFields<E>

    open fun CriteriaQuery<*>.queryFrom(): Root<E> = from(root)

    open val accessControl: AccessControl<E>? = null
    open val accessControlAllowAdmin: Boolean = true

    open fun accessControlFilter(user: UserDescriptor): Specification<E>? =
        if (accessControlAllowAdmin) accessControl?.adminOrCheckAuthorized(user)
        else accessControl?.checkAuthorized(user)

    // Execute analytical queries outside transactional context by default
    override val transactional = false

    /**
     * Define the dimension and metric fields available in analytical reports generated this query.
     * - Dimensions are defined as `dimension("<name>", <selector>)` with value selectors
     * - Metrics are defined as `dimension("<name>", <selector>)` with aggregation selectors
     */
    protected fun defineFields(define: FieldsBuilder<E>.() -> Unit) = lazy {
        val builder = MutableFieldsBuilder<E>()
        builder.define()
        DimensionAndMetricFields(
            dimensions = builder.dimensions,
            metrics = builder.metrics,
        )
    }

    data class DimensionAndMetricFields<E : Any>(
        val dimensions: Map<String, TypedSelector<E, *>>,
        val metrics: Map<String, TypedAggregator<E, out Number>>,
    ) {
        private val dimensionsLowercase = dimensions.mapKeys { it.key.lowercase() }
        private val metricsLowercase = metrics.mapKeys { it.key.lowercase() }

        fun dimensionAvailable(name: String) = dimensionsLowercase.containsKey(name.lowercase())
        fun dimensionSelector(name: String) = dimensionsLowercase[name.lowercase()]
        fun metricAvailable(name: String) = metricsLowercase.containsKey(name.lowercase())
        fun metricAggregator(name: String) = metricsLowercase[name.lowercase()]
    }

    protected interface FieldsBuilder<E : Any> {
        /**
         * Copy dimensions and metrics from an existing [DimensionAndMetricFields] definition, for example:
         * ```kt
         * // In a subclass of an existing AnalyticalReportQuery implementation:
         * override val fields by defineFields {
         *   extends(super.fields)
         *   // add more dimensions/metrics
         * }
         * ```
         */
        fun extends(baseFields: DimensionAndMetricFields<E>)

        /**
         * Define a dimension.
         * ```kt
         * dimension("bySelect", select { root -> ... })
         * dimension("byAttribute", attribute(...))
         * dimension("byDate", date { root -> ... })
         * dimension("byDateAttribute", date(attribute(...)))
         * dimension("byDateFormat", dateFormat { root -> ... })
         * dimension("byDateFormatAttribute", dateFormat(attribute(...)))
         * dimension("byDay", dayOfMonth { root -> ... })
         * dimension("byDayAttribute", dayOfMonth(attribute(...)))
         * dimension("byMonth", month { root -> ... })
         * dimension("byMonthAttribute", month(attribute(...)))
         * dimension("byYear", year { root -> ... })
         * dimension("byYearAttribute", year(attribute(...)))
         * dimension("byYearAndMonth", yearAndMonth { root -> ... })
         * dimension("byYearAndMonthAttribute", yearAndMonth(attribute(...)))
         * dimension("byBuckets", select { root -> ... }) {
         *   range("first", 0..<10)
         *   range("second", 10..<20)
         *   otherwise("other")
         * }
         * dimension("withTypecast", cast(...::class) { castRoot -> ... })
         * dimension("withTypecastAttribute", cast(...::class, attribute(...)))
         * dimension("bySubquery", subquery(...) { childRoot -> ... })
         * ```
         */
        fun <T> dimension(name: String, selector: TypedSelector<E, T>)

        /**
         * Define a metric.
         * ```kt
         * metric("byAggregate", aggregate { root -> ... })
         * metric("byCount", count())
         * metric("byDistinct", countDistinct())
         * metric("byDistinctValues", countDistinct { root -> ... })
         * metric("byDistinctAttribute", countDistinct(attribute(...)))
         * metric("byCondition", countWhere { root -> ... })
         * metric("byEqualsValueCondition", countWhere(...) { root -> ... })
         * metric("byAttributeEqualsValueCondition", countWhere(..., attribute(...)))
         * metric("byTypeCondition", countOfType(...::class))
         * metric("byTypeCondition", countOfType<_, ...>())
         * metric("bySum", sum { root -> ... })
         * metric("bySumAttribute", sum(attribute(...)))
         * metric("bySumConditional", sumWhere { root -> <value> to <filter> })
         * metric("byAverage", average { root -> ... })
         * metric("byAverageAttribute", average(attribute(...)))
         * metric("byAverageConditional", averageWhere { root -> <value> to <filter> })
         * metric("byMin", min { root -> ... })
         * metric("byMinAttribute", min(attribute(...)))
         * metric("byMinConditional", minWhere { root -> <value> to <filter> })
         * metric("byMax", max { root -> ... })
         * metric("byMaxAttribute", max(attribute(...)))
         * metric("byMaxConditional", maxWhere { root -> <value> to <filter> })
         * ```
         */
        fun <T : Number> metric(name: String, aggregator: TypedAggregator<E, T>)

        /**
         * Remove an existing dimension/metric from the current definition
         */
        fun remove(name: String)
    }

    private class MutableFieldsBuilder<E : Any> : FieldsBuilder<E> {
        val dimensions = LinkedHashMap<String, TypedSelector<E, *>>()
        val metrics = LinkedHashMap<String, TypedAggregator<E, out Number>>()

        override fun extends(baseFields: DimensionAndMetricFields<E>) {
            dimensions.putAll(baseFields.dimensions)
            metrics.putAll(baseFields.metrics)
        }

        override fun <T> dimension(name: String, selector: TypedSelector<E, T>) {
            dimensions[name] = selector
        }

        override fun <T : Number> metric(name: String, aggregator: TypedAggregator<E, T>) {
            metrics[name] = aggregator
        }

        override fun remove(name: String) {
            dimensions.keys.removeIf { it.equals(name, ignoreCase = true) }
            metrics.keys.removeIf { it.equals(name, ignoreCase = true) }
        }
    }

    companion object {
        //region General Selectors

        /** Selects a value using a [ColumnField] selector. */
        inline fun <E : Any, reified T : Any> column(
            column: ColumnField<E, T, *>,
        ) = select<E, T> { root ->
            val selection = column.selectFrom(root, this@select)
            requireNotNull(selection as? Expression<T>) { "Column [${column.fieldName}] does not select an expression" }
        }

        /** Selects a value from an attribute. */
        inline fun <E : Any, reified T> attribute(
            attr: SingularAttribute<in E, T>,
        ) = select<E, T> { root ->
            root.get(attr)
        }

        /** Selects a value from a chain of attributes. */
        inline fun <E : Any, Z0, reified T> attribute(
            attr0: SingularAttribute<in E, Z0>,
            attr1: SingularAttribute<in Z0, T>,
        ) = select<E, T> { root ->
            root.getReusable(attr0).get(attr1)
        }

        /** Selects a value from a chain of attributes. */
        inline fun <E : Any, Z0, Z1, reified T> attribute(
            attr0: SingularAttribute<in E, Z0>,
            attr1: SingularAttribute<in Z0, Z1>,
            attr2: SingularAttribute<in Z1, T>,
        ) = select<E, T> { root ->
            root.getReusable(attr0).getReusable(attr1).get(attr2)
        }

        /** Selects a value from a chain of attributes. */
        inline fun <E : Any, Z0, Z1, Z2, reified T> attribute(
            attr0: SingularAttribute<in E, Z0>,
            attr1: SingularAttribute<in Z0, Z1>,
            attr2: SingularAttribute<in Z1, Z2>,
            attr3: SingularAttribute<in Z2, T>,
        ) = select<E, T> { root ->
            root.getReusable(attr0).getReusable(attr1).getReusable(attr2).get(attr3)
        }

        /** Selects a value from a chain of attributes. */
        inline fun <E : Any, Z0, Z1, Z2, Z3, reified T> attribute(
            attr0: SingularAttribute<in E, Z0>,
            attr1: SingularAttribute<in Z0, Z1>,
            attr2: SingularAttribute<in Z1, Z2>,
            attr3: SingularAttribute<in Z2, Z3>,
            attr4: SingularAttribute<in Z3, T>,
        ) = select<E, T> { root ->
            root.getReusable(attr0).getReusable(attr1).getReusable(attr2).getReusable(attr3).get(attr4)
        }

        fun <E, R> Path<E>.getReusable(attr: SingularAttribute<*, R>) =
            reusableJoin<E, R>(attr.name, JoinType.INNER)

        //endregion
        //region Dimension Selectors

        /** Create a [TypedSelector] by selecting a value from a [Root]. */
        inline fun <E : Any, reified T> select(select: CriteriaSelect<E, T>) =
            TypedSelector(T::class.java, select)

        /** Cast a root into the root of a subtype, then applying a selection on the cast root. */
        inline fun <E : Any, X : E, reified T> cast(toType: KClass<X>, then: CriteriaSelect<X, T>) =
            select<E, T> { root ->
                @Suppress("UNCHECKED_CAST")
                then(this, treat(root as Root<E>, toType.java))
            }

        /** Select the date of a given selection. */
        fun <E : Any, T : Comparable<T>> date(select: CriteriaSelect<E, T>) =
            select<E, SqlDate> { function("DATE", SqlDate::class.java, select(this, it)) }

        /** Select the date in a given format of a given selection. */
        fun <E : Any, T : Comparable<T>> dateFormat(format: String, select: CriteriaSelect<E, T>) =
            select<E, String> { function("DATE_FORMAT", String::class.java, select(this, it), literal(format)) }

        /** Select the day-of-month of a given selection */
        fun <E : Any, T : Comparable<T>> dayOfMonth(selectDate: CriteriaSelect<E, T>) =
            select<E, Int> { function("DAY", Int::class.java, selectDate(this, it)) }

        /** Select the month of a given selection */
        fun <E : Any, T : Comparable<T>> month(selectDate: CriteriaSelect<E, T>) =
            select<E, Int> { function("MONTH", Int::class.java, selectDate(this, it)) }

        /** Select the year of a given selection. */
        fun <E : Any, T : Comparable<T>> year(selectDate: CriteriaSelect<E, T>) =
            select<E, Int> { function("YEAR", Int::class.java, selectDate(this, it)) }

        /**
         * Select the year and month of a given selection, formatted as `month + (100 * year)`.
         * For example, the selection value `date '2024-07-01'` would be returned as the integer `202407`.
         */
        fun <E : Any, T : Comparable<T>> yearMonth(selectDate: CriteriaSelect<E, T>) =
            select<E, Int>((year(selectDate) * 100) + month(selectDate))

        /**
         * Select a dimension by categorizing selected values into discrete buckets.
         * ```kt
         * // Example:
         * buckets(attribute(Person_.age)) {
         *   range("child", 0..<18)
         *   range("young adult", 18..<30)
         *   range("adult", 30..<60)
         *   range("old adult", 60..<80)
         *   range("elderly", 80..<100)
         *   otherwise("other")
         * }
         * ```
         */
        fun <E : Any, T : Comparable<T>> buckets(
            selectValue: CriteriaSelect<E, T>,
            defineBuckets: BucketBuilder<T>.() -> Unit,
        ) = select { root ->
            val value = selectValue(this, root)
            val case = selectCase<String>()

            val builder = object : BucketBuilder<T> {
                override fun range(name: String, range: OpenEndRange<T>) {
                    val minBound = greaterThanOrEqualTo(value, literal(range.start))
                    val maxBound = lessThan(value, literal(range.endExclusive))
                    case.`when`(and(minBound, maxBound), literal(name))
                }

                override fun otherwise(name: String) {
                    case.otherwise(literal(name))
                }
            }

            builder.defineBuckets()
            return@select case
        }

        /** Select the entity type. */
        fun <E : Any> entityType() = select<E, Class<out E>> { it.type() }

        /** Create an artificially cast `SingularAttribute<E, ID>` for a given entity type */
        @Suppress("UNCHECKED_CAST")
        fun <E : BaseEntity<ID>, ID : EntityId> entityIdAttribute() =
            BaseEntity_.id as SingularAttribute<E, ID>

        //endregion
        //region Metric Aggregators

        inline fun <E : Any, reified N : Number> aggregate(aggregate: CriteriaAggregate<E, N>) =
            TypedAggregator(N::class.java, aggregate)

        /** Aggregate the total number of rows: `COUNT(*)` */
        fun <E : Any> count() = aggregate<E, Long> { count(it) }

        /** Aggregate the total number of rows where the selected value is not null: `COUNT(<value>)` */
        fun <E : Any> count(selectValue: CriteriaSelect<E, *>) =
            aggregate<E, Long> { count(selectValue(this, it)) }

        /** Aggregate the number of distinct and non-null selected values: `COUNT(DISTINCT <value>)` */
        fun <E : Any> countDistinct(selectValue: CriteriaSelect<E, *>) =
            aggregate<E, Long> { countDistinct(selectValue(this, it)) }

        /** Aggregate the total number of rows where the filter applies: `COUNT(*) FILTER (WHERE <filter>)` */
        fun <E : Any> countWhere(condition: CriteriaFilter<E>) =
            aggregate<E, Long> { sum(conditionalValue(condition(this, it), literal(1L))) }

        /**
         *  Aggregate the total number of rows where the given value is equal to the selected value:
         *  `COUNT(*) FILTER (WHERE <equalTo> = <value>)`
         */
        fun <E : Any, T> countWhere(equalTo: T, selectValue: CriteriaSelect<E, T>) =
            countWhere { equal(selectValue(this, it), literal(equalTo)) }

        /**
         * Aggregate the total number of rows where the row type matches the given type:
         * `COUNT(*) FILTER (WHERE <type> = TYPE(<root>))`
         */
        fun <E : Any> countOfType(type: KClass<out E>) =
            countWhere<E> { equal(it.type(), type.java) }

        /** Aggregate the sum of the selected value: `SUM(<value>)` */
        inline fun <E : Any, reified N : Number> sum(selectValue: CriteriaSelect<E, N>) =
            aggregate<E, N> { sum(selectValue(this, it)) }

        /**
         * Aggregate the sum of the selected value where the filter applies:
         * `SUM(<value>) FILTER (WHERE <filter>)`
         */
        inline fun <E : Any, reified N : Number> sumWhere(selectAndFilter: CriteriaSelectAndFilter<E, N>) =
            aggregate<E, N> {
                val (value, filter) = selectAndFilter(this, it)
                sum(conditionalValue(filter, value))
            }

        /** Aggregate the average of the selected value: `AVG(<value>)` */
        fun <E : Any> average(selectValue: CriteriaSelect<E, out Number>) =
            aggregate<E, Double> { avg(selectValue(this, it)) }

        /**
         * Aggregate the average of the selected value where the filter applies:
         * `AVG(<value>) FILTER (WHERE <filter>)`
         */
        inline fun <E : Any, reified N : Number> averageWhere(selectAndFilter: CriteriaSelectAndFilter<E, N>) =
            aggregate<E, Double> {
                val (value, filter) = selectAndFilter(this, it)
                avg(conditionalValue(filter, value))
            }

        /**
         * Aggregate the minimum of the selected value: `MIN(<value>)`
         */
        fun <E : Any> min(selectValue: CriteriaSelect<E, Number>) =
            aggregate<E, Number> { min(selectValue(this, it)) }

        /**
         * Aggregate the minimum of the selected value where the filter applies: `MIN(<value>) FILTER (WHERE <filter>)`
         */
        fun <E : Any> minWhere(selectAndFilter: CriteriaSelectAndFilter<E, Number>) =
            aggregate<E, Number> {
                val (value, filter) = selectAndFilter(this, it)
                min(conditionalValue(filter, value))
            }

        /**
         * Aggregate the maximum of the selected value: `MAX(<value>)`
         */
        fun <E : Any> max(selectValue: CriteriaSelect<E, Number>) =
            aggregate<E, Number> { max(selectValue(this, it)) }

        /**
         * Aggregate the maximum of the selected value where the filter applies: `MAX(<value>) FILTER (WHERE <filter>)`
         */
        fun <E : Any> maxWhere(selectAndFilter: CriteriaSelectAndFilter<E, Number>) =
            aggregate<E, Number> {
                val (value, filter) = selectAndFilter(this, it)
                max(conditionalValue(filter, value))
            }

        /**
         * Create an expression that returns the given expression when the predicate is true, or zero otherwise.
         */
        inline fun <reified N : Number> CriteriaBuilder.conditionalValue(
            predicate: Predicate,
            value: Expression<N>,
        ): Expression<N> =
            selectCase<Boolean, N>(predicate)
                .`when`(literal(true), value)
                .otherwise(literal(zeroValue(N::class)))

        /**
         * Return the zero value of the given number type.
         */
        fun <N : Number> zeroValue(numberType: KClass<N>): N =
            numberType.cast(
                when (numberType) {
                    Int::class -> 0
                    Long::class -> 0L
                    Float::class -> 0f
                    Double::class -> 0.0
                    Byte::class -> 0.toByte()
                    Short::class -> 0.toShort()
                    BigInteger::class -> BigInteger.ZERO
                    BigDecimal::class -> BigDecimal.ZERO
                    else -> throw AssertionError(numberType.java.name)
                }
            )

        //endregion
        //region Subquery Selector

        /**
         * Execute a child query on a [PluralAttribute] of the root entity,
         * selecting an aggregate value from the child entity.
         * `(SELECT <value> FROM <parent> JOIN <parent>.<child>)`
         */
        inline fun <E : Any, C : Any, reified N : Number> subquery(
            attribute: PluralAttribute<E, *, C>,
            childSelectValue: CriteriaSelect<C, N>,
        ) = subquery(attribute, N::class, childSelectValue.withNoFilter())

        /**
         * Execute a child query on a [PluralAttribute] of the root entity,
         * selecting an aggregate value from the child entity.
         * `(SELECT <value> FROM <parent> JOIN <parent>.<child>)`
         */
        fun <E : Any, C : Any, N : Number> subquery(
            attribute: PluralAttribute<E, *, C>,
            valueType: KClass<N>,
            childSelectValue: CriteriaSelect<C, N>,
        ) = subquery(attribute, valueType, childSelectValue.withNoFilter())

        /**
         * Execute a child query on a [PluralAttribute] of the root entity,
         * selecting an aggregate value and applying a filter to the child entity.
         * `(SELECT <value> FROM <parent> JOIN <parent>.<child> WHERE <filter>)`
         */
        fun <E : Any, C : Any, N : Number> subquery(
            attribute: PluralAttribute<E, *, C>,
            valueType: KClass<N>,
            childSelectAndFilter: CriteriaSelectAndFilter<C, N>,
        ) = TypedSelector<E, N>(valueType.java) { parent ->
            @Suppress("UNCHECKED_CAST") val parentRoot = parent as Root<E>
            val childQuery = query.subquery(valueType.java)
            val correlateParent = childQuery.correlate(parentRoot)
            val childJoin = when (attribute) {
                is SetAttribute<E, C> -> correlateParent.join(attribute)
                is ListAttribute<E, C> -> correlateParent.join(attribute)
                is CollectionAttribute<E, C> -> correlateParent.join(attribute)
                else -> throw AssertionError(attribute.javaType.name)
            }

            val (value, filter) = childSelectAndFilter(this, childJoin)
            childQuery.select(value)
            childQuery.where(filter)
        }

        //endregion
    }

    interface BucketBuilder<T : Comparable<T>> {
        fun range(name: String, range: OpenEndRange<T>)
        fun otherwise(name: String)
    }
}

//region Slices

/**
 * Data structure containing a slice of a dimension within an [AnalyticalReportQuery].
 * Slices specify which values of a dimension should be included when aggregating the [AnalyticalReport].
 *
 * A slice can specify:
 * - A set of included values with `eq`
 * - An exclusive minimum value with `gt` ("greater than")
 * - An inclusive minimum value with `ge` ("greater or equal")
 * - An exclusive maximum value with `lt` ("less than")
 * - An inclusive maximum value with `le` ("less or equal")
 * - If value is null or not null with `null` (`true` for "is null", `false` for "is not null")
 *
 * Slices can be:
 * - Combined conjunctive with `and`
 * - Combined disjunctive with `or`
 * - Negated with `not`
 */
data class Slice(
    val eq: Any? = null,
    val gt: Any? = null,
    val lt: Any? = null,
    val ge: Any? = null,
    val le: Any? = null,
    val `in`: Set<Any>? = null,
    val `null`: Boolean? = null,
    val and: List<Slice>? = null,
    val or: List<Slice>? = null,
    val not: Slice? = null,
) {
    fun <T> CriteriaDsl.filter(
        expression: Expression<T>,
        valueConverter: (Any) -> T?,
    ): Predicate {
        fun recursion(slice: Slice) = with(slice) {
            filter(expression, valueConverter)
        }

        @Suppress("UNCHECKED_CAST")
        fun compare(value: Any, operator: CriteriaCompare<Any>) = valueConverter(value)?.let { convertedValue ->
            expression as Expression<Comparable<Any>>
            convertedValue as Comparable<Any>
            operator(expression, convertedValue)
        }

        val predicates = buildList<Predicate> {
            eq?.let { eq -> compare(eq, CriteriaBuilder::equal) }?.let { add(it) }
            gt?.let { gt -> compare(gt, CriteriaBuilder::greaterThan) }?.let { add(it) }
            ge?.let { ge -> compare(ge, CriteriaBuilder::greaterThanOrEqualTo)?.let { add(it) } }
            lt?.let { lt -> compare(lt, CriteriaBuilder::lessThan) }?.let { add(it) }
            le?.let { le -> compare(le, CriteriaBuilder::lessThanOrEqualTo) }?.let { add(it) }
            `in`?.let { values -> add(expression.`in`(values.map(valueConverter))) }
            `null`?.let { isNull -> add(if (isNull) isNull(expression) else isNotNull(expression)) }
            and?.let { slices -> add(and(*slices.map { recursion(it) }.toTypedArray())) }
            or?.let { slices -> add(or(*slices.map { recursion(it) }.toTypedArray())) }
            not?.let { slice -> add(not(recursion(slice))) }
        }

        return and(*predicates.toTypedArray())
    }
}

private typealias CriteriaCompare<T> = CriteriaDsl.(Expression<Comparable<T>>, Comparable<T>) -> Predicate

//endregion
//region Selector, Aggregator, Filter Types

/**
 * Functional interface selecting an [Expression] of type [T] from an entity [Root],
 * with a [CriteriaBuilder] available within the selection context.
 */
fun interface CriteriaSelect<E, T> {
    fun CriteriaDsl.selectValue(from: From<*, E>): Expression<T>

    // Note: This is required for the interface to be used in contexts with multiple receivers
    operator fun invoke(builder: CriteriaDsl, from: From<*, E>): Expression<T> = builder.selectValue(from)
}

/**
 * Functional interface aggregating an [Expression] of a numeric type from an entity [Root],
 * with a [CriteriaBuilder] available within the selection context.
 * This interface is a specialization of the more general [CriteriaSelect].
 */
fun interface CriteriaAggregate<E, T : Number> : CriteriaSelect<E, T>

/**
 * Functional interface selecting a [Predicate] from an entity [Root],
 * with a [CriteriaBuilder] available within the selection context.
 */
fun interface CriteriaFilter<E> : CriteriaSelect<E, Boolean> {
    fun CriteriaDsl.filterPredicate(from: From<*, E>): Predicate

    // Note: Compatibility with usage as CriterialSelect
    override fun CriteriaDsl.selectValue(from: From<*, E>): Expression<Boolean> = filterPredicate(from)

    // Note: This is required for the interface to be used in contexts with multiple receivers
    override operator fun invoke(builder: CriteriaDsl, from: From<*, E>): Predicate = builder.filterPredicate(from)
}

/**
 * Add a conjunction filter ("always true") to the selection, effectively omitting the filter altogether
 */
fun <E, T> CriteriaSelect<E, T>.withNoFilter() = CriteriaSelectAndFilter { selectValue(it) to conjunction() }

/**
 * Add a [CriteriaFilter] to the given [CriteriaSelect], creating a combined [CriteriaSelectAndFilter]
 */
infix fun <E, T> CriteriaSelect<E, T>.filter(filter: CriteriaFilter<E>): CriteriaSelectAndFilter<E, T> {
    val select = this
    return CriteriaSelectAndFilter { from -> select(this, from) to filter(this, from) }
}

/**
 * Functional interface selecting both a value [Expression] and a filter [Predicate] from an entity [Root],
 * with a [CriteriaBuilder] available within the selection context.
 */
fun interface CriteriaSelectAndFilter<E, T> {
    fun CriteriaDsl.selectAndFilter(from: From<*, E>): Pair<Expression<T>, Predicate>

    // Note: This is required for the interface to be used in contexts with multiple receivers
    operator fun invoke(builder: CriteriaDsl, from: From<*, E>) = builder.selectAndFilter(from)
}

//region Typed Selector, Aggregator

/** Wrapper around a [CriteriaSelect] selector with the [Class] of the selected type. */
data class TypedSelector<E : Any, T>(
    val valueType: Class<T>,
    val selector: CriteriaSelect<E, T>,
) : CriteriaSelect<E, T> by selector

/** Wrapper around a [CriteriaAggregate] aggregator with the [Class] of the aggregated type. */
data class TypedAggregator<E : Any, T : Number>(
    val valueType: Class<T>,
    val aggregator: CriteriaAggregate<E, T>,
) : CriteriaAggregate<E, T> by aggregator

//endregion
//endregion
//region Selector, Aggregator, Filter Combination

inline fun <E, T, R> CriteriaSelect<E, T>.map(crossinline mapper: CriteriaDsl.(Expression<T>) -> Expression<R>) =
    CriteriaSelect { mapper(selectValue(it)) }

inline fun <E, T, V, R> CriteriaSelect<E, T>.combine(
    other: CriteriaSelect<E, V>,
    crossinline combiner: CriteriaDsl.(Expression<T>, Expression<V>) -> Expression<R>,
) = CriteriaSelect { combiner(selectValue(it), other(this, it)) }

operator fun <E, N : Number> CriteriaSelect<E, N>.plus(other: CriteriaSelect<E, N>) =
    CriteriaSelect<E, N> { sum(selectValue(it), other(this, it)) }

operator fun <E, N : Number> CriteriaSelect<E, N>.plus(literal: N) =
    CriteriaSelect<E, N> { sum(selectValue(it), literal) }

operator fun <E, N : Number> N.plus(other: CriteriaSelect<E, N>) =
    CriteriaSelect<E, N> { sum(this@plus, other(this, it)) }

operator fun <E, N : Number> CriteriaSelect<E, N>.minus(other: CriteriaSelect<E, N>) =
    CriteriaSelect<E, N> { diff(selectValue(it), other(this, it)) }

operator fun <E, N : Number> CriteriaSelect<E, N>.minus(literal: N) =
    CriteriaSelect<E, N> { diff(selectValue(it), literal) }

operator fun <E, N : Number> N.minus(other: CriteriaSelect<E, N>) =
    CriteriaSelect<E, N> { diff(this@minus, other(this, it)) }

operator fun <E, N : Number> CriteriaSelect<E, N>.times(other: CriteriaSelect<E, N>) =
    CriteriaSelect<E, N> { prod(selectValue(it), other(this, it)) }

operator fun <E, N : Number> CriteriaSelect<E, N>.times(literal: N) =
    CriteriaSelect<E, N> { prod(selectValue(it), literal) }

operator fun <E, N : Number> N.times(other: CriteriaSelect<E, N>) =
    CriteriaSelect<E, N> { prod(this@times, other(this, it)) }

operator fun <E, N : Number> CriteriaSelect<E, N>.div(other: CriteriaSelect<E, N>) =
    CriteriaSelect<E, Number> { quot(selectValue(it), other(this, it)) }

operator fun <E, N : Number> CriteriaSelect<E, N>.div(literal: N) =
    CriteriaSelect<E, Number> { quot(selectValue(it), literal) }

operator fun <E, N : Number> N.div(other: CriteriaSelect<E, N>) =
    CriteriaSelect<E, Number> { quot(this@div, other(this, it)) }

infix fun <E> CriteriaSelect<E, String>.concat(other: CriteriaSelect<E, String>) =
    CriteriaSelect<E, String> { concat(selectValue(it), other(this, it)) }

infix fun <E> CriteriaSelect<E, String>.concat(literal: String): CriteriaSelect<E, String> =
    CriteriaSelect<E, String> { concat(selectValue(it), literal) }

infix fun <E> String.concat(other: CriteriaSelect<E, String>) =
    CriteriaSelect<E, String> { concat(this@concat, other(this, it)) }

operator fun <E> CriteriaSelect<E, Boolean>.not() =
    CriteriaFilter { not(selectValue(it)) }

infix fun <E> CriteriaSelect<E, Boolean>.and(other: CriteriaSelect<E, Boolean>) =
    CriteriaFilter { and(selectValue(it), other(this, it)) }

infix fun <E> CriteriaSelect<E, Boolean>.or(other: CriteriaSelect<E, Boolean>) =
    CriteriaFilter { or(selectValue(it), other(this, it)) }

fun <E> CriteriaSelect<E, *>.isNull() =
    CriteriaFilter { isNull(selectValue(it)) }

fun <E> CriteriaSelect<E, *>.isNotNull() =
    CriteriaFilter { isNotNull(selectValue(it)) }

fun <E, T> CriteriaSelect<E, T>.inList(vararg items: T) =
    CriteriaFilter { selectValue(it).`in`(*items) }

fun <E, T> CriteriaSelect<E, T>.inList(list: Collection<T>) =
    CriteriaFilter { selectValue(it).`in`(list) }

fun <E, T> CriteriaSelect<E, T>.notInList(vararg items: T) =
    inList(*items).not()

fun <E, T> CriteriaSelect<E, T>.notInList(list: Collection<T>) =
    inList(list).not()


operator fun <E, T : Number> CriteriaAggregate<E, T>.plus(other: CriteriaAggregate<E, T>) =
    CriteriaAggregate<E, T> { sum(selectValue(it), other(this, it)) }

operator fun <E, T : Number> CriteriaAggregate<E, T>.plus(literal: T) =
    CriteriaAggregate<E, T> { sum(selectValue(it), literal) }

operator fun <E, T : Number> T.plus(other: CriteriaAggregate<E, T>) =
    CriteriaAggregate<E, T> { sum(this@plus, other(this, it)) }

operator fun <E, T : Number> CriteriaAggregate<E, T>.minus(other: CriteriaAggregate<E, T>) =
    CriteriaAggregate<E, T> { diff(selectValue(it), other(this, it)) }

operator fun <E, T : Number> CriteriaAggregate<E, T>.minus(literal: T) =
    CriteriaAggregate<E, T> { diff(selectValue(it), literal) }

operator fun <E, T : Number> T.minus(other: CriteriaAggregate<E, T>) =
    CriteriaAggregate<E, T> { diff(this@minus, other(this, it)) }

operator fun <E, T : Number> CriteriaAggregate<E, T>.times(other: CriteriaAggregate<E, T>) =
    CriteriaAggregate<E, T> { prod(selectValue(it), other(this, it)) }

operator fun <E, T : Number> CriteriaAggregate<E, T>.times(literal: T) =
    CriteriaAggregate<E, T> { prod(selectValue(it), literal) }

operator fun <E, T : Number> T.times(other: CriteriaAggregate<E, T>) =
    CriteriaAggregate<E, T> { prod(this@times, other(this, it)) }

operator fun <E, T : Number> CriteriaAggregate<E, T>.div(other: CriteriaAggregate<E, T>) =
    CriteriaAggregate<E, Number> { quot(selectValue(it), other(this, it)) }

operator fun <E, T : Number> CriteriaAggregate<E, T>.div(literal: T) =
    CriteriaAggregate<E, Number> { quot(selectValue(it), literal) }

operator fun <E, T : Number> T.div(other: CriteriaAggregate<E, T>) =
    CriteriaAggregate<E, Number> { quot(this@div, other(this, it)) }

//endregion
