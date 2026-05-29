package de.kiwi.api.generic

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.kiwi.api.domain.*
import de.kiwi.api.schema.*

/**
 * Abstract base [Command] for handling entity commands using an [EntityFieldset] for representing entity details.
 */
abstract class GenericEntityCommand<E : Any> : Command<JsonNode>(), Authorized {

    abstract val entityFieldset: EntityFieldset<E>
}

/**
 * Abstract [Command] for handling generic HTTP POST requests to create an entity,
 * using an [EntityFieldset] for retrieving values from the created entity.
 * Provides a [jsonBody] containing the HTTP POST JSON request body, to be used as entity creation data.
 *
 * **Note**: Subclasses *must* provide a no-argument constructor!
 *
 * Example:
 * ```kt
 * class CreateExample : GenericCreate<ExampleEntity>(ExampleSchema) {
 *   override val entityFieldset = ExampleFieldsets.default
 *   override fun entityId(entity: ExampleEntity) = entity.id
 *   override fun preAuthorize(user: UserDescriptor) = TODO("Authorize command")
 * }
 * ```
 * @param E Entity type to be created
 * @see de.kiwi.api.application.generic.GenericCreateHandler
 */
abstract class GenericCreate<E : Any> : GenericEntityCommand<E>() {
    lateinit var jsonBody: JsonNode

    abstract fun entityId(entity: E): EntityId

    open fun entityDetails(entity: E, user: UserDescriptor): JsonNode = entityFieldset.extractJson(entity, user)
}

/**
 * Abstract [Command] for handling generic HTTP PUT requests to update entities,
 * using an [EntityFieldset] for retrieving values from the updated entity.
 * Provides a [jsonBody] containing the HTTP PUT JSON request body, to be used as entity update data.
 *
 * **Note**: Subclasses *must* provide a no-argument constructor!
 *
 * Example:
 * ```kt
 * class UpdateExample : GenericUpdate<ExampleEntity, ExampleId>() {
 *   override val entityFieldset = ExampleFieldsets.default
 *   override fun preAuthorize(user: UserDescriptor) = TODO("Authorize command")
 * }
 * ```
 * @param E Entity type to be updated
 * @param ID EntityId type of entity to be updated
 * @see de.kiwi.api.application.generic.GenericUpdateHandler
 */
abstract class GenericUpdate<E : Any, ID : EntityId> : GenericEntityCommand<E>() {
    lateinit var entityId: ID
    lateinit var jsonBody: JsonNode

    open fun entityDetails(entity: E, user: UserDescriptor): JsonNode = entityFieldset.extractJson(entity, user)
}

/**
 * Abstract [Command] for handling generic HTTP DELETE requests to delete entities,
 * using an [EntityFieldset] for retrieving details from the deleted entity.
 *
 * **Note**: Subclasses must provide a no-argument constructor!
 *
 * Example:
 * ```kt
 * class DeleteExample : GenericDelete<ExampleEntity, ExampleId>() {
 *   override val entityFieldset = ExampleFieldsets.default
 *   override fun preAuthorize(user: UserDescriptor) = TODO("Authorize command")
 * }
 * ```
 * @param E Entity type to be deleted
 * @param ID EntityId type of entity to be deleted
 * @see de.kiwi.api.application.generic.GenericDeleteHandler
 */
abstract class GenericDelete<E : Any, ID : EntityId> : GenericEntityCommand<E>() {
    lateinit var entityId: ID

    open fun entityDetails(entity: E, user: UserDescriptor): JsonNode = entityFieldset.extractJson(entity, user)
}

// --- Internal command utilities ---

internal fun <E : Any, T, M> ColumnField<E, T, M>.parseFromJson(json: JsonNode, mapper: ObjectMapper): T? {
    return json.get(fieldName)
        ?.let { node -> mapper.treeToValue(node, type.valueType) }
        ?.let { value -> parseAndValidateColumnValue(value) }
}

private fun <E : Any, T, M> ColumnField<E, T, M>.parseAndValidateColumnValue(formattedValue: M & Any): T {
    try {
        // Auto validation only applies to attribute selectors
        @Suppress("UNCHECKED_CAST") val attributeSelector = selector as? ColumnSelector.AttributeSelector<*, T & Any>

        // Lookup auto validation associated with last attribute in the selector path
        val attribute = attributeSelector?.path?.last
        val autoValidation = attribute?.let { ColumnAutoValidation.lookupAutoValidation(it) }

        // Apply auto-validation if available, otherwise use column type as-is
        val validatedType = autoValidation?.let { type.validate(it) } ?: type
        return validatedType.parse(formattedValue)
    } catch (_: ColumnType.ParseNotSupported) {
        throw EntityFieldWriteNotAllowed(parentSchema.entityClass, fieldName)
    } catch (ex: ValidationFailed) {
        throw EntityFieldInvalid(parentSchema.entityClass, fieldName, ex.reason)
    }
}
