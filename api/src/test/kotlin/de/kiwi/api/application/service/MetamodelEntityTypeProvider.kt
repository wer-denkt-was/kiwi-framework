package de.kiwi.api.application.service

import jakarta.persistence.metamodel.EntityType
import jakarta.persistence.metamodel.Metamodel

class MetamodelEntityTypeProvider(private val metamodel: Metamodel) : EntityTypeProvider {
    override val entityClasses: List<Class<*>> by lazy { metamodel.entities.map { it.javaType } }

    override fun findEntityType(entityName: String): EntityType<*>? =
        metamodel.entities.find { it.name == entityName }
            ?: metamodel.entities.find { it.javaType.simpleName == entityName }
            ?: metamodel.entities.find { it.javaType.name == entityName }

    override fun <E : Any> findEntityType(entityTypeClass: Class<E>): EntityType<E>? {
        TODO("Not yet implemented")
    }
}
