package de.kiwi.api.schema

import de.kiwi.api.domain.Auditable
import de.kiwi.api.domain.DomainException
import de.kiwi.api.util.splitMultiValueParameters
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.Expression
import jakarta.persistence.criteria.Predicate

/**
 * Object representing a set of [ColumnFilterOperator]s applicable to a given [ColumnType].
 */
interface ColumnFilterSet {
    val operators: Set<String>
    val defaultOperator: ColumnFilterOperator?

    operator fun get(symbol: String): ColumnFilterOperator?

    interface Builder {
        fun add(operator: ColumnFilterOperator)
        fun remove(operator: ColumnFilterOperator)
        fun remove(operator: String)
    }

    object Empty : ColumnFilterSet {
        override val operators = emptySet<String>()
        override val defaultOperator = null
        override fun get(symbol: String) = null
    }
}

abstract class ColumnFilterException(
    code: String,
    args: Map<String, *>,
) : DomainException(status = Status.BadRequest, code = code, args = args) {
    override val severity = Auditable.Severity.Disabled
}

/**
 * Define a new set of [ColumnFilterOperator]s with a single default operator.
 */
fun defineColumnFilterSet(
    defaultOperator: ColumnFilterOperator,
    build: ColumnFilterSet.Builder.() -> Unit,
): ColumnFilterSet {
    val builder = MutableColumnFilterSetBuilder().apply(build)
    return ImmutableColumnFilterSet(defaultOperator, builder.operators)
}

/**
 * Extend an existing [ColumnFilterSet], adding or removing [ColumnFilterOperator]s.
 */
fun ColumnFilterSet.extendColumnFilterSet(
    defaultOperator: ColumnFilterOperator? = null,
    build: ColumnFilterSet.Builder.() -> Unit,
): ColumnFilterSet {
    val builder = MutableColumnFilterSetBuilder(existing = this).apply(build)
    val overrideDefaultOperator = defaultOperator ?: this.defaultOperator
    return ImmutableColumnFilterSet(overrideDefaultOperator, builder.operators)
}

/**
 * Helper for converting String parameter values into parsed [ColumnField] attribute values.
 */
interface ColumnFilterConversion {
    @Throws(ConversionFailed::class)
    fun <T, M> convert(input: Any, columnType: ColumnType<T, M>): T

    data class ConversionFailed(val reason: String) : ColumnFilterException(
        code = "column-filter-conversion-failed",
        args = mapOf("reason" to reason),
    )
}

/**
 * An operator filtering the values associated with a [ColumnField] based on filter parameter values.
 */
abstract class ColumnFilterOperator(val symbol: String) {
    init {
        require(symbol.isNotBlank()) { "ColumnFilterOperator ${javaClass.name} missing symbol" }
    }

    @Throws(OperatorNotApplicable::class)
    abstract fun <T, M> filter(
        paramValues: List<String>,
        expression: Expression<T>,
        column: ColumnField<*, T, M>,
        builder: CriteriaBuilder,
        conversion: ColumnFilterConversion,
    ): Predicate

    inner class OperatorNotApplicable(val type: Class<*>) : ColumnFilterException(
        code = "operator-not-applicable",
        args = mapOf("operator" to symbol, "type" to type.name)
    )
}

/**
 * Abstract base class for [ColumnFilterOperator]s converting and filtering based on a single parameter value.
 */
abstract class SingleValueOperator(symbol: String) : ColumnFilterOperator(symbol) {
    override fun <T, M> filter(
        paramValues: List<String>,
        expression: Expression<T>,
        column: ColumnField<*, T, M>,
        builder: CriteriaBuilder,
        conversion: ColumnFilterConversion,
    ): Predicate {
        val paramValue = when (val valueCount = paramValues.size) {
            1 -> paramValues.first()
            0 -> return builder.conjunction() // Missing value, ignore filter
            else -> throw OperatorValueAmbiguous(valueCount)
        }

        val filterValue = conversion.convert(paramValue, column.type)
        return singleValueFilter(filterValue, expression, builder)
    }

    abstract fun <T> singleValueFilter(
        value: T,
        expression: Expression<T>,
        builder: CriteriaBuilder,
    ): Predicate

    inner class OperatorValueAmbiguous(valueCount: Int) : ColumnFilterException(
        code = "operator-value-ambiguous",
        args = mapOf("operator" to symbol, "valueCount" to valueCount)
    )
}

/**
 * Abstract base class for [ColumnFilterOperator]s converting and filtering based on multiple parameter values.
 * If a single parameter value is passed, it is split using the [splitMultiValueParameters] algorithm.
 */
abstract class MultiValueOperator(symbol: String) : ColumnFilterOperator(symbol) {
    override fun <T, M> filter(
        paramValues: List<String>,
        expression: Expression<T>,
        column: ColumnField<*, T, M>,
        builder: CriteriaBuilder,
        conversion: ColumnFilterConversion,
    ): Predicate {
        val splitParamValues = paramValues.splitMultiValueParameters()
        val filterValues = splitParamValues.map { conversion.convert(it, column.type) }
        return multiValueFilter(filterValues, expression, builder)
    }

    abstract fun <T> multiValueFilter(
        values: List<T>,
        expression: Expression<T>,
        builder: CriteriaBuilder,
    ): Predicate
}

/**
 * Abstract base class for [ColumnFilterOperator]s using a static type for converting the parameter value.
 * For example, if the operator always expects a "true"/"false" parameter, extend this class with [S] as Boolean.
 * @param S Static single-value parameter type of this operator
 */
abstract class StaticTypeValueOperator<S>(symbol: String) : ColumnFilterOperator(symbol) {
    override fun <T, M> filter(
        paramValues: List<String>,
        expression: Expression<T>,
        column: ColumnField<*, T, M>,
        builder: CriteriaBuilder,
        conversion: ColumnFilterConversion,
    ): Predicate {
        val paramValue = paramValues.firstOrNull() ?: return builder.conjunction()
        val filterValue = conversion.convert(paramValue, staticType)
        return valueFilter(filterValue, expression, builder)
    }

    abstract val staticType: ColumnType<S, S>

    abstract fun <T> valueFilter(value: S, expression: Expression<T>, builder: CriteriaBuilder): Predicate
}

/**
 * Internal mutable [ColumnFilterSet.Builder] implementation,
 * used as an intermediate during filter-set construction
 */
private class MutableColumnFilterSetBuilder() : ColumnFilterSet.Builder {
    val operators = LinkedHashMap<String, ColumnFilterOperator>()

    constructor(existing: ColumnFilterSet) : this() {
        existing.operators.forEach { operators[it] = existing[it]!! }
    }

    override fun add(operator: ColumnFilterOperator) {
        operators[operator.symbol] = operator
    }

    override fun remove(operator: ColumnFilterOperator) {
        operators.remove(operator.symbol)
    }

    override fun remove(operator: String) {
        operators.remove(operator)
    }
}

/**
 * Internal immutable [ColumnFilterSet] implementation
 */
private class ImmutableColumnFilterSet(
    override val defaultOperator: ColumnFilterOperator?,
    private val operatorDefinitions: Map<String, ColumnFilterOperator>,
) : ColumnFilterSet {
    override val operators get() = operatorDefinitions.keys
    override fun get(symbol: String) = operatorDefinitions[symbol]
}
