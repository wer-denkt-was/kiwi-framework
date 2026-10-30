package de.kiwi.framework.application.generic

import de.kiwi.api.application.service.EntityTypeProvider
import de.kiwi.api.application.service.QuerySchemaProvider
import de.kiwi.api.generic.*
import de.kiwi.api.schema.AbstractSchema
import de.kiwi.api.schema.EntitySchema
import de.kiwi.api.schema.SingleValueSchema
import de.kiwi.api.util.lookupSupertypeArgument
import de.kiwi.framework.support.introspectForCreation
import de.kiwi.framework.support.introspectForDeserialization
import org.springframework.beans.BeanUtils
import org.springframework.core.annotation.AnnotationUtils
import tools.jackson.databind.BeanDescription
import tools.jackson.databind.JavaType
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.introspect.BeanPropertyDefinition

/**
 * Data class representing generic command/query metadata of an entity.
 */
data class GenericMetadata(
    val entityName: String,
    val entityClassName: String,
    val query: QueryDescriptor?,
    val schema: SchemaDescriptor?,
    val create: CreateDescriptor?,
    val update: UpdateDescriptor?,
    val delete: DeleteDescriptor?,
) {
    data class CreateDescriptor(
        val commandClassName: String,
        val properties: Map<String, PropertyInfo>,
    )

    data class UpdateDescriptor(
        val commandClassName: String,
        val idClassName: String,
        val properties: Map<String, PropertyInfo>,
    )

    data class DeleteDescriptor(
        val commandClassName: String,
        val idClassName: String,
    )

    data class QueryDescriptor(
        val queryClassName: String,
        val filterProperties: Map<String, PropertyInfo>,
    )

    data class SchemaDescriptor(
        val schemaClassName: String,
        val fieldsets: List<String>,
        val columnProperties: Map<String, PropertyInfo>,
        val toOneRelations: Map<String, RelationInfo>,
        val toManyRelations: Map<String, RelationInfo>,
        val toManyMapRelations: Map<String, RelationPairInfo>,
        val subtypeProperties: Map<String, SchemaDescriptor>,
    )

    data class PropertyInfo(val valueClassName: String, val description: String, val required: Boolean = false)
    data class RelationInfo(val entityName: String, val entityClassName: String)
    data class RelationPairInfo(val key: RelationInfo, val value: RelationInfo)
}

/**
 * Service creating a [GenericMetadata] representation for a given entity type class.
 */
class GenericMetadataProvider(
    private val objectMapper: ObjectMapper,
    private val entityTypeProvider: EntityTypeProvider,
    private val querySchemaProvider: QuerySchemaProvider,
    private val genericHandlerProvider: GenericHandlerProvider,
) {
    private val typeFactory = objectMapper.typeFactory

    fun genericMetadata(entityClass: Class<*>): GenericMetadata? {
        val entityType = entityTypeProvider.findEntityType(entityClass) ?: return null
        val createType = genericHandlerProvider.createClasses[entityClass]
        val updateType = genericHandlerProvider.updateClasses[entityClass]
        val deleteType = genericHandlerProvider.deleteClasses[entityClass]
        val queryType = genericHandlerProvider.queryClasses[entityClass]
        val schema = querySchemaProvider.findEntitySchema(entityClass)

        return GenericMetadata(
            entityName = entityType.name,
            entityClassName = entityClass.name,
            create = createType?.let { type ->
                GenericMetadata.CreateDescriptor(
                    commandClassName = type.name,
                    properties = genericBodyProperties(type, create = true)
                        ?: introspectProperties(type, objectMapper::introspectForCreation) { it.isRequired },
                )
            },
            update = updateType?.let { type ->
                val idClass = AnnotationUtils
                    .findAnnotation(type, UpdateCommand::class.java)?.idType?.java
                    ?: type.lookupSupertypeArgument<GenericUpdate<*, *>>(1)

                GenericMetadata.UpdateDescriptor(
                    commandClassName = type.name,
                    idClassName = idClass.name,
                    properties = genericBodyProperties(type, create = false)
                        ?: introspectProperties(type, objectMapper::introspectForDeserialization) { it.hasSetter() },
                )
            },
            delete = deleteType?.let { type ->
                val idClass = AnnotationUtils
                    .findAnnotation(type, DeleteCommand::class.java)?.idType?.java
                    ?: type.lookupSupertypeArgument<GenericDelete<*, *>>(1)

                GenericMetadata.DeleteDescriptor(
                    commandClassName = type.name,
                    idClassName = idClass.name,
                )
            },
            query = queryType?.let { createQueryDescriptor(it, entityClass) },
            schema = schema?.let { createSchemaDescriptor(it) },
        )
    }

    private fun introspectProperties(
        type: Class<*>,
        introspect: (JavaType) -> BeanDescription,
        propertyFilter: (BeanPropertyDefinition) -> Boolean,
    ): Map<String, GenericMetadata.PropertyInfo> {
        // Get properties using Jackson introspection
        val typeDescriptor = typeInfo(type)
        val introspection = introspect(typeDescriptor)
        return introspection.findProperties()
            .filter(propertyFilter)
            .associate { it.name to propertyInfo(it.primaryType) }
    }

    /** Properties of generic create/update commands from their body fields, `null` for other commands. */
    private fun genericBodyProperties(type: Class<*>, create: Boolean): Map<String, GenericMetadata.PropertyInfo>? {
        if (!GenericBodyCommand::class.java.isAssignableFrom(type)) return null
        val command = BeanUtils.instantiateClass(type) as GenericBodyCommand<*>
        return command.bodyFields.associate { field ->
            val javaType = typeFactory.constructType(field.valueType)
            val subtypes = (field.source as? GenericBodyFieldSource.Subtype)?.subtypes?.keys
                ?.joinToString(prefix = " (", postfix = ")")
            val nullable = if (field.nullable) "?" else ""
            field.jsonKey to GenericMetadata.PropertyInfo(
                valueClassName = javaType.toCanonical(),
                description = "${javaType.typeName}$nullable${subtypes ?: ""}",
                required = create && field.required,
            )
        }
    }

    private fun typeInfo(type: Class<*>) = typeFactory.constructType(type)

    private fun propertyInfo(type: Class<*>) = propertyInfo(typeInfo(type))

    private fun propertyInfo(javaType: JavaType) = GenericMetadata.PropertyInfo(
        valueClassName = javaType.toCanonical(),
        description = javaType.typeName,
    )

    private fun createQueryDescriptor(queryClass: Class<*>, entityClass: Class<*>): GenericMetadata.QueryDescriptor {
        val filterType = querySchemaProvider.findFilterType(entityClass)

        val filterProperties = filterType?.let { type ->
            introspectProperties(type, objectMapper::introspectForDeserialization) { it.hasSetter() }
        }

        return GenericMetadata.QueryDescriptor(
            queryClassName = queryClass.name,
            filterProperties = filterProperties ?: emptyMap(),
        )
    }

    private fun createSchemaDescriptor(schema: AbstractSchema<*, *>): GenericMetadata.SchemaDescriptor = when (schema) {
        is SingleValueSchema<*, *> -> GenericMetadata.SchemaDescriptor(
            schemaClassName = schema.javaClass.name,
            fieldsets = emptyList(),
            columnProperties = emptyMap(),
            toOneRelations = emptyMap(),
            toManyRelations = emptyMap(),
            toManyMapRelations = emptyMap(),
            subtypeProperties = emptyMap(),
        )

        is EntitySchema<*> -> {
            val fieldsets = querySchemaProvider.findAvailableFieldsets(schema.entityClass)
            GenericMetadata.SchemaDescriptor(
                schemaClassName = schema.javaClass.name,
                fieldsets = fieldsets?.allFieldsets?.map { it.name } ?: listOf("default"),
                columnProperties = schema.columns.associate {
                    it.fieldName to propertyInfo(it.type.valueType)
                },
                toOneRelations = schema.toOneReferences.associate {
                    it.fieldName to relationInfo(it.toOne)
                },
                toManyRelations = schema.toManyReferences.associate {
                    it.fieldName to relationInfo(it.toMany)
                },
                toManyMapRelations = schema.toManyMapReferences.associate {
                    it.fieldName to relationPairInfo(it.toManyKeys, it.toManyValues)
                },
                subtypeProperties = schema.subtypeReferences.associate {
                    it.fieldName to createSchemaDescriptor(it.subtypeSchema)
                },
            )
        }
    }

    private fun relationPairInfo(
        key: AbstractSchema<*, *>,
        value: AbstractSchema<*, *>,
    ): GenericMetadata.RelationPairInfo {
        return GenericMetadata.RelationPairInfo(
            key = relationInfo(key),
            value = relationInfo(value),
        )
    }

    private fun relationInfo(referencedSchema: AbstractSchema<*, *>): GenericMetadata.RelationInfo {
        val entityClass = referencedSchema.entityClass
        val entityType = entityTypeProvider.findEntityType(entityClass)
        val entityName = entityType?.name ?: entityClass.simpleName
        return GenericMetadata.RelationInfo(
            entityName = entityName,
            entityClassName = entityClass.name,
        )
    }
}
