package de.kiwi.framework.infrastructure.schema

import de.kiwi.api.application.service.QuerySchemaProvider
import de.kiwi.api.generic.GenericEntityFilter
import de.kiwi.api.schema.EntitySchema
import de.kiwi.api.schema.FieldsetsAvailable
import de.kiwi.api.util.lookupSupertypeArgument
import de.kiwi.api.util.slf4j
import de.kiwi.framework.infrastructure.util.ClassPathScannerUtil
import org.springframework.beans.BeanUtils
import org.springframework.beans.factory.InitializingBean

private val log = slf4j<SpringQuerySchemaProvider>()

class SpringQuerySchemaProvider(
    private val classPathScanner: ClassPathScannerUtil,
) : QuerySchemaProvider, InitializingBean {

    private lateinit var entitySchemas: Map<Class<*>, EntitySchema<*>>
    private lateinit var entityFilterTypes: Map<Class<*>, Class<out GenericEntityFilter<*>>>
    private lateinit var entityFieldsetAggregates: Map<Class<*>, FieldsetsAvailable<*>>

    override fun afterPropertiesSet() {
        val basePackages = classPathScanner.findEntityAndComponentBasePackages()
        val filterClasses = classPathScanner.findClassesOfTypeInPackages<GenericEntityFilter<*>>(basePackages)
        val schemaClasses = classPathScanner.findClassesOfTypeInPackages<EntitySchema<*>>(basePackages)
        val fieldsetsClasses = classPathScanner.findClassesOfTypeInPackages<FieldsetsAvailable<*>>(basePackages)

        entityFilterTypes = filterClasses.associateBy {
            it.lookupSupertypeArgument<GenericEntityFilter<*>>(0)
        }
        entitySchemas = schemaClasses.associate {
            it.lookupSupertypeArgument<EntitySchema<*>>(0) to singletonFromType(it)
        }
        entityFieldsetAggregates = fieldsetsClasses.associate {
            it.lookupSupertypeArgument<FieldsetsAvailable<*>>(0) to singletonFromType(it)
        }

        // Log schema, filter and fieldsets grouped by entity type
        val entityTypes = (entityFilterTypes.keys + entityFilterTypes.keys + entityFieldsetAggregates.keys).distinct()
        entityTypes.forEach { entityType ->
            val schema = entitySchemas[entityType]
            val filter = entityFilterTypes[entityType]
            val fieldsets = entityFieldsetAggregates[entityType]
            val fieldsetNames = fieldsets?.allFieldsets?.map { "\"${it.name}\"" }
            log.info("Entity [${entityType.name}]: Schema = [${schema?.javaClass?.name}], Filter = [${filter?.name}], Fieldsets = [${fieldsets?.javaClass?.name}]")
            log.info("Entity [${entityType.name}]: Fieldset names = $fieldsetNames")
        }
    }

    private fun <T : Any> singletonFromType(type: Class<T>): T {
        return type.kotlin.objectInstance ?: BeanUtils.instantiateClass(type)
    }

    override val schemas: List<EntitySchema<*>> get() = entitySchemas.values.toList()

    override fun <E : Any> findEntitySchema(entityType: Class<E>): EntitySchema<E>? {
        @Suppress("UNCHECKED_CAST")
        return entitySchemas[entityType] as EntitySchema<E>?
    }

    override fun <E : Any> findFilterType(entityType: Class<E>): Class<out GenericEntityFilter<E>>? {
        @Suppress("UNCHECKED_CAST")
        return entityFilterTypes[entityType] as Class<out GenericEntityFilter<E>>?
    }

    override fun <E : Any> findAvailableFieldsets(entityType: Class<E>): FieldsetsAvailable<E>? {
        @Suppress("UNCHECKED_CAST")
        return entityFieldsetAggregates[entityType] as FieldsetsAvailable<E>?
    }
}
