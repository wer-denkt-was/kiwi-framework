package de.kiwi.api.application.service

import jakarta.persistence.metamodel.EntityType

/**
 * Service providing access to JPA entity types by name lookup from the metamodel.
 */
interface EntityTypeProvider {

    /**
     * List of all entity classes known to the current persistence context.
     */
    val entityClasses: List<Class<*>>

    /**
     * Find the JPA entity type corresponding to the given entity name.
     * The given entity name is case-insensitive.
     */
    fun findEntityType(entityName: String): EntityType<*>?

    /**
     * Find the JPA entity type corresponding to the given entity class.
     */
    fun <E : Any> findEntityType(entityTypeClass: Class<E>): EntityType<E>?
}
