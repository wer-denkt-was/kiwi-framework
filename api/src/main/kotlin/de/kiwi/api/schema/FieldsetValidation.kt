package de.kiwi.api.schema

import de.kiwi.api.domain.DomainException
import de.kiwi.api.domain.Validation

fun interface FieldsetValidation : Validation<Fieldset<*>> {
    val order get() = 0

    companion object {
        internal val valid = FieldsetValidation { fieldset ->
            Validation.valid(fieldset)
        }
    }
}

internal infix fun FieldsetValidation.and(other: FieldsetValidation) = FieldsetValidation { fieldset ->
    validate(fieldset).fold { result -> other.validate(result) }
}

class FieldsetValidationService(validators: List<FieldsetValidation>) {
    private val validateAll = validators
        .sortedBy { it.order }
        .fold(FieldsetValidation.valid) { a, b -> a and b }

    @Throws(InvalidFieldset::class)
    fun assertFieldsetValid(fieldset: Fieldset<*>) {
        validateFieldset(fieldset).getOrElse { reason -> throw InvalidFieldset(fieldset, reason) }
    }

    fun validateFieldset(fieldset: Fieldset<*>): Validation.Result<Fieldset<*>> {
        return validateAll.validate(fieldset)
    }
}

data class InvalidFieldset(val fieldset: Fieldset<*>, val reason: String) : DomainException(
    status = Status.BadRequest,
    code = "invalid-fieldset",
    args = mapOf(
        "reason" to reason,
        "fieldsetName" to fieldset.name,
        "fieldsetInfo" to fieldset.toString(),
    )
)
