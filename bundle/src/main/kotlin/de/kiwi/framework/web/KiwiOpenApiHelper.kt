package de.kiwi.framework.web

import de.kiwi.api.domain.Command
import de.kiwi.api.domain.EntityId
import de.kiwi.api.domain.Transactional
import de.kiwi.api.generic.GenericBodyCommand
import de.kiwi.api.generic.GenericBodyFieldSource
import de.kiwi.api.generic.GenericCreate
import de.kiwi.api.schema.*
import de.kiwi.api.util.lookupSupertypeArgument
import de.kiwi.framework.support.introspectForCreation
import de.kiwi.framework.support.introspectForDeserialization
import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverters
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.*
import org.springframework.beans.BeanUtils
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf

class KiwiOpenApiHelper(
    private val objectMapper: ObjectMapper,
) {
    private val columnTypeModelCache = ConcurrentHashMap<ColumnType<*, *>, Schema<*>>()
    private val valueClassModelCache = ConcurrentHashMap<Class<*>, Schema<*>>()

    fun entitySchemaModel(
        context: OpenAPI,
        entitySchema: EntitySchema<*>?,
        alreadyVisited: Set<Class<*>> = emptySet(),
        requestBody: Boolean = false,
    ): Schema<*> {
        val components = context.getOrCreateComponents()

        // unknown schema -> object with arbitrary properties
        if (entitySchema == null) {
            return ObjectSchema().additionalProperties(true)
        }

        // schema already exists -> immediately return reference to entity schema
        val existingSchemaKeys = components.schemas?.keys ?: emptySet()
        val entitySchemaKey = entitySchemaKey(entitySchema, requestBody)
        if (entitySchemaKey in existingSchemaKeys) {
            return componentSchemaRef(entitySchemaKey)
        }

        fun readAndRegisterRelationModel(relation: AbstractSchema<*, *>): Schema<*> = when (relation) {
            is SingleValueSchema<*, *> -> resolveColumnTypeModel(relation, components)
            is EntitySchema<*> -> when {
                relation.entityClass == entitySchema.entityClass -> componentSchemaRef(entitySchemaKey)
                relation.entityClass in alreadyVisited -> componentSchemaRef(entitySchemaKey(relation, requestBody))
                else -> entitySchemaModel(context, relation, alreadyVisited + entitySchema.entityClass)
            }
        }

        // Exclude primaryKey-field from POST request-body schema, as these are not usually required in commands
        val includedColumns =
            if (requestBody) entitySchema.columns.filterNot { it.primaryKey }
            else entitySchema.columns

        val columnFieldModels = includedColumns.associate {
            it.fieldName to resolveColumnModel(it, components)
        }

        // Only include relation-fields (toOne, toMany, toManyMap) if schema is not a request body, i.e. is a GET response
        // The rationale for this is that POST/PATCH requests only rely on column-fields for supplying values in commands
        val relationFieldModels = if (requestBody) {
            emptyMap<String, Schema<*>>()
        } else {
            entitySchema.toOneReferences.associate { ref ->
                ref.fieldName to readAndRegisterRelationModel(ref.toOne)
            } + entitySchema.toManyReferences.associate { ref ->
                val referencedItemModel = readAndRegisterRelationModel(ref.toMany)
                ref.fieldName to ArraySchema().items(referencedItemModel)
            } + entitySchema.toManyMapReferences.associate { ref ->
                val referencedKeyModel = readAndRegisterRelationModel(ref.toManyKeys)
                val referencedValueModel = readAndRegisterRelationModel(ref.toManyValues)
                ref.fieldName to ArraySchema()
                    .prefixItems(listOf(referencedKeyModel, referencedValueModel))
                    .minItems(2)
                    .maxItems(2)
            }
        }

        val allFieldModels = columnFieldModels + relationFieldModels
        val entitySchemaModel = assembleEntitySchemaModel(allFieldModels, requestBody)
        entitySchemaModel.name(entitySchema.entityClass.simpleName)
        entitySchemaModel.description("EntitySchema ${entitySchema.entityClass.name}")
        components.addSchemas(entitySchemaKey, entitySchemaModel)

        // Return ref-schema referencing registered entity schema type
        return componentSchemaRef(entitySchemaKey)
    }

    private fun assembleEntitySchemaModel(fieldModels: Map<String, Schema<*>>, requestBody: Boolean): Schema<*> {
        val entitySchemaModel = ObjectSchema()
        fieldModels.forEach { (name, model) -> entitySchemaModel.addProperty(name, model) }
        if (requestBody) entitySchemaModel.additionalProperties(true)
        return entitySchemaModel
    }

    /**
     * Request body model of a generic create/update command, from its declared body fields.
     * Update commands are patches, so no field is required. Unknown fields are rejected.
     */
    fun genericBodyCommandModel(context: OpenAPI, commandClass: Class<out GenericBodyCommand<*>>): Schema<*> {
        val components = context.getOrCreateComponents()
        val command = BeanUtils.instantiateClass(commandClass)
        val create = command is GenericCreate<*>

        val commandModel = ObjectSchema()
        commandModel.name(commandClass.simpleName)
        commandModel.description("Command ${commandClass.name}")
        commandModel.additionalProperties(false)
        command.bodyFields.forEach { field ->
            val valueModel = when (val source = field.source) {
                is GenericBodyFieldSource.Schema -> {
                    val columnType = source.schemaField.type
                    resolveColumnTypeModel(columnType, components)
                }

                is GenericBodyFieldSource.Property -> {
                    val valueClass = objectMapper.typeFactory.constructType(field.valueType).rawClass
                    resolveValueClassModel(valueClass, components)
                }

                is GenericBodyFieldSource.ToOneReference -> entityIdSchema(field.referencedIdType)

                is GenericBodyFieldSource.ToManyReferences -> ArraySchema()
                    .items(entityIdSchema(field.referencedIdType))

                is GenericBodyFieldSource.Subtype -> StringSchema()
                    ._enum(source.subtypes.keys.toList())
            }

            commandModel.addProperty(field.jsonKey, if (field.nullable) valueModel.withNullable() else valueModel)
            if (create && field.required) commandModel.addRequiredItem(field.jsonKey)
        }
        return commandModel
    }

    fun commandClassModels(
        context: OpenAPI,
        commandClass: Class<out Command<*>>,
        role: CommandClassRole,
    ): CommandClassModels {
        val components = context.getOrCreateComponents()

        fun assembleCommandClassSchema(create: Boolean): Schema<*> {
            val commandJavaType = objectMapper.typeFactory.constructType(commandClass)
            val introspect =
                if (create) objectMapper.introspectForCreation(commandJavaType)
                else objectMapper.introspectForDeserialization(commandJavaType)

            val commandModel = ObjectSchema()
            commandModel.name(commandClass.simpleName)
            commandModel.description("Command ${commandClass.name}")
            introspect.findProperties().forEach { property ->
                val include =
                    if (create) property.mutator != null
                    else property.hasSetter()

                if (include) {
                    val propertyValueModel = resolveValueClassModel(property.rawPrimaryType, components)
                    commandModel.addProperty(property.name, propertyValueModel)
                }

                if (create && property.isRequired) {
                    commandModel.addRequiredItem(property.name)
                }
            }

            return commandModel
        }

        val resultValueClass = commandClass.lookupSupertypeArgument<Transactional<*>>(0)
        val responseModel = resolveValueClassModel(resultValueClass, components)

        return when (role) {
            CommandClassRole.Create -> CommandClassModels(assembleCommandClassSchema(create = true), responseModel)
            CommandClassRole.Update -> CommandClassModels(assembleCommandClassSchema(create = false), responseModel)
            CommandClassRole.Delete -> CommandClassModels(ArbitrarySchema(), responseModel)
        }
    }

    private fun resolveColumnModel(column: ColumnField<*, *, *>, components: Components): Schema<*> {
        val columnTypeModel = resolveColumnTypeModel(column.type, components)
        return if (column.nullable) columnTypeModel.withNullable()
        else columnTypeModel
    }

    private fun resolveColumnTypeModel(columnType: ColumnType<*, *>, components: Components): Schema<*> {
        return columnTypeModelCache.computeIfAbsent(columnType) {
            when (columnType) {
                is EntityIdColumnType<*> -> resolveEntityIdColumnTypeModel(columnType)
                is EnumColumnType<*> -> resolveEnumColumnTypeModel(columnType)
                else -> resolveValueClassModel(columnType.valueType, components)
            }
        }
    }

    private fun resolveEntityIdColumnTypeModel(columnType: EntityIdColumnType<*>): Schema<*> {
        return entityIdSchema(columnType.entityIdType)
    }

    private fun resolveEnumColumnTypeModel(enumColumnType: EnumColumnType<*>): Schema<*> {
        val enumClass = enumColumnType.enumType
        check(enumClass.isEnum) { "Type ${enumClass.name} is not an enum type" }
        val enumNames = enumClass.enumConstants.map { it.name }
        return StringSchema()._enum(enumNames)
    }

    private fun resolveValueClassModel(valueClass: Class<*>, components: Components): Schema<*> {
        val valueKClass = valueClass.kotlin
        fun isSubclassOf(superType: KClass<*>) = valueKClass.isSubclassOf(superType)

        return valueClassModelCache.computeIfAbsent(valueClass) { valueClass ->
            when {
                // Handling well-known Java types
                isSubclassOf(String::class) -> StringSchema()
                isSubclassOf(Int::class) -> IntegerSchema()
                isSubclassOf(Byte::class) -> IntegerSchema().format("int8")
                isSubclassOf(Short::class) -> IntegerSchema().format("int16")
                isSubclassOf(Long::class) -> IntegerSchema().format("int64")
                isSubclassOf(Double::class) -> NumberSchema().format("double")
                isSubclassOf(Float::class) -> NumberSchema().format("float")
                isSubclassOf(Number::class) -> NumberSchema()
                isSubclassOf(Boolean::class) -> BooleanSchema()

                // Special handling if value-class is an EntityId
                isSubclassOf(EntityId::class) -> entityIdSchema(valueClass)

                // Special handling if value-class is already a JsonNode
                isSubclassOf(JsonNode::class) ->
                    @Suppress("UNCHECKED_CAST")
                    jsonNodeTypeModel(valueClass as Class<out JsonNode>)

                // Resolve schema model for column value-class type
                else -> {
                    // Resolve schema as $ref using ModelConverters. Swagger-core uses Jackson 2, so it is given the
                    // plain Java class instead of a Jackson 3 JavaType, which Jackson 2 cannot resolve
                    val converters = ModelConverters.getInstance()
                    val annotatedType = AnnotatedType(valueClass).resolveAsRef(true)
                    val resolved = converters.resolveAsResolvedSchema(annotatedType)

                    // If resolved schema has a $ref, relativize the references,
                    // and register the referenced schemas in components
                    val dtoPrefix = "kiwi.dto."
                    val resolvedSchema = resolved.schema ?: return@computeIfAbsent ArbitrarySchema()
                    resolvedSchema.relativize(prefix = dtoPrefix) // mutates resolvedSchema
                    resolved.referencedSchemas.forEach { (key, schema) ->
                        schema.relativize(dtoPrefix) // mutates schema
                        components.addSchemas(dtoPrefix + key, schema)
                    }
                    return@computeIfAbsent resolvedSchema
                }
            }
        }
    }

    enum class CommandClassRole { Create, Update, Delete }

    data class CommandClassModels(
        val requestModel: Schema<*>,
        val responseModel: Schema<*>,
    )
}

private fun jsonNodeTypeModel(jsonNodeType: Class<out JsonNode>) = when {
    StringNode::class.java.isAssignableFrom(jsonNodeType) -> StringSchema()
    BooleanNode::class.java.isAssignableFrom(jsonNodeType) -> BooleanSchema()
    IntNode::class.java.isAssignableFrom(jsonNodeType) -> IntegerSchema().format("int32")
    LongNode::class.java.isAssignableFrom(jsonNodeType) -> IntegerSchema().format("int64")
    ShortNode::class.java.isAssignableFrom(jsonNodeType) -> IntegerSchema()
    DoubleNode::class.java.isAssignableFrom(jsonNodeType) -> NumberSchema().format("double")
    FloatNode::class.java.isAssignableFrom(jsonNodeType) -> NumberSchema().format("float")
    DecimalNode::class.java.isAssignableFrom(jsonNodeType) -> NumberSchema()
    NullNode::class.java.isAssignableFrom(jsonNodeType) -> Schema<Any>().nullable(true)
    ArrayNode::class.java.isAssignableFrom(jsonNodeType) -> ArraySchema()
    ObjectNode::class.java.isAssignableFrom(jsonNodeType) -> ObjectSchema()
    POJONode::class.java.isAssignableFrom(jsonNodeType) -> ObjectSchema()
    BinaryNode::class.java.isAssignableFrom(jsonNodeType) -> BinarySchema()
    else -> ArbitrarySchema()
}

private fun componentSchemaRef(entitySchemaKey: String): Schema<*> {
    return refSchema("#/components/schemas/$entitySchemaKey")
}

private fun entitySchemaKey(schema: AbstractSchema<*, *>, requestBody: Boolean): String {
    val key = "kiwi.entity.${schema.entityClass.name}"
    return if (requestBody) "$key.request" else key
}

internal fun entityIdSchema(entityIdClass: Class<*>? = null) = StringSchema().apply {
    format("base32")
    entityIdClass?.let { description(it.simpleName) }
}

internal fun OpenAPI.getOrCreateComponents(): Components {
    return components ?: Components().also { components = it }
}

internal fun <T> Schema<T>.withNullable(isNullable: Boolean = true): Schema<T> =
    composeWith(ArbitrarySchema().nullable(isNullable))

internal fun <T> Schema<T>.composeWith(vararg additionalSchemas: Schema<*>): Schema<T> {
    val composed = Schema<T>()
    composed.addAllOfItem(this)
    additionalSchemas.forEach { composed.addAllOfItem(it) }
    return composed
}

internal fun refSchema(ref: String): Schema<*> {
    return Schema<Any>().`$ref`(ref)
}

internal fun Schema<*>.relativize(prefix: String = "", suffix: String = "") {
    // Relativize path of $ref in schema in a ref-schema (which is a leaf node)
    `$ref` = `$ref`?.let { ref -> relativeRef(ref = ref, prefix = prefix, suffix = suffix) }

    // Propagate relativization to child schemata
    properties?.forEach { (_, propertySchema) -> propertySchema.relativize(prefix, suffix) }
    additionalItems?.relativize(prefix, suffix)
    additionalProperties?.let { it as? Schema<*> }?.relativize(prefix, suffix)
    not?.relativize(prefix, suffix)
    items?.relativize(prefix, suffix)
    prefixItems?.forEach { itemSchema -> itemSchema.relativize(prefix, suffix) }
    allOf?.forEach { itemSchema -> itemSchema.relativize(prefix, suffix) }
    anyOf?.forEach { itemSchema -> itemSchema.relativize(prefix, suffix) }
    oneOf?.forEach { itemSchema -> itemSchema.relativize(prefix, suffix) }
}

/**
 * Relativize "#/components/schemas/example"
 * with prefix = "foo." and suffix = ".bar"
 * into "#/components/schemas/foo.example.bar"
 */
private fun relativeRef(ref: String, prefix: String = "", suffix: String = ""): String {
    val tailIndex = ref.lastIndexOf('/')
    if (tailIndex < 0) return idempotentPrependAndAppend(ref, prefix = prefix, suffix = suffix)

    val head = ref.take(tailIndex + 1)
    val tail = ref.drop(tailIndex + 1)

    return head + idempotentPrependAndAppend(tail, prefix = prefix, suffix = suffix)
}

private fun idempotentPrependAndAppend(value: String, prefix: String, suffix: String): String {
    val maxCapacity = prefix.length + value.length + suffix.length
    return buildString(capacity = maxCapacity) {
        if (!value.startsWith(prefix)) append(prefix)
        append(value)
        if (!value.endsWith(suffix)) append(suffix)
    }
}
