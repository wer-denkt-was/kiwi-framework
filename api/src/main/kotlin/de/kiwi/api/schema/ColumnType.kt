package de.kiwi.api.schema

import de.kiwi.api.domain.EntityId
import de.kiwi.api.domain.Validation
import de.kiwi.api.util.lookupSupertypeArgument
import jakarta.persistence.DiscriminatorValue
import jakarta.persistence.Entity
import org.springframework.util.unit.DataSize
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URI
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter.*
import java.util.*

/**
 * Type interface specifying how a [ColumnField] is formatted (using [ColumnType.format])
 * or parsed (using [ColumnType.parse]) when represented through an API.
 *
 * ```
 * read: (entity: E) --[ColumnSelector<E, T>]-> (attributeValue: T) --[ColumnType<T, M>]-> (formattedValue: M)
 *
 * write: (formattedValue: M) --[ColumnType<T, M>]-> (attributeValue: T) --[ColumnSelector<E, T>]-> (entity: E)
 * ```
 * @param T Column value type
 * @param M Formatted value type
 */
interface ColumnType<T, M> {
    /**
     * Class of the formatted value type [M] representing a column value
     */
    val valueType: Class<M>

    /** Set of filter operators supported by this [ColumnType] */
    val filterSet: ColumnFilterSet get() = StandardColumnFilters.default

    /**
     * Format a column value of type [T] into a formatted value representation of type [M]
     */
    fun format(value: T): M

    /**
     * Parse a formatted representation of type [M] into a column value of type [T]
     * @throws ParseNotSupported If this [ColumnType] does not support parsing values
     */
    @Throws(ParseNotSupported::class)
    fun parse(value: M): T

    /** Exception thrown when parsing a column value is not supported by the given [ColumnType.parse] function. */
    class ParseNotSupported : UnsupportedOperationException("parse-not-supported")

    companion object {
        /** Column type passing the given value through as-is, performing no formatting or validation. */
        inline fun <reified X : Any> identity(): ColumnType<X, X> = identity(X::class.java)

        /** Column type passing the given value through as-is. */
        fun <X : Any> identity(valueType: Class<X>): ColumnType<X, X> = IdentityColumnType(valueType)

        /** Column type formatting/parsing EntityIDs as strings */
        inline fun <reified ID : EntityId> entityId() = EntityIdColumnType(ID::class.java)

        /** Column type returning or parsing an enum by name. */
        inline fun <reified E : Enum<E>> enum(): ColumnType<E, String> = EnumColumnType(E::class.java)

        /**
         * Column type converting the given value to a string using [Any.toString],
         * with an optional parse function to create a value from a string.
         * If no parse function is given, attempting to parse will throw a [ParseNotSupported] exception.
         */
        fun <X> stringify(parseString: (String) -> X = { throw ParseNotSupported() }) =
            StringifyColumnType(parseString)

        /**
         * Create an inline [ColumnType] using a pair of parse- and format-functions.
         */
        inline operator fun <T, reified M> invoke(
            filterSet: ColumnFilterSet = StandardColumnFilters.default,
            crossinline parse: (M) -> T = { throw ParseNotSupported() },
            crossinline format: (T) -> M,
        ) = object : ColumnType<T, M> {
            override val valueType = M::class.java
            override val filterSet = filterSet
            override fun format(value: T): M = format(value)
            override fun parse(value: M): T = parse(value)
        }

        inline fun <reified X : Any> map(noinline mapper: (X) -> X): ColumnType<X, X> =
            identity<X>().map(mapper)

        inline fun <reified X : Any> validate(validator: Validation<X>): ColumnType<X, X> =
            identity<X>().validate(validator)

        inline fun <reified X : Any> reduceArray(crossinline reducer: (Array<*>) -> X) =
            ColumnType<Array<*>, X>(filterSet = StandardColumnFilters.empty) { reducer(it) }
    }
}

/**
 * Type interface throwing a [ColumnType.ParseNotSupported] exception by default
 * when attempting to call [ColumnType.parse].
 */
interface ReadOnlyColumnType<T, M> : ColumnType<T, M> {
    override fun parse(value: M): T = throw ColumnType.ParseNotSupported()
}

/**
 * Apply validation to a parsed value.
 */
infix fun <T, M> ColumnType<T, M>.validate(validator: Validation<T>): ColumnType<T, M> {
    val delegate = this
    return object : ColumnType<T, M> by delegate {
        override fun parse(value: M): T = validator(delegate.parse(value))
    }
}

infix fun <T, M> ColumnType<T, M>.map(mapper: (M) -> M): ColumnType<T, M> {
    val delegate = this
    return object : ColumnType<T, M> by delegate {
        override fun format(value: T): M = mapper(delegate.format(value))
    }
}

/**
 * Pipeline two [ColumnType]s together, such that
 * `(ColumnType<A, B>) pipe (ColumnType<B, C>)` creates `ColumnType<A, C>`.
 */
infix fun <T, M, R> ColumnType<T, M>.pipe(other: ColumnType<M, R>): ColumnType<T, R> {
    val self = this
    return object : ColumnType<T, R> {
        override val valueType: Class<R> = other.valueType
        override val filterSet: ColumnFilterSet = self.filterSet
        override fun parse(value: R): T = other.parse(value).let { self.parse(it) }
        override fun format(value: T): R = self.format(value).let { other.format(it) }
    }
}

/**
 * Create a [ColumnType] object using the specific given [ColumnFilterSet].
 */
infix fun <T, M> ColumnType<T, M>.withFilters(filters: ColumnFilterSet) = object : ColumnType<T, M> by this {
    override val filterSet = filters
}

open class IdentityColumnType<X>(override val valueType: Class<X>) : ColumnType<X, X> {
    final override fun format(value: X): X = value
    final override fun parse(value: X): X = value
}

/** Column type representing an [EntityId] value. */
class EntityIdColumnType<ID : EntityId>(val entityIdType: Class<ID>) : ColumnType<ID, String> {
    override val valueType = String::class.java
    override val filterSet = StandardColumnFilters.basic // Only supports basic equality, no compare operators
    override fun format(value: ID): String = value.toString()
    override fun parse(value: String): ID = EntityId.fromString(value, entityIdType)
}

/** Column type converting a value into a string using [Any.toString]. */
class StringifyColumnType<T>(private val stringParser: (String) -> T) : ColumnType<T, String> {
    override val valueType = String::class.java
    override fun format(value: T): String = value.toString()
    override fun parse(value: String): T = stringParser(value)
}

/** Column type converting an entity type class into its short class name using [Class.getSimpleName]. */
object EntityClassNameType : ReadOnlyColumnType<Class<*>, String> {
    override val valueType = String::class.java
    override val filterSet = StandardColumnFilters.basic // Only supports basic equality, no compare operators
    override fun format(value: Class<*>): String = value.simpleName
}

/** Column type looking up the discriminator value of an entity type using [DiscriminatorValue]. */
object EntityDiscriminatorValueType : ReadOnlyColumnType<Class<*>, String> {
    override val valueType = String::class.java
    override val filterSet = StandardColumnFilters.basic // Only supports basic equality, no compare operators
    override fun format(value: Class<*>): String {
        // Discriminator is either explicitly specified, or defaults to entity name.
        // Entity name is either explicitly specified, or defaults to entity class name.
        return value.getAnnotation(DiscriminatorValue::class.java)?.value
            ?: value.getAnnotation(Entity::class.java)?.name
            ?: value.simpleName
    }
}

/** Base class for simple value-based column types. */
abstract class BasicColumnType<T, M>(valueType: Class<M>? = null) : ColumnType<T, M> {
    @Suppress("UNCHECKED_CAST")
    override val valueType: Class<M> = valueType
        ?: javaClass.lookupSupertypeArgument<ColumnType<T, M>>(1) as Class<M>
}

/** Type representing [LocalDate]s in UTC timezone as an ISO string */
object IsoLocalDateType : BasicColumnType<LocalDate, String>() {
    override fun format(value: LocalDate): String = value.format(ISO_LOCAL_DATE)
    override fun parse(value: String): LocalDate = LocalDate.parse(value, ISO_LOCAL_DATE)
}

/** Type alias for [IsoLocalDateType], indicating the intended use for UTC dates */
typealias IsoUtcDateType = IsoLocalDateType

/** Type representing [LocalTime]s in UTC timezone as an ISO string */
object IsoLocalTimeType : BasicColumnType<LocalTime, String>() {
    override fun format(value: LocalTime): String = value.format(ISO_LOCAL_TIME)
    override fun parse(value: String): LocalTime = LocalTime.parse(value, ISO_LOCAL_TIME)
}

/** Type alias for [IsoLocalTimeType], indicating the intended use for UTC times */
typealias IsoUtcTimeType = IsoLocalTimeType

/** Type representing [LocalDateTime]s in UTC timezone as an ISO string */
object IsoLocalDateTimeType : BasicColumnType<LocalDateTime, String>() {
    override fun format(value: LocalDateTime): String = value.format(ISO_LOCAL_DATE_TIME)
    override fun parse(value: String): LocalDateTime = LocalDateTime.parse(value, ISO_LOCAL_DATE_TIME)
}

/** Type alias for [IsoLocalDateTimeType], indicating the intended use for UTC date times */
typealias IsoUtcDateTimeType = IsoLocalDateTimeType

/** Type representing [DataSize]s as a number of bytes */
object DataSizeType : BasicColumnType<DataSize, Long>() {
    override fun format(value: DataSize): Long = value.toBytes()
    override fun parse(value: Long): DataSize = DataSize.ofBytes(value)
}

/** Type representing [URI]s as strings */
object UriType : BasicColumnType<URI, String>() {
    override fun format(value: URI): String = value.toString()
    override fun parse(value: String): URI = URI.create(value)
    override val filterSet = StandardColumnFilters.basic // Only supports basic equality
}

object UuidType : BasicColumnType<UUID, String>() {
    override fun format(value: UUID): String = value.toString()
    override fun parse(value: String): UUID = UUID.fromString(value)
    override val filterSet = StandardColumnFilters.basic // Only supports basic equality
}

/** Type representing [BigDecimal]s as plain-decimal strings */
object BigDecimalType : BasicColumnType<BigDecimal, String>() {
    override fun format(value: BigDecimal): String = value.toPlainString()
    override fun parse(value: String) = BigDecimal(value)
}

/** Type representing [BigInteger]s as base-10 strings */
object BigIntegerType : BasicColumnType<BigInteger, String>() {
    override fun format(value: BigInteger): String = value.toString(10)
    override fun parse(value: String) = BigInteger(value, 10)
}

/** Type representing [Enum]s as strings using [Enum.name] */
class EnumColumnType<E : Enum<E>>(val enumType: Class<E>) : BasicColumnType<E, String>() {
    override fun format(value: E): String = value.name
    override fun parse(value: String): E = java.lang.Enum.valueOf(enumType, value)
    override val filterSet = StandardColumnFilters.basic // Only supports basic equality, no compare operators
}

/** [IdentityColumnType] for Strings that uses [StandardColumnFilters.patternMatching] as an extended [ColumnFilterSet] */
object LikeStringColumnType : IdentityColumnType<String>(String::class.java) {
    override val filterSet = StandardColumnFilters.patternMatching // Supports extended pattern matching operators
}
