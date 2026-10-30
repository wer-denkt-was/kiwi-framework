package de.kiwi.api.generic

import de.kiwi.api.domain.*
import de.kiwi.api.schema.*
import de.kiwi.api.util.lookupSupertypeArgument
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import kotlin.reflect.KProperty0

/**
 * Abstract base [Command] for handling entity commands using an [EntityFieldset] for representing entity details.
 * @param E Entity class associated with this command
 */
abstract class GenericEntityCommand<E : Any> : Command<JsonNode>(), Authorized {

    /** Entity class of [E] associated with this [GenericEntityCommand] */
    val entityClass: Class<E> by lazy {
        @Suppress("UNCHECKED_CAST")
        javaClass.lookupSupertypeArgument<GenericEntityCommand<*>>(0) as Class<E>
    }

    /** Entity details provider (e.g. a fieldset) used to extract details of created/updated/deleted entity instance */
    abstract val detailsProvider: JsonDetailsProvider<E>
}

/**
 * Abstract base [Command] of [GenericCreate] and [GenericUpdate], declaring the properties of the JSON request body.
 *
 * Body properties are declared explicitly using delegates, each registering a [GenericBodyField] when the command
 * is instantiated:
 * - `val x by schema(ExampleSchema.x)`: value of an [EntitySchema] column, parsed by its [ColumnType],
 *   including the auto-validation of the column. The JSON key is the column field name.
 * - `val x by property<T>()`: value of type `T` parsed by Jackson, the JSON key is the property name.
 * - `val x by reference(ExampleSchema.parent)`: [EntityId] of the entity referenced by a to-one relation.
 * - `val x by references(ExampleSchema.children)`: [EntityId]s of the entities referenced by a to-many relation.
 * - `val x by subtype(ExampleSchema.type)`: entity subclass selected by an `entityType()`/`entityDiscriminator()`
 *   column, among the subtypes declared in the entity schema.
 *
 * Properties of a non-null type are required by create commands, unless declared with a `default(...)`.
 * Nullable properties (or `optional()` ones) are `null` when absent. A JSON `null` is only accepted by nullable
 * properties, which also refer to a nullable column or relation.
 *
 * The command is bound to a JSON body with [bind]: All properties are parsed at once, failing with a single
 * [EntityBodyInvalid] exception listing all invalid, missing and unknown fields.
 *
 * **Note**: Subclasses *must* provide a no-argument constructor!
 * @param E Entity type of the command
 */
abstract class GenericBodyCommand<E : Any> internal constructor() : GenericEntityCommand<E>() {
    private val fields = mutableMapOf<String, GenericBodyField<*>>()
    private val values = mutableMapOf<GenericBodyField<*>, Any?>()

    /** If `true`, the body follows patch-semantics (i.e. differentiate between absent keys and `null` values) */
    protected abstract val patchMode: Boolean

    /** JSON body this command has been bound to, see [bind]. */
    lateinit var jsonBody: JsonNode
        private set

    /** Body fields declared by this command, in declaration order. */
    val bodyFields: List<GenericBodyField<*>> get() = fields.values.toList()

    /** `true` if this command has been bound to a JSON body. */
    val isBound: Boolean get() = this::jsonBody.isInitialized

    /**
     * Bind this command to the given JSON body, parsing all declared body properties.
     * Called by the generic entity endpoints; use it for dispatching generic commands programmatically and in tests.
     * @throws EntityBodyInvalid If any property is invalid or missing, or the body contains unknown keys.
     */
    fun bind(json: JsonNode, objectMapper: ObjectMapper) {
        if (!json.isObject) {
            throw EntityBodyInvalid(entityClass, listOf(EntityMismatchedInput(entityClass, emptyList())))
        }

        val context = GenericBodyContext(entityClass, objectMapper)
        val errors = mutableListOf<DomainException>()

        // Unknown keys are rejected, every accepted key must be declared as a body property
        val declaredKeys = fields.values.mapTo(HashSet(fields.size)) { it.jsonKey }
        json.propertyNames().filterNot { it in declaredKeys }.forEach { key ->
            errors.add(EntityFieldUnknown(entityClass, key))
        }

        val boundValues = mutableMapOf<GenericBodyField<*>, Any?>()
        for ((_, field) in fields) {
            val node = json[field.jsonKey]

            // Absent keys are not patched in update commands
            if (patchMode && node == null) continue

            // Bind and assign value
            try {
                boundValues[field] = field.bindValue(node, context)
            } catch (ex: DomainException) {
                errors.add(ex)
            }
        }

        if (errors.isNotEmpty()) {
            throw EntityBodyInvalid(entityClass, errors)
        }

        values.clear()
        values.putAll(boundValues)
        jsonBody = json
    }

    // --- Body property delegates ---

    /**
     * Declare a body property for the given [column], parsed by the column type.
     * The JSON key is the column field name. Read-only columns (e.g. primary keys, or columns of `val` entity
     * properties) are supported, for using their value in the command handler, but can't be patched using
     * [GenericUpdate.patchFields]. Use [subtype] for `entityType()`/`entityDiscriminator()` columns.
     */
    protected fun <C : E, T, M> schema(column: ColumnField<C, T, M>) =
        GenericBodyFieldBuilder<T>(column.fieldName, GenericBodyFieldSource.Schema(column))

    /**
     * Declare a body property of type [T], parsed by Jackson.
     * @param key JSON key of the property, defaults to the property name
     */
    protected fun <T> property(key: String? = null) =
        GenericBodyFieldBuilder<T>(key, GenericBodyFieldSource.Property)

    /**
     * Declare a body property for the [EntityId] of the entity referenced by the to-one relation [toOne].
     * The JSON key is the relation field name. Loading the referenced entity is up to the command handler.
     */
    protected fun <C : E, R : BaseEntity<ID>, ID : EntityId> reference(toOne: ToOneField<C, out R?>) =
        GenericBodyFieldBuilder<ID>(toOne.fieldName, GenericBodyFieldSource.ToOneReference(toOne))

    /**
     * Declare a body property for the [EntityId]s of the entities referenced by the to-many relation [toMany],
     * as a JSON array. The JSON key is the relation field name.
     */
    protected fun <C : E, R : BaseEntity<ID>, ID : EntityId> references(toMany: ToManyField<C, *, out R?, *>) =
        GenericBodyFieldBuilder<List<ID>>(toMany.fieldName, GenericBodyFieldSource.ToManyReferences(toMany))

    /**
     * Declare a body property selecting the entity class to create among the schema subtypes,
     * by the value of the given `entityType()` or `entityDiscriminator()` [column].
     * The selectable classes are the non-abstract entity class of the schema and of its (nested) `subtype(...)`
     * schemas. The JSON key is the column field name.
     */
    protected fun subtype(column: ColumnField<E, Class<*>, String>) =
        GenericBodyFieldBuilder<Class<out E>>(column.fieldName, GenericBodyFieldSource.Subtype(column))

    // --- Internal field registry ---

    internal fun registerField(field: GenericBodyField<*>) {
        val existingField = fields.values.find { it.jsonKey == field.jsonKey }
        if (existingField != null) {
            val jsonKey = field.jsonKey
            val duplicateName = field.propertyName
            val existingName = existingField.propertyName
            throw IllegalArgumentException("Body properties '$existingName' and '$duplicateName' of ${javaClass.name} use the same JSON key '$jsonKey'")
        }

        fields[field.propertyName] = field
    }

    internal fun <T> valueOf(field: GenericBodyField<T>): T {
        checkBound()
        if (field !in values) {
            throw GenericCommandMisconfigured("Body property ${field.propertyName} of ${javaClass.name} has not been set, use ifPatched(...)")
        }

        @Suppress("UNCHECKED_CAST")
        return values[field] as T
    }

    protected fun isPresent(field: GenericBodyField<*>): Boolean {
        checkBound()
        return field in values
    }

    protected fun fieldOf(property: KProperty0<*>): GenericBodyField<*> {
        return fields[property.name]
            ?: throw GenericCommandMisconfigured("${property.name} is not a body property of ${javaClass.name}")
    }

    private fun checkBound() {
        if (!isBound) throw GenericCommandMisconfigured("Generic command ${javaClass.name} has not been bound to a JSON body")
    }
}

/**
 * Abstract [Command] for handling generic HTTP POST requests to create an entity,
 * using an [EntityFieldset] or other details-provider for retrieving values from the created entity.
 * The JSON request body is declared by body properties, see [GenericBodyCommand].
 *
 * **Note**: Subclasses *must* provide a no-argument constructor!
 *
 * Example:
 * ```kt
 * class CreateExample : GenericCreate<ExampleEntity>() {
 *   override val detailsProvider = ExampleFieldsets.default
 *
 *   val name by schema(ExampleSchema.name)
 *   val parent by reference(ExampleSchema.parent)
 *   val notify by property<Boolean>().default(false)
 *
 *   override fun preAuthorize(user: UserDescriptor) = TODO("Authorize command")
 * }
 * ```
 * @param E Entity type to be created
 * @see de.kiwi.api.application.generic.GenericCreateHandler
 */
abstract class GenericCreate<E : Any> : GenericBodyCommand<E>() {
    override val patchMode get() = false

    /** ID of the created entity, the [BaseEntity.id] by default. */
    open fun entityId(entity: E): EntityId = when (entity) {
        is BaseEntity<*> -> entity.id
        else -> {
            val entityClassName = entity.javaClass.name
            val baseClassName = BaseEntity::class.java.name
            throw AssertionError("Entity of type $entityClassName does not extend $baseClassName; must override entityId(entity) in ${javaClass.name}")
        }
    }
}

/**
 * Abstract [Command] for handling generic HTTP PATCH requests to update entities,
 * using an [EntityFieldset] or other details-provider for retrieving values from the updated entity.
 * The JSON request body is declared by body properties, see [GenericBodyCommand].
 *
 * Updates are patches: Body properties are only set if their key is present in the JSON body,
 * reading an absent property fails. Use [isPatched], [ifPatched], [ifPatchedNotNull] and [patchFields]
 * to apply the present properties.
 *
 * **Note**: Subclasses *must* provide a no-argument constructor!
 *
 * Example:
 * ```kt
 * class UpdateExample : GenericUpdate<ExampleEntity, ExampleId>() {
 *   override val detailsProvider = ExampleFieldsets.default
 *
 *   val name by schema(ExampleSchema.name)
 *   val order by schema(ExampleSchema.order)
 *
 *   override fun preAuthorize(user: UserDescriptor) = TODO("Authorize command")
 * }
 * ```
 * @param E Entity type to be updated
 * @param ID EntityId type of entity to be updated
 * @see de.kiwi.api.application.generic.GenericUpdateHandler
 */
abstract class GenericUpdate<E : Any, ID : EntityId> : GenericBodyCommand<E>() {
    lateinit var entityId: ID

    override val patchMode get() = true

    /** `true` if the given body [property] is present in the JSON body. */
    fun isPatched(property: KProperty0<*>): Boolean = isPresent(fieldOf(property))

    /** Execute [then] with the value of the given body [property], if it is present in the JSON body. */
    fun <T> ifPatched(property: KProperty0<T>, then: (T) -> Unit) {
        if (isPatched(property)) then(property.get())
    }

    /** Execute [then] with the value of the given body [property], if it is present in the JSON body and non-null. */
    fun <T> ifPatchedNotNull(property: KProperty0<T?>, then: (T & Any) -> Unit) {
        if (isPatched(property)) property.get()?.let(then)
    }

    /**
     * Write the values of the given `schema(...)` body [properties] to the [entity], if present in the JSON body.
     * The properties are checked before any value is written, independently of the JSON body, so a misconfigured
     * command fails on every request.
     * @throws EntityFieldWriteNotAllowed If the column belongs to a subtype the entity is not an instance of.
     * @throws GenericCommandMisconfigured If a property is not a `schema(...)` property, or its column is read-only.
     */
    fun patchFields(entity: E, vararg properties: KProperty0<*>) {
        properties.map { property ->
            val field = fieldOf(property)
            val schemaSource = field.source as? GenericBodyFieldSource.Schema
                ?: throw GenericCommandMisconfigured("${property.name} of ${javaClass.name} is not a schema(...) body property")
            if (schemaSource.schemaField.isReadOnly) {
                throw GenericCommandMisconfigured("${property.name} of ${javaClass.name} refers to a read-only column, and can't be patched")
            }
            field to schemaSource
        }.forEach { (field, schemaSource) ->
            patchField(entity, field, schemaSource)
        }
    }

    private fun patchField(entity: E, field: GenericBodyField<*>, source: GenericBodyFieldSource.Schema) {
        if (!isPresent(field)) return

        @Suppress("UNCHECKED_CAST")
        val column = source.schemaField as ColumnField<Any, Any?, *>
        if (!column.parentSchema.entityClass.isInstance(entity)) {
            throw EntityFieldWriteNotAllowed(entityClass, field.jsonKey)
        }

        column.writeToEntity(entity, valueOf(field))
    }
}

/**
 * Thrown if a generic command is used contrary to its declaration, e.g. patching a read-only `schema(...)` property,
 * or reading a property that is absent in the JSON body of an update.
 * This is a server-side programming error, and is not handled as a [DomainException]: The request fails with
 * `500 Internal Server Error`.
 */
class GenericCommandMisconfigured(message: String) : RuntimeException(message)

/**
 * Abstract [Command] for handling generic HTTP DELETE requests to delete entities,
 * using an [EntityFieldset] for retrieving details from the deleted entity.
 *
 * **Note**: Subclasses must provide a no-argument constructor!
 *
 * Example:
 * ```kt
 * class DeleteExample : GenericDelete<ExampleEntity, ExampleId>() {
 *   override val detailsProvider = ExampleFieldsets.default
 *   override fun preAuthorize(user: UserDescriptor) = TODO("Authorize command")
 * }
 * ```
 * @param E Entity type to be deleted
 * @param ID EntityId type of entity to be deleted
 * @see de.kiwi.api.application.generic.GenericDeleteHandler
 */
abstract class GenericDelete<E : Any, ID : EntityId> : GenericEntityCommand<E>() {
    lateinit var entityId: ID
}
