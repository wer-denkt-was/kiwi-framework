package de.kiwi.api.generic

import de.kiwi.api.domain.BaseEntity
import de.kiwi.api.domain.DomainEvent
import de.kiwi.api.domain.EntityId
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.schema.Fieldset
import de.kiwi.api.schema.FieldsetsAvailable

/**
 * Event published when a new entity is created.
 * Usually published by [de.kiwi.api.application.generic.GenericCreateHandler].
 */
data class EntityCreated(
    val entityId: EntityId,
    val entityType: Class<*>,
    val createdDetails: Any?,
) : DomainEvent() {
    override val details = mapOf(
        "id" to entityId.toString(),
        "entityType" to entityType.name,
        "created" to createdDetails,
    )

    companion object {
        @JvmStatic
        fun <E : BaseEntity<*>> fromEntity(
            entity: E,
            fieldset: Fieldset<E>,
            user: UserDescriptor? = null,
        ) = fromEntity(entity = entity, createdDetails = fieldset.extract(entity, user))

        @JvmStatic
        @Deprecated(
            message = "Use a specific Fieldset instead of implicit default from FieldsetsAvailable",
            replaceWith = ReplaceWith("fromEntity(entity, fieldset = fieldsets.default, user = user)")
        )
        fun <E : BaseEntity<*>> fromEntity(
            entity: E,
            fieldsets: FieldsetsAvailable<E>,
        ) = fromEntity(entity = entity, createdDetails = fieldsets.entityInfo(entity))

        @JvmStatic
        fun <E : BaseEntity<*>> fromEntity(entity: E, createdDetails: Any?) =
            EntityCreated(entityId = entity.id, entityType = entity.javaClass, createdDetails = createdDetails)
    }
}

/**
 * Event published when an existing entity has been updated.
 * Usually published by [de.kiwi.api.application.generic.GenericUpdateHandler].
 */
data class EntityUpdated(
    val entityId: EntityId,
    val entityType: Class<*>,
    val beforeDetails: Any?,
    val afterDetails: Any?,
) : DomainEvent() {
    override val details = mapOf(
        "id" to entityId.toString(),
        "entityType" to entityType.name,
        "before" to beforeDetails,
        "after" to afterDetails,
    )

    companion object {
        @JvmStatic
        fun <E : BaseEntity<*>> fromEntity(entity: E, beforeAndAfter: Pair<*, *>) =
            EntityUpdated(
                entityId = entity.id,
                entityType = entity.javaClass,
                beforeDetails = beforeAndAfter.first,
                afterDetails = beforeAndAfter.second,
            )
    }
}

/**
 * Event published when a formerly existing entity has been deleted.
 * Usually published by [de.kiwi.api.application.generic.GenericDeleteHandler].
 */
data class EntityDeleted(
    val entityId: EntityId,
    val entityType: Class<*>,
    val deletedDetails: Any?,
) : DomainEvent() {
    override val details = mapOf(
        "id" to entityId.toString(),
        "entityType" to entityType.name,
        "deleted" to deletedDetails,
    )

    companion object {
        @JvmStatic
        fun <E : BaseEntity<*>> fromEntity(entity: E, fieldset: Fieldset<E>, user: UserDescriptor? = null) =
            fromEntity(entity = entity, deletedDetails = fieldset.extract(entity, user))

        @JvmStatic
        fun <E : BaseEntity<*>> fromEntity(entity: E, deletedDetails: Any?) =
            EntityDeleted(entityId = entity.id, entityType = entity.javaClass, deletedDetails = deletedDetails)
    }
}
