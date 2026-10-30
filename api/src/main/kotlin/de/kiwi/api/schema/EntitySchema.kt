package de.kiwi.api.schema

import de.kiwi.api.domain.*
import de.kiwi.api.util.lookupSupertypeArgument
import de.kiwi.api.util.reusableJoin
import jakarta.persistence.criteria.*
import org.springframework.data.domain.Sort
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1
import kotlin.reflect.full.withNullability
import kotlin.reflect.jvm.jvmErasure
import kotlin.reflect.typeOf

/**
 * Abstract parent class for query schema definitions.
 * @param E Queried root type
 * @param S Value type of the query result
 */
sealed class AbstractSchema<E : Any, S> {
    val entityClass: Class<E> by lazy {
        @Suppress("UNCHECKED_CAST")
        javaClass.lookupSupertypeArgument<AbstractSchema<E, *>>(0) as Class<E>
    }
}

/**
 * Abstract class for defining the schema of an entity query.
 *
 * An entity schema is composed of [ColumnField]s, [ToOneField] (parent) references,
 * and [ToManyField]/[ToManyMapField] (child) references.
 *
 * A [ColumnField] is composed of a [ColumnSelector] for selecting the column value from the underlying entity,
 * and a [ColumnType] for formatting or parsing the column values for representation in the API.
 *
 * __Example__:
 * ```kt
 * object ExampleSchema : EntitySchema<Example>() {
 *   val id by entityId()
 *   val someAttribute by column(Example::someAttribute)
 *   val mappedAttribute by column(Example::someAttribute, ColumnType.map { it.transformSomehow() })
 *   val validatedAttribute by column(Example::validatedAttribute, ColumnType.validate(someValidation))
 *   val parent by toOne(Example::parent, ParentSchema)
 *   val parentId by parentEntityId(Example::parent)
 *   val children by toMany(Example::children, ChildSchema)
 *   override val defaultSort = Sort.by("someAttribute")
 * }
 * ```
 * @param E Queried entity type
 */
abstract class EntitySchema<E : Any> : AbstractSchema<E, E>() {
    private val columnSet = mutableSetOf<ColumnField<E, *, *>>()
    private val toOneRefSet = mutableSetOf<ToOneField<E, *>>()
    private val toManyRefSet = mutableSetOf<ToManyField<E, *, *, *>>()
    private val toManyMapRefSet = mutableSetOf<ToManyMapField<E, *, *, *, *>>()
    private val subtypeSet = mutableSetOf<SubtypeField<E, *, *>>()

    val columns get() = columnSet.toList()
    val primaryKeys get() = columnSet.filter { it.primaryKey }
    val toOneReferences get() = toOneRefSet.toList()
    val toManyReferences get() = toManyRefSet.toList()
    val toManyMapReferences get() = toManyMapRefSet.toList()
    val subtypeReferences get() = subtypeSet.toList()

    open val defaultSort: Sort = Sort.unsorted()

    open fun fieldAccessControl(field: EntitySchemaField<E>): FieldAccessControl<E>? = null

    fun singlePrimaryKey(): ColumnField<E, *, *> = when (primaryKeys.size) {
        1 -> primaryKeys[0]
        0 -> throw IllegalStateException("Entity ${entityClass.simpleName} does not have a primary key")
        else -> throw IllegalStateException("Entity ${entityClass.simpleName} has more than one primary key")
    }

    fun selectPrimaryKeys(builder: CriteriaBuilder, from: Path<E>, createAlias: (Int) -> String): List<Selection<*>> {
        return primaryKeys.mapIndexed { i, key -> key.selectFrom(from, builder).apply { alias(createAlias(i)) } }
    }

    fun allColumnsIncludingSubtypes(): List<ColumnField<out E, *, *>> = buildList {
        addAll(columnSet)
        subtypeSet.forEach { subtype ->
            val subtypeSchema = subtype.subtypeSchema
            if (subtypeSchema is EntitySchema<out E>) {
                addAll(subtypeSchema.allColumnsIncludingSubtypes())
            }
        }
    }

    override fun toString() = buildString {
        append("EntitySchema(${entityClass.name}) {")
        append("columns = $columns")
        append(", toOne = $toOneReferences")
        append(", toMany = ${toManyReferences + toManyMapReferences}")
        append("}")
    }

    // --- Schema Definition ---

    /** Define a [ColumnField] on a [KProperty1], and an implicit [ColumnType.identity] column type. */
    protected inline fun <reified T> column(
        attribute: KProperty1<E, T>,
        nullable: Boolean = isTypeNullable<T>(),
    ): ReadonlyProvider<ColumnField<E, T, T>> =
        column(selector = attribute(AttributePath.Tail(attribute)), nullable = nullable)

    /** Define a non-nullable [ColumnField] on a [KProperty1] with a given [ColumnType]. */
    protected inline fun <T : Any, reified M : Any> column(
        attribute: KProperty1<E, T>,
        type: ColumnType<T, M>,
    ): ReadonlyProvider<ColumnField<E, T, M>> =
        column(selector = attribute(AttributePath.Tail(attribute)), type = type, nullable = false)

    /** Define a nullable [ColumnField] on a [KProperty1] with a given [ColumnType]. */
    @JvmName("nullableColumn")
    protected inline fun <T : Any, reified M : Any> column(
        attribute: KProperty1<E, T?>,
        type: ColumnType<T, M>,
    ): ReadonlyProvider<ColumnField<E, T?, M?>> =
        column(selector = attribute(AttributePath.Tail(attribute)), type = type, nullable = true)

    /** Define a [ColumnField] using a given [EntityComputation], and an implicit [ColumnType.identity] type. */
    protected inline fun <reified T : Any> column(
        computation: EntityComputation<E, T>,
    ): ReadonlyProvider<ColumnField<E, T, T>> =
        column(computed(computation))

    /** Define a [ColumnField] using a given [ColumnSelector] with an implicit [ColumnType.identity] column type. */
    protected inline fun <reified T> column(
        selector: ColumnSelector<E, T>,
        nullable: Boolean = isTypeNullable<T>(),
    ): ReadonlyProvider<ColumnField<E, T, T>> {
        @Suppress("UNCHECKED_CAST") val typeClass = typeOf<T>().withNullability(false).jvmErasure.java as Class<T & Any>
        return column(selector, type = ColumnType.identity(typeClass), nullable = nullable)
    }

    /** Define a read-only [ColumnField] that always returns `null` */
    protected inline fun <reified T : Any> alwaysNullColumn() =
        column<T>(alwaysNull(T::class), nullable = true)

    /** Define a [ColumnField] using a given [ColumnSelector] and [ColumnType]. */
    protected inline fun <T, reified M> column(
        selector: ColumnSelector<E, T>,
        type: ColumnType<T & Any, M & Any>,
        nullable: Boolean = isTypeNullable<M>(),
    ): ReadonlyProvider<ColumnField<E, T, M>> {
        return column(selector, type, primaryKey = false, nullable = nullable)
    }

    /** Define a [ColumnField] that selects *two* values, and combines the array into a single result value */
    protected inline fun <reified M : Any, F0, F1> multiselect(
        select0: ColumnSelector<E, F0>,
        select1: ColumnSelector<E, F1>,
        crossinline combineValues: (F0, F1) -> M,
    ) = multiselect<M>(select0, select1) { (f0, f1) ->
        @Suppress("UNCHECKED_CAST")
        combineValues(f0 as F0, f1 as F1)
    }

    /** Define a [ColumnField] that selects *three* values, and combines the array into a single result value */
    protected inline fun <reified M : Any, F0, F1, F2> multiselect(
        select0: ColumnSelector<E, F0>,
        select1: ColumnSelector<E, F1>,
        select2: ColumnSelector<E, F2>,
        crossinline combineValues: (F0, F1, F2) -> M,
    ) = multiselect<M>(select0, select1, select2) { (f0, f1, f2) ->
        @Suppress("UNCHECKED_CAST")
        combineValues(f0 as F0, f1 as F1, f2 as F2)
    }

    /** Define a [ColumnField] that selects *four* values, and combines the array into a single result value */
    protected inline fun <reified M : Any, F0, F1, F2, F3> multiselect(
        select0: ColumnSelector<E, F0>,
        select1: ColumnSelector<E, F1>,
        select2: ColumnSelector<E, F2>,
        select3: ColumnSelector<E, F3>,
        crossinline combineValues: (F0, F1, F2, F3) -> M,
    ) = multiselect<M>(select0, select1, select2, select3) { (f0, f1, f2, f3) ->
        @Suppress("UNCHECKED_CAST")
        combineValues(f0 as F0, f1 as F1, f2 as F2, f3 as F3)
    }

    /** Define a [ColumnField] that selects *five* values, and combines the array into a single result value */
    protected inline fun <reified M : Any, F0, F1, F2, F3, F4> multiselect(
        select0: ColumnSelector<E, F0>,
        select1: ColumnSelector<E, F1>,
        select2: ColumnSelector<E, F2>,
        select3: ColumnSelector<E, F3>,
        select4: ColumnSelector<E, F4>,
        crossinline combineValues: (F0, F1, F2, F3, F4) -> M,
    ) = multiselect<M>(select0, select1, select2, select3, select4) { (f0, f1, f2, f3, f4) ->
        @Suppress("UNCHECKED_CAST")
        combineValues(f0 as F0, f1 as F1, f2 as F2, f3 as F3, f4 as F4)
    }

    /** Define a [ColumnField] that selects *multiple* values, and combines the array into a single result value */
    protected inline fun <reified M : Any> multiselect(
        vararg selectors: ColumnSelector<E, *>,
        crossinline combineValues: (Array<*>) -> M,
    ): ReadonlyProvider<ColumnField<E, Array<*>, M>> = multiselect(
        selectors = selectors.toList(),
        combineValues = combineValues,
    )

    /** Define a [ColumnField] that selects *multiple* values, and combines the array into a single result value */
    protected inline fun <reified M : Any> multiselect(
        selectors: List<ColumnSelector<E, *>>,
        crossinline combineValues: (Array<*>) -> M,
    ): ReadonlyProvider<ColumnField<E, Array<*>, M>> = column(
        selector = ColumnSelector.MultiValueSelector(selectors),
        type = ColumnType.reduceArray { combineValues(it) },
        primaryKey = false,
        nullable = false,
    )

    /** Define a [ColumnField] that selects *multiple* values, associated by field names, and combines the selections into a key-value map */
    protected fun multiselectMap(
        vararg fieldSelectors: Pair<String, ColumnSelector<E, *>>,
    ): ReadonlyProvider<ColumnField<E, Array<*>, Map<String, *>>> {
        val fields = fieldSelectors.map { it.first }
        val selectors = fieldSelectors.map { it.second }
        return column(
            selector = ColumnSelector.MultiValueSelector(selectors),
            type = ColumnType.reduceArray { values -> fields.zip(values).toMap() },
            primaryKey = false,
            nullable = false,
        )
    }

    /** Define a [ColumnField] with an explicit selector, type, and column flags */
    protected fun <T, M> column(
        selector: ColumnSelector<E, T>,
        type: ColumnType<T & Any, M & Any>,
        primaryKey: Boolean,
        nullable: Boolean,
    ): ReadonlyProvider<ColumnField<E, T, M>> =
        registeredProperty(columnSet::add) { property ->
            ColumnField(
                fieldName = property.name,
                parentSchema = this,
                selector = selector,
                type = type,
                primaryKey = primaryKey,
                nullable = nullable,
            )
        }

    protected fun <R> toOne(
        attribute: KProperty1<E, R>,
        schema: EntitySchema<R & Any>,
    ) = registeredProperty(toOneRefSet::add) { property ->
        ToOneField(
            fieldName = property.name,
            parentSchema = this,
            toOne = schema,
            attribute = attribute,
        )
    }

    protected fun <C : Collection<R>, R : Any, S> toMany(
        attribute: KProperty1<in E, C>,
        schema: AbstractSchema<R, S>,
    ) = registeredProperty(toManyRefSet::add) { property ->
        ToManyField(
            fieldName = property.name,
            parentSchema = this,
            toMany = schema,
            attribute = attribute,
        )
    }

    protected fun <K : Any, V : Any, KS, VS> toMany(
        attribute: KProperty1<in E, Map<K, V>>,
        keySchema: AbstractSchema<K, KS>,
        valueSchema: AbstractSchema<V, VS>,
    ) = registeredProperty(toManyMapRefSet::add) { property ->
        ToManyMapField(
            fieldName = property.name,
            parentSchema = this,
            toManyKeys = keySchema,
            toManyValues = valueSchema,
            attribute = attribute,
        )
    }

    protected fun <C : E, CS> subtype(
        subtypeSchema: AbstractSchema<C, CS>,
    ) = registeredProperty(subtypeSet::add) { property ->
        SubtypeField(
            fieldName = property.name,
            parentSchema = this,
            subtypeSchema = subtypeSchema,
        )
    }

    protected companion object {
        // --- Column selector functions ---

        /**
         * Define a column for an entity based on a JPA attribute.
         * Supports nested attributes.
         * @param E Entity type the attribute belongs to
         * @param T Column attribute type
         */
        fun <E : Any, T> attribute(
            attribute1: KProperty1<in E, T>,
            useJoinForAssociations: Boolean = true,
        ) = attribute(
            path = tail(attribute1),
            useJoinForAssociations = useJoinForAssociations,
        )

        /** @see Companion.attribute */
        fun <E : Any, Z0, T> attribute(
            attribute1: KProperty1<in E, Z0?>,
            attribute2: KProperty1<in Z0, T>,
            useJoinForAssociations: Boolean = true,
        ) = attribute(
            path = head(attribute1, tail(attribute2)),
            useJoinForAssociations = useJoinForAssociations,
        )

        /** @see Companion.attribute */
        fun <E : Any, Z0, Z1, T> attribute(
            attribute1: KProperty1<in E, Z0?>,
            attribute2: KProperty1<in Z0, Z1?>,
            attribute3: KProperty1<in Z1, T>,
            useJoinForAssociations: Boolean = true,
        ) = attribute(
            path = head(attribute1, head(attribute2, tail(attribute3))),
            useJoinForAssociations = useJoinForAssociations,
        )

        /** @see Companion.attribute */
        fun <E : Any, Z0, Z1, Z2, T> attribute(
            attribute1: KProperty1<in E, Z0?>,
            attribute2: KProperty1<in Z0, Z1?>,
            attribute3: KProperty1<in Z1, Z2?>,
            attribute4: KProperty1<in Z2, T>,
            useJoinForAssociations: Boolean = true,
        ) = attribute(
            path = head(attribute1, head(attribute2, head(attribute3, tail(attribute4)))),
            useJoinForAssociations = useJoinForAssociations,
        )

        /** @see Companion.attribute */
        fun <E : Any, Z0, Z1, Z2, Z3, T> attribute(
            attribute1: KProperty1<in E, Z0?>,
            attribute2: KProperty1<in Z0, Z1?>,
            attribute3: KProperty1<in Z1, Z2?>,
            attribute4: KProperty1<in Z2, Z3?>,
            attribute5: KProperty1<in Z3, T>,
            useJoinForAssociations: Boolean = true,
        ) = attribute(
            path = head(attribute1, head(attribute2, head(attribute3, head(attribute4, tail(attribute5))))),
            useJoinForAssociations = useJoinForAssociations,
        )

        /** Create an arbitrarily deeply nested [AttributePath]. __Warning__: This provides no type safety! */
        @Suppress("UNCHECKED_CAST")
        fun <E : Any, T> attribute(
            rootAttribute: KProperty1<in E, *>,
            vararg nestedAttribute: KProperty1<*, *>,
            useJoinForAssociations: Boolean = true,
        ): ColumnSelector<E, T> {
            // Merge root=A, nested=[B, C, D] into attrs=[A, B, C, D]
            val attrs = listOf(rootAttribute) + nestedAttribute.toList()

            // Split attrs=[A, B, C, D] into head=[A, B, C] and tail=D
            val headAttrs = attrs.dropLast(1) as List<KProperty1<Any?, Any?>>
            val tailAttr = attrs.last() as KProperty1<Any?, Any?>

            // Fold head=[A, B, C] from right to left with tail(D):
            // 0. tail(D)
            // 1. head(C, tail(D))
            // 2. head(B, head(C, tail(D)))
            // 3. head(A, head(B, head(C, tail(D))))
            val tail: AttributePath<Any?, Any?> = AttributePath.Tail(tailAttr)
            val path = headAttrs.foldRight(tail) { attribute, next -> AttributePath.Head(attribute, next) }

            return ColumnSelector.AttributeSelector(
                path = path as AttributePath<E, T>,
                selectedType = tailAttr.returnType.jvmErasure as KClass<T & Any>,
                useJoinForAssociations = useJoinForAssociations,
            )
        }

        /** Create an [AttributeSelector][ColumnSelector.AttributeSelector] with the given [AttributePath]. */
        fun <E : Any, T> attribute(
            path: AttributePath<E, T>,
            useJoinForAssociations: Boolean = true,
        ): ColumnSelector<E, T> {
            return ColumnSelector.AttributeSelector(
                path = path,
                selectedType = path.selectedClass,
                useJoinForAssociations = useJoinForAssociations,
            )
        }

        /**
         * Create a [ComputedSelector][ColumnSelector.ComputedSelector] with the given computation.
         */
        inline fun <E : Any, reified T : Any> computed(computation: EntityComputation<E, T>) =
            computed(computation, T::class)

        /**
         * Create a [ComputedSelector][ColumnSelector.ComputedSelector] with the given value type and computation.
         */
        fun <E : Any, T : Any> computed(computation: EntityComputation<E, T>, valueType: KClass<T>) =
            ColumnSelector.ComputedSelector(computation, valueType)

        inline fun <E : Any, reified T : Any> alwaysNull() = alwaysNull<E, T>(T::class)

        fun <E : Any, T> alwaysNull(valueType: KClass<T & Any>) =
            ColumnSelector.NullSelector<E, T>(valueType)

        // --- Special column extensions ---

        /** Define a primary key column for this schema. */
        inline fun <E : Any, reified T : Any> EntitySchema<E>.primaryKey(attribute: KProperty1<E, T>) =
            primaryKey(attribute, T::class.java)

        /** Define a primary key column with a given ID value class for this schema. */
        fun <E : Any, T> EntitySchema<E>.primaryKey(
            attribute: KProperty1<E, T>,
            valueType: Class<T & Any>,
        ) = column(
            selector = attribute(attribute),
            type = ColumnType.identity(valueType),
            primaryKey = true,
            nullable = false,
        )

        inline fun <E : Any, reified F, reified K : Any> EntitySchema<E>.foreignKey(
            associationAttribute: KProperty1<E, F>,
            keyAttribute: KProperty1<F, K>,
            nullable: Boolean = isTypeNullable<F>(),
        ) = foreignKey(
            associationAttribute = associationAttribute,
            keyAttribute = keyAttribute,
            valueType = K::class.java,
            nullable = nullable,
        )

        fun <E : Any, F, K : Any> EntitySchema<E>.foreignKey(
            associationAttribute: KProperty1<E, F>,
            keyAttribute: KProperty1<F, K>,
            valueType: Class<K>,
            nullable: Boolean,
        ) = column(
            selector = attribute(associationAttribute, keyAttribute, useJoinForAssociations = false),
            type = ColumnType.identity(valueType),
            primaryKey = false,
            nullable = nullable,
        )

        /** Define an [EntityId] primary key for this schema. */
        inline fun <E : BaseEntity<ID>, reified ID : EntityId> EntitySchema<E>.entityId() =
            entityId(ID::class)

        /** Define an [EntityId] primary key with a given ID value class for this schema. */
        fun <E : BaseEntity<ID>, ID : EntityId> EntitySchema<E>.entityId(
            entityIdType: KClass<ID>,
        ) = column(
            selector = ColumnSelector.AttributeSelector(
                path = tail(BaseEntity<ID>::id),
                selectedType = entityIdType,
            ),
            type = EntityIdColumnType(entityIdType.java),
            primaryKey = true,
            nullable = false,
        )

        /**
         * Define an attribute referencing the [EntityId] primary key of an entity referenced in a
         * [OneToOne][jakarta.persistence.OneToOne] or [ManyToOne][jakarta.persistence.ManyToOne] relation.
         */
        inline fun <E : Any, reified P : BaseEntity<ID>?, reified ID : EntityId> EntitySchema<E>.parentEntityId(
            parentAttribute: KProperty1<E, P>,
            nullable: Boolean = isTypeNullable<P>(),
        ) = parentEntityId(parentAttribute, ID::class, nullable = nullable)

        /**
         * Define an attribute referencing the [EntityId] primary key of an entity referenced in a
         * [OneToOne][jakarta.persistence.OneToOne] or [ManyToOne][jakarta.persistence.ManyToOne] relation
         * with a given ID value type.
         */
        fun <E : Any, P : BaseEntity<ID>?, ID : EntityId> EntitySchema<E>.parentEntityId(
            parentAttribute: KProperty1<E, P>,
            entityIdType: KClass<ID>,
            nullable: Boolean,
        ): ReadonlyProvider<ColumnField<E, ID, String>> {
            @Suppress("UNCHECKED_CAST") val parentIdAttribute = BaseEntity<ID>::id as KProperty1<P, ID>
            val path: AttributePath<E, ID> = head(parentAttribute, tail(parentIdAttribute))
            return column(
                selector = ColumnSelector.AttributeSelector(
                    path = path,
                    selectedType = entityIdType,
                    useJoinForAssociations = false,
                ),
                type = EntityIdColumnType(entityIdType.java),
                primaryKey = false,
                nullable = nullable,
            )
        }

        /**
         * Define a [ColumnField] representing the entity class name using the [EntityClassNameType] as column type.
         */
        fun <E : Any> EntitySchema<E>.entityType() = column(
            selector = ColumnSelector.EntityTypeSelector(),
            type = EntityClassNameType,
            primaryKey = false,
            nullable = false,
        )

        /**
         * Define a [ColumnField] representing the entity discriminator value using the [EntityDiscriminatorValueType]
         * as column type.
         */
        fun <E : Any> EntitySchema<E>.entityDiscriminator() = column(
            selector = ColumnSelector.EntityTypeSelector(),
            type = EntityDiscriminatorValueType,
            primaryKey = false,
            nullable = false,
        )

        private fun <E, X, T> head(attribute: KProperty1<in E, X?>, next: AttributePath<X, T?>) =
            AttributePath.Head<E, X, T>(attribute, next)

        private fun <E, T> tail(attribute: KProperty1<in E, T?>) =
            AttributePath.Tail<E, T>(attribute)

        inline fun <reified T> isTypeNullable() = typeOf<T>().isMarkedNullable
    }
}

/**
 * Abstract class for defining a schema that represents an entity as a single value.
 * Objects of this class also implement the [ColumnType] interface, and can be used in [ColumnField] definitions.
 *
 * __Example__:
 * ```kt
 * object ExampleValueSchema : SingleValueSchema<ExampleValue, String> {
 *   fun toValue(entity: ExampleValue): String = entity.representAsString()
 * }
 * ```
 *
 * @param E Entity type
 * @param S Single value type
 */
abstract class SingleValueSchema<E : Any, S> : AbstractSchema<E, S>(), ColumnType<E, S> {
    abstract fun toValue(entity: E): S

    override val valueType: Class<S> by lazy {
        @Suppress("UNCHECKED_CAST")
        javaClass.lookupSupertypeArgument<SingleValueSchema<E, S>>(1) as Class<S>
    }

    final override fun format(value: E): S = toValue(value)
    override fun parse(value: S): E = throw ColumnType.ParseNotSupported()

    override fun toString() = "SingleValueSchema(${entityClass.name})"
}

// --- Attributes ---

sealed class EntitySchemaField<E : Any> {
    abstract val fieldName: String
    abstract val parentSchema: EntitySchema<E>
    abstract val nullable: Boolean

    override fun equals(other: Any?): Boolean = when {
        this === other -> true
        other == null -> false
        other !is EntitySchemaField<*> -> false
        javaClass != other.javaClass -> false
        else -> (fieldName == other.fieldName) &&
                (parentSchema.entityClass == other.parentSchema.entityClass)
    }

    override fun hashCode(): Int {
        var result = parentSchema.entityClass.hashCode()
        result = 31 * result + fieldName.hashCode()
        return result
    }
}

/**
 * Query column representing a single attribute of an entity.
 *
 * A column belongs to an entity of type [E], has an entity attribute type [T],
 * an API representation type [M], and can be marked as a [primaryKey] of the entity.
 * The value of a column is retrieved from the entity query using a [ColumnSelector],
 * and either formatted to or parsed from an API representation using a [ColumnType].
 *
 * ```
 * QueryColumn<E, T, M>:
 * read = (entity : E) --[ColumnSelector<E, T>]-> (attributeValue : T) --[ColumnType<T, M>]-> (formattedValue : M)
 * write = (formattedValue : M) --[ColumnType<T, M>]-> (attributeValue : T) --[ColumnSelector<E, T>]-> (entity : E)
 * query = (from : Root<E>) --[ColumnSelector<E, T>]-> (attributePath : Path<T>) --[ColumnType<T, M>]-> (formattedValue : M)
 * ```
 *
 * @param E Parent entity type
 * @param T Column attribute type
 * @param M Mapped value type
 */
data class ColumnField<E : Any, T, M>(
    override val fieldName: String,
    override val parentSchema: EntitySchema<E>,
    val selector: ColumnSelector<E, T>,
    val type: ColumnType<T & Any, M & Any>,
    val primaryKey: Boolean,
    override val nullable: Boolean,
) : EntitySchemaField<E>() {

    /**
     * Select the attribute associated with this [ColumnField] from the entity in a criteria query.
     */
    fun selectFrom(base: Path<in E>, builder: CriteriaBuilder): Selection<out T> {
        return selector.selectFrom(base, builder)
    }

    /**
     * Read and format the attribute value associated with this [ColumnField] from the given entity.
     * Null attribute values are propagated and not formatted.
     */
    fun readFromEntity(entity: E): M? {
        val attributeValue = selector.readValue(entity)
        // Propagate null, otherwise format attribute value
        return attributeValue?.let { type.format(it) }
    }

    /**
     * Format and write the given attribute value to the entity attribute associated with this [ColumnField].
     * @throws EntityFieldMissingValue If attempting to write null to a non-null attribute.
     * @throws EntityFieldWriteNotAllowed If input value parsing failed with [ColumnType.ParseNotSupported].
     * @throws EntityFieldInvalid If input value validation failed with [ValidationFailed].
     */
    fun writeToEntity(entity: E, attributeValue: T?) {
        // Propagate null if attribute is optional
        if (attributeValue == null && !selector.isNullable) {
            // Check for null write to non-nullable attribute
            throw EntityFieldMissingValue(parentSchema.entityClass, fieldName)
        }
        selector.writeValue(entity, attributeValue)
    }

    val isReadOnly get() = primaryKey || selector.isReadOnly

    val attributeType: KClass<T & Any> by lazy { selector.selectedType }

    override fun toString(): String {
        val entityName = parentSchema.entityClass.simpleName
        val qualifier = if (primaryKey) "<PK>" else ""
        return "Column$qualifier($fieldName: $entityName.$selector)"
    }
}

// --- Associations ---

/**
 * Relation representing a many-to-one or one-to-one relation from [E] to one [R] entity.
 * @param E Relation owning entity type
 * @param R Target entity type referenced in to-one relation
 */
data class ToOneField<E : Any, R>(
    override val fieldName: String,
    override val parentSchema: EntitySchema<E>,
    val toOne: AbstractSchema<R & Any, *>,
    val attribute: KProperty1<in E, R>,
) : EntitySchemaField<E>() {
    override val nullable = attribute.returnType.isMarkedNullable

    fun joinFrom(base: Path<E>): Path<R> {
        // Join from parent to relation; if parent is a left-join, then further propagate as left-join
        val propagateLeft = (base is Join<*, *>) && (base.joinType == JoinType.LEFT)
        val joinType = if (propagateLeft || nullable) JoinType.LEFT else JoinType.INNER
        return base.reusableJoin(attribute, joinType)
    }

    override fun toString(): String {
        val fromName = parentSchema.entityClass.simpleName
        val toName = toOne.entityClass.simpleName
        return "ToOne($fieldName: $fromName.${attribute.name} -> $toName)"
    }
}

/**
 * Relation representing a one-to-many or many-to-many relation from [E] to many [R] entities.
 * @param E Relation owning entity type
 * @param C Type of collection containing many entities of type [R]
 * @param R Target entity type referenced in to-many relation
 * @param S Target entity value type
 */
data class ToManyField<E : Any, C : Collection<R>, R, S>(
    override val fieldName: String,
    override val parentSchema: EntitySchema<E>,
    val toMany: AbstractSchema<R & Any, S>,
    val attribute: KProperty1<in E, C>,
) : EntitySchemaField<E>() {
    override val nullable = false

    override fun toString(): String {
        val fromName = parentSchema.entityClass.simpleName
        val toName = toMany.entityClass.simpleName
        return "ToMany($fieldName: $fromName.${attribute.name} -< $toName)"
    }
}

/**
 * Relation representing a one-to-many or many-to-many relation from [E] to a map associating [K] with [V] entities.
 * @param E Relation owning entity type
 * @param K Key entity type referenced in to-many relation
 * @param V Value entity type referenced in to-many relation
 * @param KS Key entity value type
 * @param VS Value entity value type
 */
data class ToManyMapField<E : Any, K, V, KS, VS>(
    override val fieldName: String,
    override val parentSchema: EntitySchema<E>,
    val toManyKeys: AbstractSchema<K & Any, KS>,
    val toManyValues: AbstractSchema<V & Any, VS>,
    val attribute: KProperty1<in E, Map<K, V>>,
) : EntitySchemaField<E>() {
    override val nullable = false

    override fun toString(): String {
        val fromName = parentSchema.entityClass.simpleName
        val toKeyName = toManyKeys.entityClass.simpleName
        val toValueName = toManyValues.entityClass.simpleName
        return "ToMany($fieldName: $fromName.${attribute.name} -< ($toKeyName, $toValueName))"
    }
}

// --- Subtypes ---

/**
 * Pseudo-relation representing a nested schema with fields that belong to a subclass [C] of the parent schema entity-type [E].
 * @param E Parent entity type
 * @param C Child entity type, subclass of [E]
 * @param CS Child entity value type
 */
data class SubtypeField<E : Any, C : E, CS>(
    override val fieldName: String,
    override val parentSchema: EntitySchema<E>,
    val subtypeSchema: AbstractSchema<C, CS>,
) : EntitySchemaField<E>() {
    override val nullable = false

    /**
     * Returns a [Path] representing the given [base] path "treated as" the child entity type [C], allowing access to
     * subtype-specific attributes in a query while preserving `LEFT JOIN` semantics (i.e. parent entities that are not
     * instances of [C] are **not** filtered out).
     *
     * The returned path is the [base] path itself, cast to `Path<C>`: The cast only satisfies the compile-time types of
     * the Criteria API, and has no effect on the generated SQL. Subtype attributes are resolved explicitly when they are
     * accessed through the returned path, see [de.kiwi.api.util.getIncludingSubtypes] (columns) and
     * [de.kiwi.api.util.joinIncludingSubtypes] (singular and plural relations). Since Hibernate 7, attributes declared on
     * a subclass are no longer resolved implicitly by name on a path of the parent entity type.
     *
     * `CriteriaBuilder.treat()` is not used intentionally: Hibernate narrows a treated `Root` or `Join` to the subtype,
     * which filters out all parent entities that are not instances of [C]. Subtype fields are optional (nullable)
     * instead, so non-instances must still appear in the result.
     *
     * @param base the [Path] for the parent entity type [E], typically a [Root] or [Join]
     * @param builder the [CriteriaBuilder] (unused in this implementation; retained for API consistency)
     * @return the [base] path typed as [C], for accessing subtype attributes in a Criteria query
     */
    fun treatFrom(base: Path<E>, builder: CriteriaBuilder): Path<C> {
        // Intentional unchecked cast, see KDoc: CriteriaBuilder.treat() would filter out non-[C] parent entities
        @Suppress("UNCHECKED_CAST")
        return base as Path<C>
    }

    fun tryCast(parentEntity: E): C? {
        val childClass = subtypeSchema.entityClass
        return parentEntity.takeIf { childClass.isInstance(it) }?.let { childClass.cast(it) }
    }

    override fun toString(): String {
        val fromName = parentSchema.entityClass.simpleName
        val subtypeName = subtypeSchema.entityClass.simpleName
        return "Subtype($fieldName: $fromName <|- $subtypeName)"
    }
}

// --- Internal property management ---

private typealias ReadonlyProvider<V> = PropertyDelegateProvider<Any?, ReadOnlyProperty<Any?, V>>

/**
 * Utility to create provide a value which initializes a value with the property available during initialization,
 * and then creates a delegate that returns the previously initialized value.
 * Used for creating and registering column/relation/etc. properties while registering them.
 */
private fun <V> registeredProperty(
    register: (V) -> Unit,
    initializer: (KProperty<*>) -> V,
): ReadonlyProvider<V> =
    RegisteredProperty(register, initializer)

private class RegisteredProperty<V>(
    val register: (V) -> Unit,
    val initializer: (KProperty<*>) -> V,
) : ReadonlyProvider<V> {
    override operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): ValueProperty<V> {
        val value = initializer(property)
        register(value)
        return ValueProperty(value)
    }
}

private data class ValueProperty<T>(val value: T) : ReadOnlyProperty<Any?, T> {
    override operator fun getValue(thisRef: Any?, property: KProperty<*>): T = value
}
