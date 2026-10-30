package de.kiwi.api.schema

import tools.jackson.databind.JsonNode
import de.kiwi.api.domain.UserDescriptor
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification

/**
 * Service for querying fieldset representations of entities from the persistence context.
 * @see Fieldset
 * @see Specification
 * @see Pageable
 * @see Sort
 */
interface QuerySchemaService {
    /**
     * Find at most one entity matching the given [Specification].
     * The data of the entity is represented according to the given [Fieldset] as a [JsonNode].
     */
    fun <E : Any> findOne(
        fieldset: Fieldset<E>,
        specification: Specification<E>,
        user: UserDescriptor? = null,
    ): JsonNode? = findOne(
        fieldset = fieldset,
        specification = specification,
        serializer = JsonFieldsetSerializer,
        user = user,
    )

    /**
     * Find at most one entity matching the given [Specification].
     * The data of the entity is represented according to the given [Fieldset],
     * serialized using the given [FieldsetSerializer].
     */
    fun <E : Any, N> findOne(
        fieldset: Fieldset<E>,
        specification: Specification<E>,
        serializer: FieldsetSerializer<N>,
        user: UserDescriptor? = null,
    ): N?

    /**
     * Find all entities matching the given [Specification], ordered by the given [Sort].
     * The data of the entities is represented according to the given [Fieldset] as a [JsonNode].
     */
    fun <E : Any> findAll(
        fieldset: Fieldset<E>,
        specification: Specification<E>,
        sort: Sort = Sort.unsorted(),
        totalLimit: Long? = null,
        user: UserDescriptor? = null,
    ): List<JsonNode> = findAll(
        fieldset = fieldset,
        specification = specification,
        serializer = JsonFieldsetSerializer,
        sort = sort,
        totalLimit = totalLimit,
        user = user,
    )

    /**
     * Find all entities matching the given [Specification], ordered by the given [Sort].
     * The data of the entities is represented according to the given [Fieldset],
     * serialized using the given [FieldsetSerializer].
     */
    fun <E : Any, N> findAll(
        fieldset: Fieldset<E>,
        specification: Specification<E>,
        serializer: FieldsetSerializer<N>,
        sort: Sort = Sort.unsorted(),
        totalLimit: Long? = null,
        user: UserDescriptor? = null,
    ): List<N>

    /**
     * Find a page of entities matching the given [Specification], ordered by the given [Sort] or [Pageable].
     * The size and offset of the page is determined by the given [Pageable].
     * The data of the entities is represented according to the given [Fieldset].
     */
    fun <E : Any> findPage(
        fieldset: Fieldset<E>,
        specification: Specification<E>,
        pagination: Pageable = Pageable.unpaged(),
        sort: Sort = Sort.unsorted(),
        totalLimit: Long? = null,
        user: UserDescriptor? = null,
    ): Page<JsonNode> = findPage(
        fieldset = fieldset,
        specification = specification,
        serializer = JsonFieldsetSerializer,
        pagination = pagination,
        sort = sort,
        totalLimit = totalLimit,
        user = user,
    )

    /**
     * Find a page of entities matching the given [Specification], ordered by the given [Sort] or [Pageable].
     * The size and offset of the page is determined by the given [Pageable].
     * The data of the entities is represented according to the given [Fieldset],
     * serialized using the given [FieldsetSerializer].
     */
    fun <E : Any, N : Any> findPage(
        fieldset: Fieldset<E>,
        specification: Specification<E>,
        serializer: FieldsetSerializer<N>,
        pagination: Pageable = Pageable.unpaged(),
        sort: Sort = Sort.unsorted(),
        totalLimit: Long? = null,
        user: UserDescriptor? = null,
    ): Page<N>
}
