package de.kiwi.api.application.generic

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.kiwi.api.domain.*
import de.kiwi.api.generic.EntityUpdated
import de.kiwi.api.generic.GenericUpdate
import de.kiwi.api.generic.parseFromJson
import de.kiwi.api.schema.ColumnField
import org.springframework.beans.factory.annotation.Autowired

/**
 * Abstract base class for handling [GenericUpdate] commands.
 *
 * __Example__:
 * ```kt
 * class UpdateExample : UpdateExample<ExampleEntity, ExampleId>(ExampleSchema) { ... }
 *
 * @Component
 * class UpdateExampleHandler : GenericUpdateHandler<UpdateExample, ExampleEntity>() {
 *   override fun updateEntity(update: UpdateBody, entity: ExampleEntity) = DomainResult {
 *     update.patchField(ExampleSchema.name)
 *     update.patchField(ExampleSchema.optional)
 *     // More patch logic and record update events here
 *   }
 * }
 * ```
 */
abstract class GenericUpdateHandler<GU : GenericUpdate<E, *>, E : Any> : GenericModificationHandler<GU, E>() {

    @Autowired
    lateinit var objectMapper: ObjectMapper

    override fun commandEntityId(command: GU): EntityId = command.entityId

    override fun handle(command: GU, user: UserDescriptor) = DomainResult { record ->
        val entityId = command.entityId
        val entityClass = command.entityFieldset.schema.entityClass

        val entity = loadEntity(command, entityClass, user)
            ?: throw EntityNotFound(entityClass, entityId.toString())

        val update = UpdateBody(command, entity)
        val beforeDetails = command.entityDetails(entity, user)
        record(updateEntity(update, entity, user))

        val updatedEntity = entityManager.merge(entity)
        val afterDetails = command.entityDetails(updatedEntity, user)

        record(
            EntityUpdated(
                entityId = entityId,
                entityType = entityClass,
                beforeDetails = beforeDetails,
                afterDetails = afterDetails,
            )
        )

        return@DomainResult afterDetails
    }

    /**
     * Update the given entity instance using the data present in this command.
     * The changes applied to the entity will be persisted after this method returns.
     */
    protected abstract fun updateEntity(update: UpdateBody, entity: E, user: UserDescriptor): DomainResult<*>

    /**
     * Wrapper for the JSON request body of a [GenericUpdate] command, providing convenient accessors
     * for extracting and validating entity field values from the request, and patching entity fields.
     *
     * The [UpdateBody] class offers type-safe [get], [require], and [patch]/[patchNotNull]/[patchField] functions to
     * retrieve or update values by [ColumnField] or field name, automatically handling deserialization,
     * validation, and missing value errors.
     *
     * Usage:
     * ```kotlin
     * val name: String? = updateBody[ExampleSchema.name]
     * val custom: Int = updateBody.require<Int>("customField")
     * updateBody.patchField(ExampleSchema.name) // Directly patches Example.name field
     * updateBody.patch(ExampleSchema.something) { valueOrNull -> ... } // Invokes callback if value is present or null
     * updateBody.patchNotNull(ExampleSchema.something) { value -> ... } // Invokes callback if value is present and not null
     * ```
     */
    protected inner class UpdateBody(val command: GU, val entity: E) {
        /**
         * Retrieve the value for the given [ColumnField] from the request body, or null if not present.
         * This method automatically applies [auto validation][de.kiwi.api.generic.ColumnAutoValidation] if defined for the column.
         * @see de.kiwi.api.generic.ColumnAutoValidation
         */
        operator fun <T, M> get(column: ColumnField<E, T, M>): T? {
            return column.parseFromJson(command.jsonBody, objectMapper)
        }

        /**
         * Retrieve the raw [JsonNode] for the given field name from the request body, or null if not present.
         */
        operator fun get(fieldName: String): JsonNode? =
            command.jsonBody[fieldName]

        /**
         * Retrieve the value for the given field name, deserialized as [valueType], or null if not present.
         */
        fun <T> get(fieldName: String, valueType: Class<T>): T? {
            val node = command.jsonBody[fieldName] ?: return null
            return objectMapper.treeToValue(node, valueType)
        }

        /**
         * Retrieve the value for the given field name, deserialized as type [T], or null if not present.
         */
        inline operator fun <reified T> get(fieldName: String): T? =
            get(fieldName, T::class.java)

        /**
         * Check if the request body contains a value (possibly null) for the given [ColumnField].
         */
        operator fun contains(column: ColumnField<E, *, *>): Boolean {
            return command.jsonBody.has(column.fieldName)
        }

        /**
         * If the request body contains the given [ColumnField], apply the given function with its value (which may be null).
         */
        fun <T, M> patch(column: ColumnField<E, T, M>, apply: (T?) -> Unit) {
            if (contains(column)) {
                apply(get(column))
            }
        }

        /**
         * If the request body contains the given [ColumnField], apply the given function with its non-null value.
         * If the value is null and [ignoreNull] is false, throws [EntityFieldMissingValue].
         * If the value is null and [ignoreNull] is true, the given function is not applied and nothing happens.
         */
        fun <T, M> patchNotNull(
            column: ColumnField<E, T, M>,
            ignoreNull: Boolean = false,
            apply: (T & Any) -> Unit,
        ) {
            patch(column) { value ->
                if (value != null) apply(value)
                else if (!ignoreNull) missingField(column.fieldName)
            }
        }

        /**
         * If the request body contains the given [ColumnField],
         * parse and write its value directly to the entity using [ColumnField.writeToEntity].
         * This method automatically applies [de.kiwi.api.generic.ColumnAutoValidation] if defined for the column.
         * @see ColumnField.writeToEntity
         * @see de.kiwi.api.generic.ColumnAutoValidation
         */
        fun <T, M> patchField(column: ColumnField<E, T, M>) {
            if (contains(column)) {
                val value = column.parseFromJson(command.jsonBody, objectMapper)
                column.writeToEntity(entity, value)
            }
        }

        /**
         * Retrieve the value for the given field name, deserialized as type [T], or throw if missing.
         * @throws EntityFieldMissingValue if the value is missing
         */
        inline fun <reified T> require(fieldName: String): T =
            require(fieldName, T::class.java)

        /**
         * Retrieve the value for the given field name, deserialized as [valueType], or throw if missing.
         * @throws EntityFieldMissingValue if the value is missing
         */
        fun <T> require(fieldName: String, valueType: Class<T>): T =
            get(fieldName, valueType) ?: missingField(fieldName)

        private fun missingField(fieldName: String): Nothing =
            throw EntityFieldMissingValue(command.entityFieldset.schema.entityClass, fieldName)
    }
}
