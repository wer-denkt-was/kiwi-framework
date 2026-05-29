package de.kiwi.api.generic

import com.fasterxml.jackson.databind.JsonNode
import de.kiwi.api.domain.*
import de.kiwi.api.schema.Fieldset
import de.kiwi.api.util.where
import jakarta.persistence.metamodel.SingularAttribute
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.domain.Specification

/**
 * Abstract query for retrieving a page of [JSON values][JsonNode] representing entities.
 * This query supports filtering by [EntityId], [GenericEntityFilter], and representing entities using a [Fieldset].
 *
 * __Example__:
 * ```kt
 * class ExampleQuery : GenericEntityQuery<ExampleEntity>() {
 *   override fun filterById(id: ExampleEntityId) = baseEntityFilter<ExampleEntity>(id)
 *
 *   override val accessControl = TODO("Add AccessControl<ExampleEntity> filter")
 *   override val accessControlAllowAdmin = TODO("Determine if UserDescriptor.isAdmin has access to all entities")
 *   override fun customFilter(): Specification<ExampleEntity>? = TODO("Add additional custom entity filter")
 *
 *   override fun preAuthorize(user: UserDescriptor) = TODO("Pre-Authorize query")
 * }
 * ```
 */
abstract class GenericEntityQuery<E : Any> : Query<PageResult<JsonNode>>(), Authorized {
    var id: EntityId? = null
    var filter: GenericEntityFilter<E>? = null
    var pagination: Pageable = Pageable.unpaged()
    var totalLimit: Long? = null

    lateinit var fieldset: Fieldset<E>

    abstract fun filterById(id: EntityId): Specification<E>

    open val accessControl: AccessControl<E>? = null
    open val accessControlAllowAdmin: Boolean = true

    open fun accessControlFilter(user: UserDescriptor): Specification<E>? {
        return accessControl?.let {
            if (accessControlAllowAdmin) it.adminOrCheckAuthorized(user)
            else it.checkAuthorized(user)
        }
    }

    open fun customFilter(user: UserDescriptor): Specification<E>? = null

    companion object {
        fun <E : BaseEntity<*>> baseEntityFilter(
            id: EntityId,
            attribute: SingularAttribute<in E, EntityId> = BaseEntity_.id,
        ) = where { root ->
            equal(root[attribute][EntityId_.idValue], id.value)
        }
    }
}
