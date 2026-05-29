package de.kiwi.api.application.generic

import com.fasterxml.jackson.databind.JsonNode
import de.kiwi.api.application.command.CommandHandler
import de.kiwi.api.domain.*
import de.kiwi.api.util.where
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.criteria.CriteriaQuery
import jakarta.persistence.criteria.Expression
import jakarta.persistence.criteria.From
import jakarta.persistence.criteria.Root
import org.springframework.data.jpa.domain.Specification

/**
 * Base class for [GenericUpdateHandler] and [GenericDeleteHandler], allowing customization
 * of entity loading by offering overridable methods/properties:
 * - [GenericModificationHandler.accessControl] to specify an [AccessControl] to use
 * - [GenericModificationHandler.accessControlAllowAdmin] to specify unrestricted access to [UserDescriptor.isAdmin] users
 * - [GenericModificationHandler.selectEntityIdValue] to customize entity ID attribute selection
 * - [GenericModificationHandler.findEntityBy] to customize target entity specification
 * - [GenericModificationHandler.loadEntity] to customize target entity selection and loading
 */
abstract class GenericModificationHandler<C : Command<JsonNode>, E : Any> : CommandHandler<C, JsonNode> {

    @PersistenceContext
    lateinit var entityManager: EntityManager

    /** Abstract method retrieving the [EntityId] of the command type [C] */
    protected abstract fun commandEntityId(command: C): EntityId

    /**
     * Overridable property specifying an [AccessControl] to use when loading entities for modification.
     * If not specified, no access control restriction is applied when loading an entity.
     */
    protected open val accessControl: AccessControl<E>? = null

    /**
     * Overridable property specifying if the [accessControl] specification should allow for unrestricted
     * access when the [UserDescriptor.isAdmin] property is `true`.
     * If not specified, unrestricted admin access is **enabled by default**.
     */
    protected open val accessControlAllowAdmin: Boolean = true

    /**
     * Overridable method for selecting the [EntityId.idValue] for a given entity [Root].
     * By default, this selects "root.[BaseEntity.id].[EntityId.idValue]".
     */
    protected open fun selectEntityIdValue(root: Root<E>): Expression<*> =
        root.get<EntityId>(BaseEntity_.id.name).get(EntityId_.idValue)

    /**
     * Overridable method for creating a [Specification] filtering a single target entity to be loaded for modification.
     * By default, this invokes [accessControl] for an [AccessControl] restriction to be used, and returns a
     * [Specification] using the given accessControl in conjunction with filtering the entity by ID comparing
     * [selectEntityIdValue] against the entity ID derived from the [command].
     */
    protected open fun findEntityBy(command: C, entityClass: Class<E>, user: UserDescriptor): Specification<E>? {
        // Create AccessControl specification if a non-null accessControl property has been defined
        val accessControl = this.accessControl ?: return null
        val filterByAccess =
            if (accessControlAllowAdmin) accessControl.adminOrCheckAuthorized(user)
            else accessControl.checkAuthorized(user)

        // Create Entity ID specification
        val entityId = commandEntityId(command)
        val filterById = where { root -> equal(selectEntityIdValue(root), entityId.value) }

        // Filter entity by ID and AccessControl
        return filterById.and(filterByAccess)
    }

    /**
     * Overridable method for loading an entity to be modified.
     * By default, this invokes [findEntityBy] to obtain a [Specification] for loading the target entity.
     * If no specification is returned, loading an entity falls back to using [EntityManager.find].
     */
    protected open fun loadEntity(command: C, entityClass: Class<E>, user: UserDescriptor): E? {
        // Get a specification for entity selection, otherwise use entityManager.find
        val specification = findEntityBy(command, entityClass, user)
            ?: return entityManager.find(entityClass, commandEntityId(command))

        with(entityManager.criteriaBuilder) {
            // Build the query "SELECT e FROM Entity e WHERE <specification> LIMIT 1"
            val query = createQuery(entityClass)
            val root = query.from(entityClass)
            query.where(specification.toPredicate(root, query, this))
            query.select(root)

            // Check if the resulting CriteriaQuery contains a join-fetch statement
            // If so, pagination (set maxResults -> LIMIT ?) must *not* be used,
            // as Hibernate will otherwise issue an in-memory fetch/pagination warning.
            val includesFetch = checkIncludesFetch(query)

            // Use the query to select the target entity for modification
            val entityQuery = entityManager.createQuery(query)
            if (!includesFetch) entityQuery.maxResults = 1
            return entityQuery.resultList.singleOrNull()
        }
    }

    private fun checkIncludesFetch(query: CriteriaQuery<*>): Boolean {
        fun recursiveCheck(sources: Iterable<From<*, *>>): Boolean {
            return sources.any { source ->
                source.fetches.isNotEmpty() || recursiveCheck(source.joins)
            }
        }
        return recursiveCheck(query.roots)
    }
}
