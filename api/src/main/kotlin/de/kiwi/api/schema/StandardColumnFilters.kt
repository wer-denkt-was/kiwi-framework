package de.kiwi.api.schema

import de.kiwi.api.application.service.EntityTypeProvider
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.Expression
import jakarta.persistence.criteria.Predicate
import org.springframework.core.convert.ConversionService
import org.springframework.data.jpa.repository.query.EscapeCharacter

/**
 * Container for standard [ColumnFilterSet] instances commonly used for [ColumnType]s.
 */
object StandardColumnFilters {
    /**
     * [ColumnFilterSet] *without any* operators, use if filtering is not supported for the given [ColumnType]
     */
    val empty by lazy {
        ColumnFilterSet.Empty
    }

    /**
     * [ColumnFilterSet] providing basic equality, null-check, and in-list operators
     */
    val basic by lazy {
        defineColumnFilterSet(defaultOperator = InListOperator) {
            add(EqualToOperator)
            add(NotEqualOperator)
            add(InListOperator)
            add(NotInListOperator)
            add(IsNullOperator)
        }
    }

    /**
     * [ColumnFilterSet] extending [basic] operators, providing and comparison operators
     */
    val default by lazy {
        basic.extendColumnFilterSet {
            add(GreaterThanOperator)
            add(GreaterOrEqualOperator)
            add(LessThanOperator)
            add(LessOrEqualOperator)
        }
    }

    /**
     * [ColumnFilterSet] extending [default] operators, providing string pattern-matching operators
     */
    val patternMatching by lazy {
        default.extendColumnFilterSet {
            add(StringContainsOperator)
            add(StringStartsWithOperator)
        }
    }
}

@Deprecated(message = "Use container object instead", replaceWith = ReplaceWith("StandardColumnFilters.empty"))
val emptyColumnFilters by StandardColumnFilters::empty

@Deprecated(message = "Use container object instead", replaceWith = ReplaceWith("StandardColumnFilters.basic"))
val basicColumnFilters by StandardColumnFilters::basic

@Deprecated(message = "Use container object instead", replaceWith = ReplaceWith("StandardColumnFilters.default"))
val standardColumnFilters by StandardColumnFilters::default

@Deprecated(
    message = "Use container object instead",
    replaceWith = ReplaceWith("StandardColumnFilters.patternMatching"),
)
val patternMatchColumnFilters by StandardColumnFilters::patternMatching

/**
 * Operator negating a delegate operator's filter predicate
 */
abstract class NegateOperator(symbol: String) : ColumnFilterOperator(symbol) {
    override fun <T, M> filter(
        paramValues: List<String>,
        expression: Expression<T>,
        column: ColumnField<*, T, M>,
        builder: CriteriaBuilder,
        conversion: ColumnFilterConversion,
    ): Predicate {
        return delegate
            .filter(paramValues, expression, column, builder, conversion)
            .not() // Negate delegate predicate
    }

    abstract val delegate: ColumnFilterOperator
}

/**
 * Operator checking for value equality
 */
object EqualToOperator : SingleValueOperator("eq") {
    override fun <T> singleValueFilter(value: T, expression: Expression<T>, builder: CriteriaBuilder): Predicate {
        return builder.equal(expression, value)
    }
}

/**
 * Operator checking for value inequality
 */
object NotEqualOperator : NegateOperator("ne") {
    override val delegate = EqualToOperator
}

/**
 * Operator checking if the expression is contained in a list of values
 */
object InListOperator : MultiValueOperator("in") {
    override fun <T> multiValueFilter(
        values: List<T>,
        expression: Expression<T>,
        builder: CriteriaBuilder,
    ): Predicate {
        return expression.`in`(values)
    }
}

/**
 * Operator checking if the expression is not contained in a list of values
 */
object NotInListOperator : NegateOperator("not") {
    override val delegate = InListOperator
}

/**
 * Operator checking if the expression is null if the value is true, or not null if the value is false
 */
object IsNullOperator : StaticTypeValueOperator<Boolean>("null") {
    override val staticType = ColumnType.identity<Boolean>()

    override fun <T> valueFilter(value: Boolean, expression: Expression<T>, builder: CriteriaBuilder): Predicate {
        return if (value) builder.isNull(expression)
        else builder.isNotNull(expression)
    }
}

/**
 * Base class for operators comparing the expression to a value using a relational comparison
 */
abstract class CompareOperator(symbol: String) : SingleValueOperator(symbol) {
    override fun <T> singleValueFilter(value: T, expression: Expression<T>, builder: CriteriaBuilder): Predicate {
        // Assert that value is comparable
        val compareValue = value as? Comparable<*>
            ?: throw OperatorNotApplicable((value ?: Unit)::class.java)

        // Assert that expression is comparable
        if (!Comparable::class.java.isAssignableFrom(expression.javaType)) {
            throw OperatorNotApplicable(expression.javaType)
        }

        @Suppress("UNCHECKED_CAST") // Use "Any" top-type for comparable
        return compare(
            expression = expression as Expression<Comparable<Any>>,
            value = compareValue as Comparable<Any>,
            builder = builder,
        )
    }

    abstract fun <C : Comparable<C>> compare(expression: Expression<C>, value: C, builder: CriteriaBuilder): Predicate
}

object GreaterThanOperator : CompareOperator("gt") {
    override fun <C : Comparable<C>> compare(expression: Expression<C>, value: C, builder: CriteriaBuilder): Predicate {
        return builder.greaterThan(expression, value)
    }
}

object LessThanOperator : CompareOperator("lt") {
    override fun <C : Comparable<C>> compare(expression: Expression<C>, value: C, builder: CriteriaBuilder): Predicate {
        return builder.lessThan(expression, value)
    }
}

object GreaterOrEqualOperator : CompareOperator("ge") {
    override fun <C : Comparable<C>> compare(expression: Expression<C>, value: C, builder: CriteriaBuilder): Predicate {
        return builder.greaterThanOrEqualTo(expression, value)
    }
}

object LessOrEqualOperator : CompareOperator("le") {
    override fun <C : Comparable<C>> compare(expression: Expression<C>, value: C, builder: CriteriaBuilder): Predicate {
        return builder.lessThanOrEqualTo(expression, value)
    }
}

abstract class LikePatternOperator(symbol: String) : SingleValueOperator(symbol) {
    override fun <T> singleValueFilter(value: T, expression: Expression<T>, builder: CriteriaBuilder): Predicate {
        val stringValue = value as? String ?: throw OperatorNotApplicable(value!!::class.java)
        val escapedValue = escapeCharacter.escape(stringValue) ?: ""
        val pattern = valueToPattern(escapedValue)

        // Only applicable to string expressions
        val stringExpression = if (String::class.java.isAssignableFrom(expression.javaType)) {
            @Suppress("UNCHECKED_CAST")
            expression as Expression<String>
        } else {
            throw OperatorNotApplicable(expression.javaType)
        }

        return builder.like(stringExpression, pattern, escapeCharacter.escapeCharacter)
    }

    open val escapeCharacter: EscapeCharacter = EscapeCharacter.DEFAULT

    abstract fun valueToPattern(value: String): String
}

object StringContainsOperator : LikePatternOperator("contains") {
    override fun valueToPattern(value: String): String = "%${value}%"
}

object StringStartsWithOperator : LikePatternOperator("startsWith") {
    override fun valueToPattern(value: String): String = "${value}%"
}

/**
 * Standard implementation of [ColumnFilterConversion] using a Spring [ConversionService]
 * and an [EntityTypeProvider] to convert filter parameters into column attribute values.
 */
class StandardColumnFilterConversion(
    private val conversionService: ConversionService,
    private val entityTypeProvider: EntityTypeProvider,
) : ColumnFilterConversion {
    override fun <T, M> convert(input: Any, columnType: ColumnType<T, M>): T {
        // For well-known special column types, use specific conversion rules
        return when (columnType) {
            is EntityClassNameType, is EntityDiscriminatorValueType -> convertEntityClassName(input.toString())
            else -> convertDefault(input, columnType)
        }
    }

    private fun <T : Class<*>> convertEntityClassName(entityClassName: String): T {
        // Look up entity class name from metamodel provider
        @Suppress("UNCHECKED_CAST")
        return (entityTypeProvider.findEntityType(entityClassName)?.javaType as T?
            ?: throw ColumnFilterConversion.ConversionFailed("unknown-entity-class-name"))
    }

    private fun <T, M> convertDefault(input: Any, columnType: ColumnType<T, M>): T {
        // Default conversion: (input: Any) --[conversionService]-> (columnValue: M) --[parse]-> (attributeValue: T)
        val columnValue = conversionService.convert(input, columnType.valueType)
            ?: throw ColumnFilterConversion.ConversionFailed("null")

        try {
            return columnType.parse(columnValue)
        } catch (ex: ColumnType.ParseNotSupported) {
            val reason = ex.message ?: "unknown"
            throw ColumnFilterConversion.ConversionFailed(reason)
        }
    }
}
