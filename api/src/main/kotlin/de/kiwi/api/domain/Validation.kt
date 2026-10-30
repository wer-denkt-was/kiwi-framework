package de.kiwi.api.domain

import jakarta.persistence.Column
import org.springframework.core.annotation.AnnotationUtils
import java.util.regex.Pattern
import kotlin.reflect.KProperty
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaGetter

/**
 * Interface validating a given value, returning a validated value result
 * or an invalid result with a reason.
 */
fun interface Validation<T> {
    /**
     * Validate the given value, and return either a successful validation
     * or a reason why the given value is invalid.
     */
    fun validate(value: T): Result<T>

    /**
     * Optional Kotlin property this validation is **associated** with.
     * This is used for automatic association of validations with [de.kiwi.api.schema.ColumnField]s.
     * @see KProperty.validateColumn
     * @see de.kiwi.api.generic.ColumnAutoValidation
     */
    fun associatedProperty(): KProperty<*>? = null

    /**
     * Apply the validation to the given value and return the validated result value if the validation succeeds,
     * or throw an [ValidationFailed] with the failure reason as exception message if validation fails.
     */
    operator fun invoke(value: T): T =
        validate(value).getOrElse { throw ValidationFailed(this, it, value) }

    /**
     * The result of a validation: Either a [Result.Valid] validation success with a value,
     * or a [Result.Invalid] validation failure with a failure reason.
     */
    sealed interface Result<out T> {
        /** True if this result is [Valid], false if [Invalid] */
        val isValid: Boolean

        /** Get the value of this result if valid, or evaluate the given function if invalid. */
        fun getOrElse(orElse: (reason: String) -> Nothing): T

        /** Fold the value of this result if valid, or propagate the reason if invalid. */
        fun <R> fold(reduction: (T) -> Result<R>): Result<R>

        data class Valid<T>(val value: T) : Result<T> {
            override val isValid = true
            override fun getOrElse(orElse: (String) -> Nothing): T = value
            override fun <R> fold(reduction: (T) -> Result<R>) = reduction(value)
        }

        data class Invalid<T>(val reason: String) : Result<T> {
            override val isValid = false
            override fun getOrElse(orElse: (String) -> Nothing): T = orElse(reason)
            override fun <R> fold(reduction: (T) -> Result<R>) = Invalid<R>(reason)
        }
    }

    /**
     * Common validations
     */
    companion object Validators {
        /**
         * Create a validation performing an assertion on the value to be validated.
         * If the assertion fails, a reason for the failure is evaluated and an invalid result is returned.
         */
        fun <T> assert(reason: String, condition: (T) -> Boolean) = Validation<T> { value ->
            if (condition(value)) Result.Valid(value) else Result.Invalid(reason)
        }

        /** Create a validation applying a mapping function on the value to be validated. */
        fun <T> map(mapper: (T) -> T) = Validation<T> { Result.Valid(mapper(it)) }

        /** Validate the given string is not empty using [String.isNullOrEmpty] */
        val notEmpty = assert<String>("not-empty") { it.isNotEmpty() }

        /** Validate the given string is not blank using [String.isNullOrBlank] */
        val notBlank = assert<String>("not-blank") { it.isNotBlank() }

        /** Validate the given string is a valid e-mail using a regex pattern */
        val validEmail = assert<String>("invalid-email") { it.matches(emailRegex) }

        /** Convert the given string to uppercase */
        val toUppercase = map<String> { it.uppercase() }

        /** Convert the given string to lowercase */
        val toLowercase = map<String> { it.lowercase() }

        /** Validate the given string does not exceed a maximum length. */
        fun maxLength(max: Int) = assert<String>("too-long") { it.length <= max }

        /** Validate the given string matches the given regex */
        fun matchesRegex(reason: String, regex: Regex) = assert<String>(reason) { regex.matches(it) }

        /** Wrap a validation that allows null values to pass through as valid */
        fun <T> allowNull(delegate: Validation<T>): Validation<T?> {
            return delegate.derive { value ->
                if (value == null) Result.Valid(null) else delegate.validate(value)
            }
        }

        /** Wrap a validation that considers null values to be invalid */
        fun <T> notNull(delegate: Validation<T>, reason: String = "not-null"): Validation<T?> {
            return delegate.derive<T?> { value ->
                if (value == null) Result.Invalid(reason) else delegate.validate(value)
            }
        }

        /** Validation that returns a valid null if the given condition matches */
        fun <T> nullIf(condition: (T & Any) -> Boolean) = map<T?> { value -> value?.takeUnless { condition(it) } }

        /** Return a valid null if the string value is blank */
        val nullIfBlank = nullIf<String> { it.isBlank() }

        /** Return a valid null if the string value is empty */
        val nullIfEmpty = nullIf<String> { it.isEmpty() }

        /** Return a valid null if the integer value is zero or negative */
        val nullUnlessPositive = nullIf<Int> { it <= 0 }

        /** Validation that always returns the given value as valid */
        fun <T> alwaysValid() = Validation<T> { Result.Valid(it) }

        /** Validation that always returns invalid with the given reason */
        fun <T> alwaysInvalid(reason: String) = Validation<T> { Result.Invalid(reason) }

        /** Create a valid result with the given value */
        fun <T> valid(value: T) = Result.Valid(value)

        /** Create an invalid result with the given reason */
        fun <T> invalid(reason: String) = Result.Invalid<T>(reason)
    }
}

/**
 * Pipeline two validators, such that `(A -> B) and (B -> C)` creates `(A -> C)`.
 * This propagates the validation's [associatedProperty][Validation.associatedProperty].
 */
infix fun <T> Validation<T>.and(next: Validation<T>) = derive<T> { value ->
    validate(value).fold { nextValue -> next.validate(nextValue) }
}

/** Create a validation that does not allow null values, or delegates validation otherwise */
val <T> Validation<T>.notNull get() = Validation.notNull(this)

/** Create a validation that allows null values, or delegates validation otherwise */
val <T> Validation<T>.orNull get() = Validation.allowNull(this)

/**
 * Derive a validation from another validation, carrying over the associated property
 */
private fun <T> Validation<*>.derive(delegate: Validation<T>): Validation<T> {
    val original = this
    return object : Validation<T> {
        override fun associatedProperty() = original.associatedProperty() ?: delegate.associatedProperty()
        override fun validate(value: T): Validation.Result<T> = delegate.validate(value)
    }
}

private class AssociatedValidation<T>(
    private val property: KProperty<*>?,
    private val delegate: Validation<T>,
) : Validation<T> by delegate {
    override fun associatedProperty() = property ?: delegate.associatedProperty()
}

/**
 * DomainException thrown if a [Validation] fails when using the [Validation.invoke] operator.
 */
data class ValidationFailed(val validation: Validation<*>, val reason: String, val value: Any?) : DomainException(
    status = Status.BadRequest,
    code = "validation-failed",
    args = mapOf(
        "associatedField" to validation.associatedProperty()?.name,
        "reason" to reason,
        "value" to value,
    )
) {
    override val severity = Auditable.Severity.Disabled
}

/**
 * Create a [Validation] that checks if the value adheres to the constraints
 * imposed by the JPA [@Column][Column] annotation present on the receiver property.
 *
 * The resulting validation will be **associated** with the name of the receiver property,
 * so a lookup in [de.kiwi.api.generic.ColumnAutoValidation] will find the validation property based on this
 * column validation.
 *
 * __Example:__
 * ```kt
 * // Declare entity class with column property
 * @Entity
 * class Example(
 *   @Column(name = "foo", nullable = false, length = 64) val foo: String
 * ) {
 *   companion object {
 *     // Validation will fail on null, or when length exceeds 64.
 *     // This validation is now associated with the field "foo", and will
 *     // automatically be added to a ColumnType when applicable.
 *     val validFoo = Example::foo.validateColumn()
 *   }
 * }
 * ```
 */
fun <T> KProperty<T>.validateColumn(): Validation<T> {
    // Look up column annotation, or pass through value if no annotation exists
    val column = javaField?.let { field -> AnnotationUtils.findAnnotation(field, Column::class.java) }
        ?: javaGetter?.let { method -> AnnotationUtils.findAnnotation(method, Column::class.java) }
        ?: return AssociatedValidation(property = this, delegate = Validation.alwaysValid())

    val maxLength = columnMaxLength(column)
    val notNullable = !column.nullable || !returnType.isMarkedNullable

    // Validation depends on property type
    val validation = Validation<T> { value ->
        when (value) {
            // Check column/property nullability
            null -> {
                if (notNullable) Validation.invalid("not-null")
                else Validation.valid<T>(value)
            }
            // Check max string length of column
            is String -> {
                if (value.length > maxLength) Validation.invalid("too-long")
                else Validation.valid<T>(value)
            }
            // Nothing else to check intrinsically
            else -> Validation.valid<T>(value)
        }
    }

    // Return validation associated with the property field name
    return AssociatedValidation(property = this, validation)
}

private fun columnMaxLength(column: Column): Long = column
    .columnDefinition
    .takeIf { it.isNotBlank() }
    ?.let { columnDefinitionLength(it) }
    ?: column.length.toLong()

private fun columnDefinitionLength(definition: String): Long? = when (definition.lowercase()) {
    "tinytext" -> 0xFFL // 255 Byte, 1 byte length
    "text", "blob" -> 0xFF_FFL // 64 KB, 2 byte length
    "mediumtext", "mediumblob" -> 0xFF_FF_FFL // 16 MB, 3 byte length
    "longtext", "longblob", "json" -> 0xFF_FF_FF_FFL // 4 GB, 4 byte length
    else -> null
}

/**
 * E-Mail validation regex sourced from [emailregex.com](https://emailregex.com/index.html).
 */
private val emailRegex = Pattern.compile(
    "(?:[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*|\"(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21\\x23-\\x5b\\x5d-\\x7f]|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])*\")@(?:(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?|\\[(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?|[a-z0-9-]*[a-z0-9]:(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21-\\x5a\\x53-\\x7f]|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])+)])",
    Pattern.CASE_INSENSITIVE or Pattern.CANON_EQ or Pattern.UNICODE_CASE,
).toRegex()
