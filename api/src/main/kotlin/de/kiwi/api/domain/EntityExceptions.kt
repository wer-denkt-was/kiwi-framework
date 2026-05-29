package de.kiwi.api.domain

/** Exception thrown when an entity for a given ID does not exist. */
data class EntityNotFound(val entityType: Class<*>, val entityId: String) : DomainException(
    status = Status.NotFound,
    code = "entity-not-found",
    args = mapOf("entityType" to entityType.name, "id" to entityId)
) {
    override val severity = Auditable.Severity.Disabled

    companion object {
        inline operator fun <reified E : Any> invoke(id: Any) = EntityNotFound(E::class.java, id.toString())
    }
}

/** Exception thrown when a method is not available for a given entity type. */
data class EntityMethodUnavailable(val entityType: Class<*>, val method: EntityMethod) : DomainException(
    status = Status.MethodNotAllowed,
    code = "entity-method-unavailable",
    args = mapOf("entityType" to entityType.name, "method" to method.name)
) {
    override val severity = Auditable.Severity.Disabled

    enum class EntityMethod { Query, Create, Update, Delete, Report }
}

/** Exception thrown when a non-nullable entity field is set to null. */
data class EntityFieldMissingValue(val entityType: Class<*>, val fieldName: String) : DomainException(
    status = Status.BadRequest,
    code = "entity-field-missing-value",
    args = mapOf("entityType" to entityType.name, "fieldName" to fieldName),
) {
    override val severity = Auditable.Severity.Disabled
}

/** Exception thrown when attempting to write to a read-only entity field. */
data class EntityFieldWriteNotAllowed(val entityType: Class<*>, val fieldName: String) : DomainException(
    status = Status.BadRequest,
    code = "entity-field-write-not-allowed",
    args = mapOf("entityType" to entityType.name, "fieldName" to fieldName),
) {
    override val severity = Auditable.Severity.Disabled
}

data class EntityFieldInvalid(val entityType: Class<*>, val fieldName: String, val reason: String) : DomainException(
    status = Status.BadRequest,
    code = "entity-field-invalid",
    args = mapOf("entityType" to entityType.name, "fieldName" to fieldName, "reason" to reason)
) {
    override val severity = Auditable.Severity.Disabled
}

/** Exception thrown when an entity could not be created due to mismatched/missing fields. */
data class EntityMismatchedInput(val entityType: Class<*>, val mismatchedFields: List<String>) : DomainException(
    status = Status.BadRequest,
    code = "entity-mismatched-input",
    args = mapOf("entityType" to entityType.name, "fields" to mismatchedFields)
) {
    override val severity = Auditable.Severity.Disabled
}
