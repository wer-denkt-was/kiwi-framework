package de.kiwi.api.application.generic

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.kiwi.api.application.command.CommandHandler
import de.kiwi.api.domain.DomainResult
import de.kiwi.api.domain.EntityFieldMissingValue
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.generic.EntityCreated
import de.kiwi.api.generic.GenericCreate
import de.kiwi.api.generic.parseFromJson
import de.kiwi.api.schema.ColumnField
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.beans.factory.annotation.Autowired

/**
 * Abstract base class for handling [GenericCreate] commands.
 *
 * __Example__:
 * ```kt
 * class CreateExample : GenericCreate<ExampleEntity>() {
 *   override val entityFieldset = ExampleFieldsets.default
 *   override fun entityId(entity: ExampleEntity) = entity.id
 *   override fun preAuthorize(user: UserDescriptor) = TODO("Authorize")
 * }
 *
 * @Component
 * class CreateExampleHandler : GenericCreateHandler<CreateExample, ExampleEntity>() {
 *   override fun createEntity(create: CreateBody, user: UserDescriptor): DomainResult<ExampleEntity> {
 *       return ExampleEntity.create(
 *         name = create.require(ExampleSchema.name),
 *         optional = create.get(ExampleSchema.optional),
 *     )
 *   }
 * }
 * ```
 */
abstract class GenericCreateHandler<GC : GenericCreate<E>, E : Any> : CommandHandler<GC, JsonNode> {

    @PersistenceContext
    lateinit var entityManager: EntityManager

    @Autowired
    lateinit var objectMapper: ObjectMapper

    override fun handle(command: GC, user: UserDescriptor) = DomainResult { record ->
        val body = CreateBody(command)
        val detachedEntity = record(createEntity(body, user))
        val persistentEntity = entityManager.merge(detachedEntity)
        record(afterEntityCreated(persistentEntity, body))

        val id = command.entityId(persistentEntity)
        val createdDetails = command.entityDetails(persistentEntity, user)
        record(
            EntityCreated(
                entityId = id,
                entityType = persistentEntity.javaClass,
                createdDetails = createdDetails
            )
        )

        return@DomainResult createdDetails
    }

    /**
     * Create a new entity instance using the data present in this command, and the user submitting the command.
     * The entity wrapped in the returned [DomainResult] will be persisted after this method returns.
     */
    protected abstract fun createEntity(create: CreateBody, user: UserDescriptor): DomainResult<E>

    /**
     * Optionally override this method for post-creation entity handling.
     * The given entity will already be persistent.
     */
    protected open fun afterEntityCreated(created: E, create: CreateBody): DomainResult<*> = DomainResult.empty

    /**
     * Wrapper for the JSON request body of a [GenericCreate] command,
     * providing convenient accessors for extracting and validating entity field values from the request.
     *
     * The [CreateBody] class offers type-safe [get] and [require] functions to retrieve values by
     * [ColumnField] or field name, automatically handling deserialization, auto-validation, and missing value errors.
     *
     * Usage:
     * ```kotlin
     * val name: String = createBody.require(ExampleSchema.name)
     * val optional: String? = createBody[ExampleSchema.optional]
     * val custom: Int = createBody.require<Int>("customField")
     * ```
     */
    inner class CreateBody(val command: GC) {
        /**
         * Retrieve the value for the given [ColumnField] from the request body, or null if not present.
         * This method automatically applies [auto validation][de.kiwi.api.generic.ColumnAutoValidation] if defined for the column.
         * @see de.kiwi.api.generic.ColumnAutoValidation
         */
        operator fun <T, M> get(column: ColumnField<E, T, M>): T? =
            column.parseFromJson(command.jsonBody, objectMapper)

        /**
         * Retrieve the raw [JsonNode] for the given field name from the request body, or null if not present.
         */
        operator fun get(fieldName: String): JsonNode? =
            command.jsonBody[fieldName]

        /**
         * Retrieve the value for the given field name, deserialized as type [T], or null if not present.
         */
        inline operator fun <reified T> get(fieldName: String): T? =
            get(fieldName, T::class.java)

        /**
         * Retrieve the value for the given field name, deserialized as [valueType], or null if not present.
         */
        fun <T> get(fieldName: String, valueType: Class<T>): T? {
            val node = command.jsonBody[fieldName] ?: return null
            return objectMapper.treeToValue(node, valueType)
        }

        /**
         * Retrieve the value for the given [ColumnField] from the request body, or throw if missing.
         * This method automatically applies [auto validation][de.kiwi.api.generic.ColumnAutoValidation] if defined for the column.
         * @throws EntityFieldMissingValue if the value is missing
         * @see de.kiwi.api.generic.ColumnAutoValidation
         */
        fun <T, M> require(column: ColumnField<E, T, M>): T =
            get(column) ?: missingField(column.fieldName)

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
