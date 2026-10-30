package de.kiwi.api.generic

import de.kiwi.api.domain.*
import de.kiwi.api.schema.*
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty
import kotlin.reflect.KType
import kotlin.reflect.jvm.javaType
import kotlin.reflect.jvm.jvmErasure
import java.lang.reflect.Modifier as JavaModifier

/**
 * Body property of a [GenericBodyCommand], declared using one of the body property delegates.
 * Describes the JSON key and value of the property, e.g. for API documentation and frontend code generation.
 * @param T Kotlin type of the property
 */
class GenericBodyField<T> internal constructor(
    /** Name of the declared Kotlin property */
    val propertyName: String,
    /** JSON key of the property in the request body */
    val jsonKey: String,
    /** Source of the property value, i.e. the body property delegate used to declare the property */
    val source: GenericBodyFieldSource,
    /** Declared Kotlin type of the property */
    val propertyType: KType,
    /** Type of the JSON value, before conversion into [propertyType] */
    val valueType: Type,
    /** EntityId type of the referenced entities, for [GenericBodyFieldSource.ToOneReference] and [GenericBodyFieldSource.ToManyReferences] */
    val referencedIdType: Class<out EntityId>?,
    /** `true` if a JSON `null` value is accepted */
    val nullable: Boolean,
    /** `true` if the key is required in create commands; update commands never require a key */
    val required: Boolean,
    private val defaultValue: GenericBodyDefaultValueProvider?,
    private val validation: Validation<Any>?,
) {
    internal fun bindValue(node: JsonNode?, context: GenericBodyContext): T {
        val value = when {
            // Key is absent
            node == null || node.isMissingNode -> when {
                defaultValue != null -> defaultValue()
                required -> throw EntityFieldMissingValue(context.entityType, jsonKey)
                else -> null
            }

            // Key is present, but null-valued
            node.isNull -> {
                if (nullable) null
                else throw EntityFieldMissingValue(context.entityType, jsonKey)
            }

            // Key is present and non-null valued
            else -> parseAndValidate(node, context)
        }

        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun parseAndValidate(node: JsonNode, context: GenericBodyContext): Any? {
        val parsedValue = source.parse(node, this, context) ?: return null
        if (validation == null) return parsedValue

        return try {
            validation.validate(parsedValue).getOrElse { reason ->
                throw EntityFieldInvalid(context.entityType, jsonKey, reason)
            }
        } catch (ex: ValidationFailed) {
            throw EntityFieldInvalid(context.entityType, jsonKey, ex.reason)
        }
    }

    override fun toString() =
        "GenericBodyField(${source.javaClass.simpleName} $propertyName: $propertyType, key = $jsonKey)"
}

/**
 * Source of the value of a [GenericBodyField], determined by the body property delegate used to declare it.
 */
sealed interface GenericBodyFieldSource {
    /** `true` if the source accepts a JSON `null` value, if the property type is nullable too */
    val acceptsNull: Boolean

    /** Schema field the body property refers to, `null` for [Property] */
    val schemaField: EntitySchemaField<*>? get() = null

    /** Type of the JSON value of a property of the given [propertyType] */
    fun valueType(propertyType: KType): Type

    /** EntityId type of the referenced entities of a property of the given [propertyType], for references only */
    fun referencedIdType(propertyType: KType): Class<out EntityId>? = null

    /** Parse the given non-null JSON [node] into the value of the given [field]. */
    fun parse(node: JsonNode, field: GenericBodyField<*>, context: GenericBodyContext): Any?

    /**
     * Value of a column, declared using `schema(...)`.
     * Read-only columns are supported, but can't be written to the entity using [GenericUpdate.patchFields].
     */
    class Schema
    internal constructor(override val schemaField: ColumnField<*, *, *>) : GenericBodyFieldSource {
        init {
            require(schemaField.selector !is ColumnSelector.EntityTypeSelector<*>) {
                "Column ${schemaField.fieldName} of ${schemaField.parentSchema.entityClass.name} selects the entity type, use subtype(...)"
            }
        }

        override val acceptsNull get() = schemaField.nullable

        override fun valueType(propertyType: KType): Type = schemaField.type.valueType

        override fun parse(node: JsonNode, field: GenericBodyField<*>, context: GenericBodyContext) =
            schemaField.parseJsonValue(node, field.jsonKey, context)
    }

    /** Value parsed by Jackson, declared using `property<T>()`. */
    data object Property : GenericBodyFieldSource {
        override val acceptsNull get() = true

        override fun valueType(propertyType: KType): Type = propertyType.javaType

        override fun parse(node: JsonNode, field: GenericBodyField<*>, context: GenericBodyContext): Any? =
            context.parseJson(node, field.valueType, field.jsonKey)
    }

    /** Entity class selected by an entity type column, declared using `subtype(...)`. */
    class Subtype
    internal constructor(override val schemaField: ColumnField<*, Class<*>, String>) : GenericBodyFieldSource {
        init {
            require(schemaField.selector is ColumnSelector.EntityTypeSelector<*>) {
                "Column ${schemaField.fieldName} of ${schemaField.parentSchema.entityClass.name} does not select the entity type"
            }
        }

        /** Selectable entity classes by their JSON value */
        val subtypes: Map<String, Class<*>> = schemaField.parentSchema
            .selectableSubtypes()
            .associateBy { schemaField.type.format(it) }

        init {
            require(subtypes.isNotEmpty()) {
                "Schema of ${schemaField.parentSchema.entityClass.name} has no non-abstract entity class to select"
            }
        }

        override val acceptsNull get() = false

        override fun valueType(propertyType: KType): Type = String::class.java

        override fun parse(node: JsonNode, field: GenericBodyField<*>, context: GenericBodyContext): Class<*> {
            if (!node.isString) throw EntityMismatchedInput(context.entityType, listOf(field.jsonKey))
            return subtypes[node.stringValue()]
                ?: throw EntityFieldInvalid(context.entityType, field.jsonKey, "unknown-subtype")
        }
    }

    /** [EntityId] of an entity referenced by a to-one relation, declared using `reference(...)`. */
    class ToOneReference
    internal constructor(override val schemaField: ToOneField<*, *>) : GenericBodyFieldSource {
        override val acceptsNull get() = schemaField.nullable

        override fun valueType(propertyType: KType): Type = String::class.java

        override fun referencedIdType(propertyType: KType) = schemaField.toOne.referencedIdType(propertyType)

        override fun parse(node: JsonNode, field: GenericBodyField<*>, context: GenericBodyContext) =
            context.parseEntityId(node, field)
    }

    /** [EntityId]s of the entities referenced by a to-many relation, declared using `references(...)`. */
    class ToManyReferences
    internal constructor(override val schemaField: ToManyField<*, *, *, *>) : GenericBodyFieldSource {
        override val acceptsNull get() = false

        override fun valueType(propertyType: KType): Type = Array<String>::class.java

        override fun referencedIdType(propertyType: KType): Class<out EntityId> {
            val elementType = propertyType.arguments.firstOrNull()?.type
            return schemaField.toMany.referencedIdType(elementType)
        }

        override fun parse(node: JsonNode, field: GenericBodyField<*>, context: GenericBodyContext): List<EntityId> {
            if (!node.isArray) throw EntityMismatchedInput(context.entityType, listOf(field.jsonKey))
            return node.values().map { context.parseEntityId(it, field) }
        }
    }
}

/**
 * Context of binding a [GenericBodyCommand] to a JSON body, see [GenericBodyCommand.bind].
 */
class GenericBodyContext internal constructor(val entityType: Class<*>, val objectMapper: ObjectMapper) {
    /** Parse the given [node] into a value of the given [type], failing with [EntityMismatchedInput]. */
    fun <X> parseJson(node: JsonNode, type: Type, key: String): X = try {
        objectMapper.treeToValue(node, objectMapper.typeFactory.constructType(type))
    } catch (_: JacksonException) {
        throw EntityMismatchedInput(entityType, listOf(key))
    }

    /** Parse the given [node] into an [EntityId] of the [GenericBodyField.referencedIdType] of the given [field]. */
    fun parseEntityId(node: JsonNode, field: GenericBodyField<*>): EntityId {
        val idType = checkNotNull(field.referencedIdType) { "Missing referenced EntityId type of $field" }
        if (!node.isString && !node.isIntegralNumber) throw EntityMismatchedInput(entityType, listOf(field.jsonKey))
        return try {
            EntityId.fromString(node.asString(), idType)
        } catch (_: IllegalArgumentException) {
            throw EntityFieldInvalid(entityType, field.jsonKey, "invalid-entity-id")
        }
    }
}

internal typealias GenericBodyFieldProperty<T> = ReadOnlyProperty<GenericBodyCommand<*>, T>
internal typealias GenericBodyFieldDelegateProvider<T> = PropertyDelegateProvider<GenericBodyCommand<*>, GenericBodyFieldProperty<T>>
internal typealias GenericBodyDefaultValueProvider = () -> Any

/**
 * Builder of a [GenericBodyField], returned by the body property delegates of [GenericBodyCommand].
 * Registers the body field in the command when the property is declared using `by`.
 * @param T Kotlin type of the declared property
 */
class GenericBodyFieldBuilder<T> internal constructor(
    private val jsonKey: String?,
    private val source: GenericBodyFieldSource,
    private val defaultValue: GenericBodyDefaultValueProvider? = null,
    private val validation: Validation<Any>? = null,
) : GenericBodyFieldDelegateProvider<T> {

    /** Declare the property optional in create commands, by making its type nullable: `null` if the key is absent. */
    fun optional(): GenericBodyFieldBuilder<T?> =
        GenericBodyFieldBuilder(jsonKey, source, defaultValue, validation)

    /** Use the given default [value] in create commands if the key is absent. Not applied in update commands. */
    fun default(value: T & Any): GenericBodyFieldBuilder<T & Any> =
        GenericBodyFieldBuilder(jsonKey, source, { value }, validation)

    /** Validate non-null values with the given [validation], failing with [EntityFieldInvalid]. */
    fun validate(validation: Validation<T & Any>): GenericBodyFieldBuilder<T> {
        @Suppress("UNCHECKED_CAST")
        val nextValidation = validation as Validation<Any>
        val combinedValidation = this.validation?.and(nextValidation) ?: nextValidation
        return GenericBodyFieldBuilder(jsonKey, source, defaultValue, combinedValidation)
    }

    override fun provideDelegate(
        thisRef: GenericBodyCommand<*>,
        property: KProperty<*>,
    ): GenericBodyFieldProperty<T> {
        // Body fields are immutable, and declared identically by every instance of a command class
        val cacheKey = FieldCacheKey(thisRef.javaClass, property.name)

        @Suppress("UNCHECKED_CAST")
        val field = fieldCache.computeIfAbsent(cacheKey) { createField(property) } as GenericBodyField<T>

        // Register field in instance, return property reading value from instance
        thisRef.registerField(field)
        return ReadOnlyProperty { command, _ -> command.valueOf(field) }
    }

    private fun createField(property: KProperty<*>): GenericBodyField<T> {
        val type = property.returnType
        val declaredNullable = type.isMarkedNullable
        return GenericBodyField(
            propertyName = property.name,
            jsonKey = jsonKey ?: property.name,
            source = source,
            propertyType = type,
            valueType = source.valueType(type),
            referencedIdType = source.referencedIdType(type),
            nullable = declaredNullable && source.acceptsNull,
            required = !declaredNullable && defaultValue == null,
            defaultValue = defaultValue,
            validation = validation,
        )
    }

    private data class FieldCacheKey(val commandClass: Class<*>, val propertyName: String)

    private companion object {
        val fieldCache = ConcurrentHashMap<FieldCacheKey, GenericBodyField<*>>()
    }
}

private fun <T, M> ColumnField<*, T, M>.parseJsonValue(
    node: JsonNode,
    jsonKey: String,
    context: GenericBodyContext,
): T {
    val formattedValue = context.parseJson<M & Any>(node, type.valueType, jsonKey)
    try {
        // Auto validation only applies to attribute selectors
        @Suppress("UNCHECKED_CAST") val attributeSelector = selector as? ColumnSelector.AttributeSelector<*, T & Any>

        // Lookup auto validation associated with last attribute in the selector path
        val attribute = attributeSelector?.path?.last
        val autoValidation = attribute?.let { ColumnAutoValidation.lookupAutoValidation(it) }

        // Apply auto-validation if available, otherwise use column type as-is
        val validatedType = autoValidation?.let { type.validate(it) } ?: type
        return validatedType.parse(formattedValue)
    } catch (_: ColumnType.ParseNotSupported) {
        throw EntityFieldWriteNotAllowed(context.entityType, jsonKey)
    } catch (ex: ValidationFailed) {
        throw EntityFieldInvalid(context.entityType, jsonKey, ex.reason)
    } catch (_: IllegalArgumentException) {
        throw EntityFieldInvalid(context.entityType, jsonKey, "invalid-value")
    }
}

/**
 * Lookup the selectable subtypes of the given [AbstractSchema] subtypes (which must be an [EntitySchema] for any result
 * to be found) based on the declared [EntitySchema.subtypeReferences].
 * Only concrete (non-abstract/interface) classes are considered to be selectable.
 */
private fun AbstractSchema<*, *>.selectableSubtypes(): List<Class<*>> {
    val entitySchema = this as? EntitySchema ?: return emptyList()
    val entityClass = entitySchema.entityClass
    val subtypeSchemas = entitySchema.subtypeReferences.map { it.subtypeSchema }
    val selectable = !entityClass.isInterface && !JavaModifier.isAbstract(entityClass.modifiers)
    return listOfNotNull(entityClass.takeIf { selectable }) + subtypeSchemas.flatMap { it.selectableSubtypes() }
}

/**
 * Lookup the [EntityId] type of entities of the given [AbstractSchema], from its single primary key column,
 * or otherwise from the declared [propertyType].
 */
private fun AbstractSchema<*, *>.referencedIdType(propertyType: KType?): Class<out EntityId> {
    val entitySchema = this as? EntitySchema<*>
    if (entitySchema != null) {
        val primaryKeyType = primaryKeys.singleOrNull()?.type
        if (primaryKeyType is EntityIdColumnType<*>) {
            return primaryKeyType.entityIdType
        }
    }

    val propertyClass = propertyType?.jvmErasure?.java
    require(propertyClass != null && EntityId::class.java.isAssignableFrom(propertyClass)) {
        "Cannot determine the EntityId type of entities referenced in schema of ${entityClass.name}"
    }

    @Suppress("UNCHECKED_CAST")
    return propertyClass as Class<out EntityId>
}
