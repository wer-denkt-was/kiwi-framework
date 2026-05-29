package de.kiwi.framework.infrastructure.jpa

import de.kiwi.api.application.service.EntityTypeProvider
import jakarta.persistence.DiscriminatorValue
import jakarta.persistence.Entity
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.metamodel.EntityType
import org.springframework.beans.factory.InitializingBean
import java.util.concurrent.ConcurrentHashMap

class JpaEntityTypeProvider : EntityTypeProvider, InitializingBean {

    @PersistenceContext
    lateinit var entityManager: EntityManager

    private val entityTypeByNameCache = ConcurrentHashMap<String, EntityType<*>?>()

    override fun afterPropertiesSet() {
        require(this::entityManager.isInitialized) { "entityManager must be initialized" }
    }

    override fun <E : Any> findEntityType(entityTypeClass: Class<E>): EntityType<E>? {
        return try {
            entityManager.metamodel.entity(entityTypeClass)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override val entityClasses: List<Class<*>>
        get() = entityManager.metamodel.entities.map { it.javaType }

    override fun findEntityType(entityName: String): EntityType<*>? =
        entityTypeByNameCache.computeIfAbsent(entityName) { lookupEntityTypeFromMetamodel(it) }

    private fun lookupEntityTypeFromMetamodel(entityName: String): EntityType<*>? {
        val entityTypes = entityManager.metamodel.entities
        return entityTypes.find { it.name.equals(entityName, ignoreCase = true) }
            ?: entityTypes.find { it.discriminatorValue.equals(entityName, ignoreCase = true) }
    }
}

private val <E> EntityType<E>.discriminatorValue
    get() = javaType.getAnnotation(DiscriminatorValue::class.java)?.value
        ?: javaType.getAnnotation(Entity::class.java)?.name?.takeIf { it.isNotEmpty() }
        ?: javaType.simpleName
