package de.kiwi.api.application.service

import de.kiwi.api.generic.GenericEntityFilter
import de.kiwi.api.schema.EntitySchema
import de.kiwi.api.schema.FieldsetsAvailable

/**
 * Service providing access to entity schemata, filters and available fieldsets by entity type.
 */
interface QuerySchemaProvider {
    /**
     * List all available [EntitySchema] instances
     */
    val schemas: List<EntitySchema<*>>

    /**
     * List all available JPA entity classes that are associated with [EntitySchema] instances
     */
    val schemaEntityClasses get() = schemas.map { it.entityClass }

    /**
     * Find the declared [EntitySchema] associated with the given JPA entity class.
     */
    fun <E : Any> findEntitySchema(entityType: Class<E>): EntitySchema<E>?

    /**
     * Find the class of [GenericEntityFilter] associated with the given JPA entity class.
     */
    fun <E : Any> findFilterType(entityType: Class<E>): Class<out GenericEntityFilter<E>>?

    /**
     * Look up declared [FieldsetsAvailable] associated with the schema of the given JPA entity.
     */
    fun <E : Any> findAvailableFieldsets(entityType: Class<E>): FieldsetsAvailable<E>?
}
