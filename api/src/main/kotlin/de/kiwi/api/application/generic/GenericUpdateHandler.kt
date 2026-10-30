package de.kiwi.api.application.generic

import de.kiwi.api.domain.*
import de.kiwi.api.generic.EntityUpdated
import de.kiwi.api.generic.GenericUpdate

/**
 * Abstract base class for handling [GenericUpdate] commands.
 * The command is bound to the JSON request body before it is handled; only the body properties present in the JSON
 * body are patched.
 *
 * __Example__:
 * ```kt
 * class UpdateExample : GenericUpdate<ExampleEntity, ExampleId>() {
 *   override val detailsProvider = ExampleFieldsets.default
 *   val name by schema(ExampleSchema.name)
 *   val order by schema(ExampleSchema.order)
 *   override fun preAuthorize(user: UserDescriptor) = TODO("Authorize")
 * }
 *
 * @Component
 * class UpdateExampleHandler : GenericUpdateHandler<UpdateExample, ExampleEntity>() {
 *   override fun updateEntity(update: UpdateExample, entity: ExampleEntity, user: UserDescriptor) = DomainResult {
 *     update.patchFields(entity, update::name)
 *     update.ifPatched(update::order) { order -> entity.moveTo(order) }
 *     // More patch logic and record update events here
 *   }
 * }
 * ```
 */
abstract class GenericUpdateHandler<GU : GenericUpdate<E, *>, E : Any> : GenericModificationHandler<GU, E>() {

    override fun commandEntityId(command: GU): EntityId = command.entityId

    override fun handle(command: GU, user: UserDescriptor) = DomainResult { record ->
        val entityId = command.entityId
        val entityClass = command.entityClass

        val entity = loadEntity(command, entityClass, user)
            ?: throw EntityNotFound(entityClass, entityId.toString())

        val beforeDetails = command.detailsProvider.toJsonDetails(entity, user)
        record(updateEntity(command, entity, user))

        val updatedEntity = entityManager.merge(entity)
        val afterDetails = command.detailsProvider.toJsonDetails(updatedEntity, user)

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
     * Apply the patched body properties of the [update] command to the loaded [entity].
     * @see GenericUpdate.ifPatched
     * @see GenericUpdate.patchFields
     */
    protected abstract fun updateEntity(update: GU, entity: E, user: UserDescriptor): DomainResult<*>
}
