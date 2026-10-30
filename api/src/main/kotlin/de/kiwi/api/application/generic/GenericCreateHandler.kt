package de.kiwi.api.application.generic

import de.kiwi.api.application.command.CommandHandler
import de.kiwi.api.domain.DomainResult
import de.kiwi.api.domain.PersistableBaseEntity
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.generic.EntityCreated
import de.kiwi.api.generic.GenericCreate
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import tools.jackson.databind.JsonNode

/**
 * Abstract base class for handling [GenericCreate] commands.
 * The command is bound to the JSON request body before it is handled, so its body properties can be read directly.
 *
 * __Example__:
 * ```kt
 * class CreateExample : GenericCreate<ExampleEntity>() {
 *   override val detailsProvider = ExampleFieldsets.default
 *   val name by schema(ExampleSchema.name)
 *   val optional by schema(ExampleSchema.optional)
 *   override fun preAuthorize(user: UserDescriptor) = TODO("Authorize")
 * }
 *
 * @Component
 * class CreateExampleHandler : GenericCreateHandler<CreateExample, ExampleEntity>() {
 *   override fun createEntity(create: CreateExample, user: UserDescriptor): DomainResult<ExampleEntity> {
 *     return ExampleEntity.create(name = create.name, optional = create.optional)
 *   }
 * }
 * ```
 */
abstract class GenericCreateHandler<GC : GenericCreate<E>, E : Any> : CommandHandler<GC, JsonNode> {

    @PersistenceContext
    lateinit var entityManager: EntityManager

    override fun handle(command: GC, user: UserDescriptor) = DomainResult { record ->
        val detachedEntity = record(createEntity(command, user))
        val persistentEntity = persistEntity(detachedEntity)
        record(afterEntityCreated(persistentEntity, command, user))

        val id = command.entityId(persistentEntity)
        val createdDetails = command.detailsProvider.toJsonDetails(persistentEntity, user)
        record(
            EntityCreated(
                entityId = id,
                entityType = persistentEntity.javaClass,
                createdDetails = createdDetails,
            ),
        )

        return@DomainResult createdDetails
    }

    /**
     * Persist the entity returned by [createEntity], returning the managed entity instance.
     * New [PersistableBaseEntity] instances (see [PersistableBaseEntity.isNew]) are persisted using
     * [EntityManager.persist], inserting them without a prior `SELECT`. All other entities are merged into the
     * persistence context using [EntityManager.merge], which returns a managed copy of the entity.
     */
    protected open fun persistEntity(detachedEntity: E): E = when (detachedEntity) {
        is PersistableBaseEntity<*> if (detachedEntity.isNew) -> {
            entityManager.persist(detachedEntity)
            detachedEntity
        }

        else -> entityManager.merge(detachedEntity)
    }

    /**
     * Create a new (detached) entity from the [create] command.
     * The entity is persisted afterward using [persistEntity].
     */
    protected abstract fun createEntity(create: GC, user: UserDescriptor): DomainResult<E>

    /** Overridable method called with the persisted entity, after it has been created. */
    protected open fun afterEntityCreated(created: E, create: GC, user: UserDescriptor): DomainResult<*> =
        DomainResult.empty
}
