package de.kiwi.api.application.generic

import de.kiwi.api.domain.DomainResult
import de.kiwi.api.domain.EntityId
import de.kiwi.api.domain.EntityNotFound
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.generic.EntityDeleted
import de.kiwi.api.generic.GenericDelete

/**
 * Abstract base class for handling [GenericDelete] commands.
 *
 * __Example__:
 * ```kt
 * class DeleteExample : GenericDelete<ExampleEntity, EntityId>(ExampleSchema) { ... }
 *
 * @Component
 * class DeleteExampleHandler : GenericDeleteHandler<DeleteExample, ExampleEntity>() {
 *   override fun beforeDeleteEntity(delete: DeleteExample, entity: ExampleEntity) = DomainEvents {
 *     // Record deletion events here
 *   }
 * }
 * ```
 */
abstract class GenericDeleteHandler<GD : GenericDelete<E, *>, E : Any> : GenericModificationHandler<GD, E>() {

    override fun commandEntityId(command: GD): EntityId = command.entityId

    override fun handle(command: GD, user: UserDescriptor) = DomainResult { record ->
        val entityId = command.entityId
        val entityClass = command.entityFieldset.schema.entityClass

        val entity = loadEntity(command, entityClass, user)
            ?: throw EntityNotFound(entityClass, entityId.toString())

        val deletedDetails = command.entityDetails(entity, user)
        record(beforeDeleteEntity(command, entity, user))
        entityManager.remove(entity)

        record(
            EntityDeleted(
                entityId = entityId,
                entityType = entityClass,
                deletedDetails = deletedDetails,
            )
        )

        return@DomainResult deletedDetails
    }

    /**
     * Perform operations on the given entity instance before deletion.
     * The changes applied to related entities will be persisted after this method returns.
     */
    abstract fun beforeDeleteEntity(delete: GD, entity: E, user: UserDescriptor): DomainResult<*>
}
