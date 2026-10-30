package de.kiwi.api.schema

import de.kiwi.api.domain.Auditable
import de.kiwi.api.domain.DetailsProvider
import de.kiwi.api.domain.DomainException
import de.kiwi.api.domain.UserDescriptor
import tools.jackson.databind.JsonNode
import java.util.*
import kotlin.properties.PropertyDelegateProvider
import kotlin.reflect.KProperty
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.typeOf

/**
 * Abstraction for producing JSON details from a given value.
 * Specialization of [DetailsProvider], returning the [JsonNode] as `Any?` result details.
 */
fun interface JsonDetailsProvider<T : Any> : DetailsProvider<T> {
    fun toJsonDetails(value: T, user: UserDescriptor?): JsonNode

    override fun toDetails(value: T, user: UserDescriptor?): Any? = toJsonDetails(value, user)
}

// --- Fieldset types ---

/**
 * Fieldset definition for a given entity type.
 * This represents a subset of columns/associations of an entity
 * specified by an [AbstractSchema], most commonly an [EntitySchema], of entity type [E].
 * @param E Target type of this fieldset
 */
sealed interface Fieldset<E : Any> : DetailsProvider<E>, JsonDetailsProvider<E> {
    /** Name of this fieldset */
    val name: String

    /** Entity type class underlying the fieldset */
    val entityType: Class<E>

    /**
     * Extract a representational value of this fieldset from the given entity instance,
     * using the [BasicFieldsetSerializer] to serialize lists/objects.
     */
    fun extract(entity: E, user: UserDescriptor? = null): Any? = extract(entity, BasicFieldsetSerializer, user)

    /**
     * Extract a representational JSON value of this fieldset from the given entity instance,
     * using the default [JsonFieldsetSerializer] to serialize values, lists, and objects.
     */
    fun extractJson(entity: E, user: UserDescriptor? = null): JsonNode = extract(entity, JsonFieldsetSerializer, user)

    /**
     * Extract a representational value of this fieldset from the given entity instance,
     * using the given [FieldsetSerializer].
     */
    fun <N> extract(entity: E, serializer: FieldsetSerializer<N>, user: UserDescriptor? = null): N

    /**
     * Implementation of [DetailsProvider], making a fieldset usable as an entity-details provider.
     * If the given [user] is `null`, the [UserDescriptor] used for extraction will be obtained using
     * the [de.kiwi.api.application.service.UserDescriptorProvider.Global] instance.
     */
    override fun toDetails(value: E, user: UserDescriptor?): Any? = extract(value, user)

    /**
     * Implementation of [JsonDetailsProvider], making a fieldset usable as an entity JSON-details provider.
     * If the given [user] is `null`, the [UserDescriptor] used for extraction will be obtained using
     * the [de.kiwi.api.application.service.UserDescriptorProvider.Global] instance.
     */
    override fun toJsonDetails(value: E, user: UserDescriptor?): JsonNode = extractJson(value, user)
}

/**
 * Pair of two fieldsets used for associating keys and values of [ToManyMapField].
 */
typealias FieldsetPair<K, V> = Pair<Fieldset<K>, Fieldset<V>>

/**
 * Fieldset extracting columns, to-one relations, and to-many relations from an [EntitySchema] for entity type [E].
 * @param E Target entity type
 */
data class EntityFieldset<E : Any>(
    override val name: String,
    val schema: EntitySchema<E>,
    val columns: List<ColumnField<E, *, *>>,
    val toOne: Map<ToOneField<E, *>, Fieldset<*>>,
    val toMany: Map<ToManyField<E, *, *, *>, Fieldset<*>>,
    val toManyMap: Map<ToManyMapField<E, *, *, *, *>, FieldsetPair<*, *>>,
    val subtypes: Map<SubtypeField<E, *, *>, Fieldset<*>>,
) : Fieldset<E> {
    override val entityType by schema::entityClass

    override fun <N> extract(entity: E, serializer: FieldsetSerializer<N>, user: UserDescriptor?): N {
        val extractor = EntityFieldsetExtractor(this, serializer)
        return extractor.extract(entity, user)
    }

    override fun toString() = buildString {
        val entityType = schema.entityClass.name
        val columnList = columns.joinToString(", ") { it.fieldName }
        val toOneList = toOne.entries.joinToString(", ") { (toOne, ref) ->
            "${toOne.fieldName}:${ref.name}"
        }
        val toManyItems = toMany.entries.map { (toMany, ref) ->
            "${toMany.fieldName}:${ref.name}"
        }
        val toManyMapItems = toManyMap.entries.map { (toMany, refs) ->
            "${toMany.fieldName}:(${refs.first.name}, ${refs.second.name})"
        }
        val toManyList = (toManyItems + toManyMapItems).joinToString(", ")
        val subtypeList = subtypes.entries.joinToString { (subtype, subFieldset) ->
            "${subtype.fieldName}:${subFieldset.name}"
        }

        appendLine("Fieldset(\"$name\" = Entity($entityType)) {")
        appendLine("  columns = [$columnList]")
        appendLine("  toOne = [$toOneList]")
        appendLine("  toMany = [$toManyList]")
        appendLine("  subtypes = [$subtypeList]")
        append("}")
    }

    override fun equals(other: Any?) = when {
        this === other -> true
        other !is EntityFieldset<*> -> false
        else -> (name == other.name) && (schema == other.schema)
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + schema.hashCode()
        return result
    }
}

/**
 * Fieldset representing an entity [E] as a single value of type [S].
 * @param E Source entity type
 * @param S Target value type
 */
data class SingleValueFieldset<E : Any, S>(
    override val name: String,
    val schema: SingleValueSchema<E, S>,
) : Fieldset<E> {
    override val entityType by schema::entityClass

    override fun <N> extract(entity: E, serializer: FieldsetSerializer<N>, user: UserDescriptor?): N {
        return serializer.serializeValue(schema.toValue(entity))
    }

    override fun toString(): String {
        val entityType = schema.entityClass.name
        return "Fieldset(\"$name\" = SingleValue($entityType))"
    }

    override fun equals(other: Any?) = when {
        this === other -> true
        other !is SingleValueFieldset<*, *> -> false
        else -> (name == other.name) && (schema == other.schema)
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + schema.hashCode()
        return result
    }
}

/**
 * Fieldset extracting the value of type [M] from a single column of an entity of type [E].
 */
data class SingleColumnFieldset<E : Any, T, M>(
    override val name: String,
    val singleColumn: ColumnField<E, T, M>,
) : Fieldset<E> {
    val schema: AbstractSchema<E, *>
        get() = singleColumn.parentSchema

    override val entityType by schema::entityClass

    override fun <N> extract(entity: E, serializer: FieldsetSerializer<N>, user: UserDescriptor?): N {
        val fieldAccess = singleColumn.parentSchema.fieldAccessControl(singleColumn)
        val hasAccess = (fieldAccess == null) || run {
            val context = EntityFieldAccessControlContext(entity)
            user?.let { fieldAccess.checkAccess(context, it) }
                ?: fieldAccess.checkAccess(context)
        }
        return if (hasAccess) {
            serializer.serializeValue(singleColumn.readFromEntity(entity))
        } else {
            serializer.serializeNull
        }
    }

    override fun toString(): String {
        val entityType = singleColumn.parentSchema.entityClass.name
        val columnName = singleColumn.fieldName
        return "Fieldset(\"$name\" = SingleColumn($entityType::$columnName))"
    }

    override fun equals(other: Any?) = when {
        this === other -> true
        other !is SingleColumnFieldset<*, *, *> -> false
        else -> (name == other.name) && (singleColumn == other.singleColumn)
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + singleColumn.hashCode()
        return result
    }
}

// --- Fieldset aggregations ---

/**
 * Aggregation of named fieldsets available for a given [EntitySchema] of entity type [E].
 */
abstract class FieldsetsAvailable<E : Any>(
    val schema: EntitySchema<E>,
) {
    /**
     * Default fieldset for entity
     */
    abstract val default: Fieldset<E>

    /**
     * Map of all fieldsets defined in this fieldset aggregation
     */
    val allFieldsets: List<Fieldset<E>> get() = fieldsetsByLowercaseName.values.toList()

    /**
     * Get the fieldset with the given name ignoring case,
     * or throws [NoSuchFieldset]  if no fieldset with the given name exists.
     */
    @Throws(NoSuchFieldset::class)
    fun require(name: String): Fieldset<E> {
        return get(name) ?: throw NoSuchFieldset(name, fieldsetsByLowercaseName.keys)
    }

    /**
     * Get the fieldset with the given name ignoring case,
     * or `null` if no fieldset with the given name exists.
     */
    operator fun get(name: String): Fieldset<E>? {
        if (name.equals(::default.name, ignoreCase = true)) {
            return default
        }
        return fieldsetsByLowercaseName[name.lowercase()]
    }

    /**
     * Convenience function for using the default entity to generate entity details
     */
    @Deprecated(
        message = "Uses default fieldset implicitly, use explicit fieldset instead",
        replaceWith = ReplaceWith("default.extract(entity)"),
    )
    fun entityInfo(entity: E) = default.extract(entity, null) ?: emptyMap<String, Any>()

    override fun toString(): String {
        val entityType = schema.entityClass.name
        val fieldsetList = allFieldsets.joinToString(", ") { it.name }
        return "FieldsetsAvailable($entityType: [$fieldsetList])"
    }

    /**
     * Delegate for building an [EntityFieldset] with the name of the given property.
     */
    protected fun defineFieldset(build: BuildFieldset<E>) = namedLazy { property ->
        schema.buildFieldset(property.name, build)
    }

    /**
     * Delegate for creating a [SingleColumnFieldset] with the name of the given property.
     */
    protected fun <R, M> singleColumnFieldset(singleColumn: ColumnField<E, R, M>) = namedLazy { property ->
        singleColumn.columnFieldset(property.name)
    }

    /**
     * Delegate for creating a [SingleValueFieldset] of the given relation with the name of the given property.
     */
    protected fun <S : Any> singleValueFieldset(
        toMany: ToManyField<E, *, S, S>,
    ) = when (val referenced = toMany.toMany) {
        is SingleValueSchema<*, *> -> namedLazy { property ->
            @Suppress("UNCHECKED_CAST")
            SingleValueFieldset(property.name, referenced as SingleValueSchema<E, S>)
        }

        else -> {
            val entityType = referenced.entityClass.name
            throw AssertionError("Tried to use EntitySchema<$entityType> for a single-value fieldset")
        }
    }

    private val fieldsetsByLowercaseName: Map<String, Fieldset<E>> by lazy {
        fieldsetProperties.associate { it.name.lowercase() to createFieldsetFromProperty(it) }
    }

    private val fieldsetProperties by lazy {
        this::class.declaredMemberProperties.filter {
            it.returnType.isSubtypeOf(typeOf<Fieldset<*>>())
        }
    }

    private fun createFieldsetFromProperty(property: KProperty<*>): Fieldset<E> {
        val name = property.name
        val fieldset = property.getter.call(this)
            ?: throw NullPointerException("Fieldset property $name returned null")
        if (fieldset !is Fieldset<*>) {
            val wrongType = fieldset.javaClass.name
            val rightType = Fieldset::class.java.name
            throw AssertionError("Fieldset property $name returned $wrongType instead of $rightType")
        }

        fun incompatibleFieldset(actualSchema: AbstractSchema<*, *>): Nothing {
            val typeName = fieldset.javaClass.simpleName
            val expectedEntityType = schema.entityClass.name
            val actualEntityType = actualSchema.entityClass.name
            throw AssertionError("Incompatible $typeName from $name: Expected schema referring to $expectedEntityType, but got $actualEntityType")
        }

        @Suppress("UNCHECKED_CAST")
        return when (fieldset) {
            is EntityFieldset<*> ->
                if (fieldset.schema.entityClass == schema.entityClass) fieldset as EntityFieldset<E>
                else incompatibleFieldset(fieldset.schema)

            is SingleColumnFieldset<*, *, *> ->
                if (fieldset.schema.entityClass == schema.entityClass) fieldset as SingleColumnFieldset<E, *, *>
                else incompatibleFieldset(fieldset.singleColumn.parentSchema)

            is SingleValueFieldset<*, *> ->
                if (fieldset.schema.entityClass == schema.entityClass) fieldset as SingleValueFieldset<E, *>
                else incompatibleFieldset(fieldset.schema)
        }
    }
}

data class NoSuchFieldset(val name: String, val availableFieldsets: Set<String>) : DomainException(
    status = Status.BadRequest,
    code = "no-such-fieldset",
    args = mapOf("name" to name, "availableFieldsets" to availableFieldsets.toList()),
) {
    override val severity = Auditable.Severity.Disabled // don't audit log
}

// --- Fieldset builder ---

typealias BuildFieldset<E> = FieldsetBuilder<E>.() -> Unit

/**
 * Build a new [EntityFieldset] based on this [EntitySchema].
 */
inline fun <E : Any> EntitySchema<E>.buildFieldset(
    name: String = anonymousFieldsetName(),
    build: BuildFieldset<E>,
): EntityFieldset<E> {
    return FieldsetBuilderContext(this).let {
        it.build()
        it.createFieldset(name)
    }
}

fun <E : Any, T, M> EntitySchema<E>.singleColumnFieldset(
    name: String = anonymousFieldsetName(),
    singleColumn: ColumnField<E, T, M>,
): SingleColumnFieldset<E, T, M> {
    return singleColumn.columnFieldset(name)
}

/**
 * Generate an implicit default [EntityFieldset] based on this [EntitySchema],
 * including all columns but no relations of the schema.
 */
fun <E : Any> EntitySchema<E>.implicitDefaultFieldset(): EntityFieldset<E> {
    // Implicit fieldset (used if no FieldsetsAvailable is declared) includes all columns, but no relations
    return buildFieldset("<implicit>") { includeAllColumns() }
}

/**
 * Generate a [SingleColumnFieldset] for this [ColumnField].
 */
fun <E : Any, T, M> ColumnField<E, T, M>.columnFieldset(
    name: String = anonymousFieldsetName(),
): SingleColumnFieldset<E, T, M> =
    SingleColumnFieldset(name, this)

fun <E : Any, S> SingleValueSchema<E, S>.fieldset(
    name: String = anonymousFieldsetName(),
): SingleValueFieldset<E, S> =
    SingleValueFieldset(name, this)

/**
 * Build a new [EntityFieldset] extending this existing [EntityFieldset].
 */
inline fun <E : Any> EntityFieldset<E>.extend(
    name: String = anonymousFieldsetName(),
    build: BuildFieldset<E>,
): EntityFieldset<E> {
    return FieldsetBuilderContext(schema).let {
        it.extendsFieldset(this)
        it.build()
        it.createFieldset(name = "$name : ${this.name}")
    }
}

@PublishedApi
internal fun anonymousFieldsetName(): String {
    // The generated name must **not** be a valid Java identifier!
    // This way, anonymous fieldsets will be declared as inline types in generated external source files.
    return "<anonymous-${UUID.randomUUID()}>"
}

/**
 * Builder context for defining fieldsets.
 */
interface FieldsetBuilder<E : Any> {
    /** Extend another fieldset, inheriting all columns, to-one and to-many relations of the given fieldset. */
    fun extendsFieldset(other: Fieldset<E>)

    /** Include all [ColumnField]s of the [EntitySchema] this fieldset builder is based on. */
    fun includeAllColumns()

    /** Include all Primary Key [ColumnField]s of the [EntitySchema] this fieldset builder is based on. */
    fun includePrimaryKeys()

    /** Include the given [ColumnField]s in the fieldset. */
    fun include(vararg column: ColumnField<E, *, *>)

    /** Include all [ColumnField]s of the given collection in the fieldset. */
    fun include(columns: Iterable<ColumnField<E, *, *>>)

    /** Include the [ToOneField] relation using the given referenced fieldset. */
    fun <R : Any> includeToOne(toOne: ToOneField<E, in R>, fieldset: Fieldset<R>)

    /** Include the [ToManyField] relation using the given referenced fieldset. */
    fun <R : Any> includeToMany(toMany: ToManyField<E, *, out R, *>, fieldset: Fieldset<R>)

    /** Include the [ToManyMapField] relation using the given referenced key- and value-fieldsets. */
    fun <K : Any, V : Any> includeToMany(toManyMap: ToManyMapField<E, K, out V, *, *>, fieldsets: FieldsetPair<K, V>)

    /** Include the [SubtypeField] using the given subtype-fieldset. */
    fun <C : E> includeSubtype(subtype: SubtypeField<E, C, *>, fieldset: Fieldset<C>)

    /** Exclude all [ToOneField] relations form this fieldset. */
    fun excludeAllToOne()

    /** Exclude all [ToManyField] and [ToManyMapField] relations from this fieldset. */
    fun excludeAllToMany()

    /** Exclude all [SubtypeField] fields from this fieldset. */
    fun excludeAllSubtypes()

    /** Exclude the given [EntitySchemaField]s (columns, relations, or subtypes) from this fieldset. */
    fun exclude(vararg fields: EntitySchemaField<E>)
}

/** Include the [ToOneField] relation, building an anonymous fieldset using the given builder function. */
fun <E : Any, R : Any> FieldsetBuilder<E>.includeToOne(toOne: ToOneField<E, R>, build: BuildFieldset<R>) {
    @Suppress("UNCHECKED_CAST")
    val refEntitySchema = requireNotNull(toOne.toOne as? EntitySchema<R>) {
        "Expected ToOne relation ${toOne.fieldName} referencing EntitySchema, but got ${toOne.toOne.javaClass.simpleName}"
    }

    val refFieldset = refEntitySchema.buildFieldset(build = build)
    includeToOne(toOne, refFieldset)
}

/** Include the [ToManyField] relation, building an anonymous fieldset using the given builder function. */
fun <E : Any, R : Any> FieldsetBuilder<E>.includeToMany(toMany: ToManyField<E, *, R, *>, build: BuildFieldset<R>) {
    @Suppress("UNCHECKED_CAST")
    val refEntitySchema = requireNotNull(toMany.toMany as? EntitySchema<R>) {
        "Expected ToMany relation ${toMany.fieldName} referencing EntitySchema, but got ${toMany.toMany.javaClass.simpleName}"
    }

    val refFieldset = refEntitySchema.buildFieldset(build = build)
    includeToMany(toMany, refFieldset)
}

/** Include the [SubtypeField], building an anonymous fieldset using the given builder function. */
fun <E : Any, C : E> FieldsetBuilder<E>.includeSubtype(subtype: SubtypeField<E, C, *>, build: BuildFieldset<C>) {
    @Suppress("UNCHECKED_CAST")
    val refEntitySchema = requireNotNull(subtype.subtypeSchema as? EntitySchema<C>) {
        "Expected Subtype ${subtype.fieldName} referencing EntitySchema, but got ${subtype.subtypeSchema.javaClass.simpleName}"
    }

    val refFieldset = refEntitySchema.buildFieldset(build = build)
    includeSubtype(subtype, refFieldset)
}

/**
 * Context implementation of a fieldset builder
 */
@PublishedApi
internal class FieldsetBuilderContext<E : Any>(
    private val schema: EntitySchema<E>,
) : FieldsetBuilder<E> {
    private val includedColumns = mutableSetOf<ColumnField<E, *, *>>()
    private val includedToOne = mutableMapOf<ToOneField<E, *>, Fieldset<*>>()
    private val includedToMany = mutableMapOf<ToManyField<E, *, *, *>, Fieldset<*>>()
    private val includedToManyMap = mutableMapOf<ToManyMapField<E, *, *, *, *>, FieldsetPair<*, *>>()
    private val includedSubtypes = mutableMapOf<SubtypeField<E, *, *>, Fieldset<*>>()

    fun createFieldset(name: String): EntityFieldset<E> {
        return EntityFieldset(
            name = name,
            schema = schema,
            columns = includedColumns.toList(),
            toOne = includedToOne.toMap(),
            toMany = includedToMany.toMap(),
            toManyMap = includedToManyMap.toMap(),
            subtypes = includedSubtypes.toMap(),
        )
    }

    override fun extendsFieldset(other: Fieldset<E>) {
        when (other) {
            is EntityFieldset<E> -> {
                includedColumns.addAll(other.columns)
                includedToOne.putAll(other.toOne)
                includedToMany.putAll(other.toMany)
                includedToManyMap.putAll(other.toManyMap)
                includedSubtypes.putAll(other.subtypes)
            }

            is SingleColumnFieldset<E, *, *> -> {
                val name = other.name
                val otherType = other.singleColumn.parentSchema.entityClass.name
                throw IllegalArgumentException("Can't extend a fieldset from single-column fieldset $name for $otherType")
            }

            is SingleValueFieldset<E, *> -> {
                val name = other.name
                val otherType = other.schema.entityClass.name
                throw IllegalArgumentException("Can't extend a fieldset from single-value fieldset $name for $otherType")
            }
        }
    }

    override fun include(vararg column: ColumnField<E, *, *>) {
        column.forEach { includedColumns.add(it) }
    }

    override fun include(columns: Iterable<ColumnField<E, *, *>>) {
        includedColumns.addAll(columns)
    }

    override fun <R : Any> includeToOne(toOne: ToOneField<E, in R>, fieldset: Fieldset<R>) {
        includedToOne[toOne] = fieldset
    }

    override fun <R : Any> includeToMany(toMany: ToManyField<E, *, out R, *>, fieldset: Fieldset<R>) {
        includedToMany[toMany] = fieldset
    }

    override fun <K : Any, V : Any> includeToMany(
        toManyMap: ToManyMapField<E, K, out V, *, *>,
        fieldsets: FieldsetPair<K, V>,
    ) {
        includedToManyMap[toManyMap] = fieldsets
    }

    override fun <C : E> includeSubtype(subtype: SubtypeField<E, C, *>, fieldset: Fieldset<C>) {
        includedSubtypes[subtype] = fieldset
    }

    override fun includeAllColumns() {
        includedColumns.addAll(schema.columns)
    }

    override fun includePrimaryKeys() {
        includedColumns.addAll(schema.primaryKeys)
    }

    override fun exclude(vararg fields: EntitySchemaField<E>) = fields.forEach { field ->
        when (field) {
            is ColumnField<*, *, *> -> includedColumns.remove(field)
            is ToOneField<*, *> -> includedToOne.remove(field)
            is ToManyField<*, *, *, *> -> includedToMany.remove(field)
            is ToManyMapField<*, *, *, *, *> -> includedToManyMap.remove(field)
            is SubtypeField<*, *, *> -> includedSubtypes.remove(field)
        }
    }

    override fun excludeAllToOne() {
        includedToOne.clear()
    }

    override fun excludeAllToMany() {
        includedToMany.clear()
        includedToManyMap.clear()
    }

    override fun excludeAllSubtypes() {
        includedSubtypes.clear()
    }
}

private fun <T> namedLazy(initializer: (KProperty<*>) -> T) =
    PropertyDelegateProvider<Any?, Lazy<T>> { _, property -> lazy { initializer(property) } }
