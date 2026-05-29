package de.kiwi.framework.infrastructure.schema

import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.schema.Fieldset
import de.kiwi.api.schema.FieldsetSerializer
import de.kiwi.api.schema.QuerySchemaService
import de.kiwi.framework.infrastructure.schema.engine.QuerySchemaEngine
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.criteria.CriteriaBuilder
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification

/**
 * JPA based [QuerySchemaService] implementation.
 * This service uses an [EntityManager] to query fieldsets from the database.
 * The implementation utilizes [CriteriaBuilder.createTupleQuery] to avoid hydrating entity objects,
 * and executes one SQL query per to-many relation that is part of the given fieldset.
 *
 * ## Query Engine Internal ##
 * The query engine works by first executing a _root query_, loading columns of the entity
 * the given fieldset belongs to. Then, recursively, all relations associated with the loaded root entity rows
 * are queried as _child queries_ in turn. The scope of the selected child rows depends on the primary-key values
 * selected from the root entity.
 *
 * ### Example ###
 * - Entity `Example` with Fieldset:
 *     - QueryColumn<PK> `id`
 *     - QueryColumn `name`
 *     - QueryToOne `parent` of Entity `Parent` with Fieldset:
 *         - QueryColumn<PK> `id`
 *         - QueryColumn `parentName`
 *     - QueryToMany `child` of Entity `Child` with Fieldset:
 *         - QueryColumn<PK> `id`
 *         - QueryColumn `childName`
 *         - QueryToMany `grandchild` of Entity `Grandchild` with Fieldset:
 *             - QueryColumn<PK> `id`
 *             - QueryColumn `grandchildName`
 * ```sql
 * /* 1. Query `Example` root entity, including `Parent` to-one entity */
 * SELECT e.id, e.name, e.parent.id, e.parent.parentName FROM Example e WHERE <specification> ORDER BY <sort> LIMIT <pagination>;
 *
 * /* 2. Query `Child` child entity, using primary-key values of root query result */
 * SELECT c.id, c.childName FROM Child c WHERE c.example.id IN (<values of Example e.id>);
 *
 * /* 3. Query `Grandchild` child entity, using primary-key values of root query result */
 * SELECT g.id, g.grandchildName FROM Grandchild g JOIN Child c ON g.parent = c WHERE c.example.id IN (<values of Example e.id>);
 * ```
 */
class JpaQuerySchemaService : QuerySchemaService {

    @PersistenceContext
    lateinit var entityManager: EntityManager

    override fun <E : Any, N> findOne(
        fieldset: Fieldset<E>,
        specification: Specification<E>,
        serializer: FieldsetSerializer<N>,
        user: UserDescriptor?,
    ): N? =
        QuerySchemaEngine(fieldset, serializer, entityManager, user)
            .findOne(specification)

    override fun <E : Any, N> findAll(
        fieldset: Fieldset<E>,
        specification: Specification<E>,
        serializer: FieldsetSerializer<N>,
        sort: Sort,
        totalLimit: Long?,
        user: UserDescriptor?,
    ): List<N> =
        QuerySchemaEngine(fieldset, serializer, entityManager, user)
            .findPage(specification, Pageable.unpaged(), sort, totalLimit)
            .content

    override fun <E : Any, N> findPage(
        fieldset: Fieldset<E>,
        specification: Specification<E>,
        serializer: FieldsetSerializer<N>,
        pagination: Pageable,
        sort: Sort,
        totalLimit: Long?,
        user: UserDescriptor?,
    ): Page<N> =
        QuerySchemaEngine(fieldset, serializer, entityManager, user)
            .findPage(specification, pagination, sort, totalLimit)
}
