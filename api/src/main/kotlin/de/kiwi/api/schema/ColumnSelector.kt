package de.kiwi.api.schema

import de.kiwi.api.util.reusableJoin
import de.kiwi.api.util.slf4j
import jakarta.persistence.Embedded
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Id
import jakarta.persistence.criteria.*
import jakarta.persistence.metamodel.SingularAttribute
import org.springframework.beans.BeanUtils
import org.springframework.util.ClassUtils
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaGetter
import kotlin.reflect.jvm.jvmErasure

/**
 * Column selector accessing the entity attribute value underlying a [ColumnField].
 */
sealed interface ColumnSelector<E : Any, T> {
    val selectedType: KClass<T & Any>
    val isNullable: Boolean
    val isReadOnly: Boolean

    fun readValue(entity: E): T?
    fun writeValue(entity: E, attributeValue: T?)
    fun selectFrom(from: Path<in E>, builder: CriteriaBuilder): Selection<out T>

    /** Select the value of an attribute from a given entity */
    class AttributeSelector<E : Any, T>(
        val path: AttributePath<E, T>,
        override val selectedType: KClass<T & Any>,
        private val useJoinForAssociations: Boolean = true,
    ) : ColumnSelector<E, T> {
        override val isNullable: Boolean by lazy { path.last.returnType.isMarkedNullable }
        override val isReadOnly: Boolean by lazy { path.last !is KMutableProperty<*> }

        override fun selectFrom(from: Path<in E>, builder: CriteriaBuilder): Expression<out T> {
            // Select expression from attribute path, or null if no path to the target attribute exists
            return if (useJoinForAssociations) path.selectFrom(from, propagateLeftJoin = false)
            else path.selectChain(from)
        }

        override fun readValue(entity: E): T? = path.readValue(entity)
        override fun writeValue(entity: E, attributeValue: T?) = path.writeValue(entity, attributeValue)
        override fun toString(): String = path.toString()
    }

    /** Select the type from a given entity */
    class EntityTypeSelector<E : Any> : ColumnSelector<E, Class<*>> {
        override fun selectFrom(from: Path<in E>, builder: CriteriaBuilder): Expression<Class<out E>> {
            @Suppress("UNCHECKED_CAST")
            return from.type() as Expression<Class<out E>>
        }

        override fun readValue(entity: E): Class<out E> = entity.javaClass

        override fun writeValue(entity: E, attributeValue: Class<*>?) =
            throw AssertionError("Attempted to write to EntityType")

        @Suppress("UNCHECKED_CAST")
        override val selectedType: KClass<Class<*>>
            get() = Class::class

        override val isNullable = false
        override val isReadOnly = true

        override fun toString(): String = "<type>"
    }

    /** Compute a value from a given entity */
    class ComputedSelector<E : Any, T>(
        private val computation: EntityComputation<E, T>,
        override val selectedType: KClass<T & Any>,
    ) : ColumnSelector<E, T> {
        override val isNullable = true
        override val isReadOnly = true

        override fun selectFrom(from: Path<in E>, builder: CriteriaBuilder): Expression<out T> {
            return with(computation) {
                @Suppress("UNCHECKED_CAST")
                builder.select(from as Path<E>)
            }
        }

        override fun readValue(entity: E): T? = computation.compute(entity)
        override fun writeValue(entity: E, attributeValue: T?) = Unit // Do nothing

        override fun toString(): String = "<computed>"
    }

    class MultiValueSelector<E : Any>(
        private val selections: List<ColumnSelector<E, *>>,
    ) : ColumnSelector<E, Array<*>> {
        override val selectedType = Array::class
        override val isNullable = false
        override val isReadOnly = true

        override fun selectFrom(from: Path<in E>, builder: CriteriaBuilder): CompoundSelection<Array<*>> {
            val expressions = selections.map { it.selectFrom(from, builder) }
            return builder.array(*expressions.toTypedArray())
        }

        override fun readValue(entity: E): Array<*> {
            return selections.map { it.readValue(entity) }.toTypedArray()
        }

        override fun writeValue(entity: E, attributeValue: Array<*>?) = Unit // Do nothing
    }

    /** Always select null, used to dummy out values that should not be queried */
    class NullSelector<E : Any, T>(
        override val selectedType: KClass<T & Any>,
    ) : ColumnSelector<E, T> {
        override val isNullable = true
        override val isReadOnly = true

        override fun selectFrom(from: Path<in E>, builder: CriteriaBuilder): Expression<out T> {
            return builder.nullLiteral(selectedType.java)
        }

        override fun writeValue(entity: E, attributeValue: T?) = Unit // Do nothing
        override fun readValue(entity: E): T? = null

        override fun toString() = "<null>"
    }
}

/**
 * Interface representing a computation in two equivalent forms:
 * Computing the value from an entity object, and an expression computing the same value from a selection.
 */
interface EntityComputation<E, T> {
    /** Compute the result value from an entity object */
    fun compute(entity: E): T?

    /** Compute the result value using an SQL expression */
    fun CriteriaBuilder.select(fromEntity: Path<E>): Expression<T>
}

private val attributePathLog = slf4j<AttributePath<*, *>>()

/**
 * Represents a (possibly nested) [SingularAttribute] path leading from a root to a tail.
 *
 * Structure examples:
 * - `x` => `Tail { attr="x" }`
 * - `foo.bar` => `Head { attr="foo", next=Tail { attr="bar" } }`
 * - `a.b.c` => `Head { attr="a", next=Head { attr="b", next=Tail { attr="c" } } }`
 */
sealed interface AttributePath<E, T> {
    val first: KProperty1<in E, *>
    val last: KProperty1<*, T?>
    val attributes: List<KProperty1<*, *>>

    val selectedClass: KClass<T & Any>

    fun selectFrom(base: Path<in E>, propagateLeftJoin: Boolean = false): Path<out T>
    fun selectChain(base: Path<in E>): Path<out T>

    fun readValue(entity: E): T?
    fun writeValue(entity: E, value: T?)

    /** Nested path with an attribute and the rest of the path */
    data class Head<E, X, T>(
        val attribute: KProperty1<in E, X?>,
        val next: AttributePath<X, T?>,
    ) : AttributePath<E, T> {
        override val first = attribute
        override val last = next.last
        override val attributes = listOf(attribute) + next.attributes

        override val selectedClass = next.selectedClass

        private val emplaceNullEmbeddable = attribute.nullEmbeddableHandler()

        override fun selectFrom(base: Path<in E>, propagateLeftJoin: Boolean): Path<out T> {
            // Select path to attribute, use it as base for the next path,
            // and propagate left-joins to keep optional relations intact
            val (path, joinType) = selectAttribute(base, propagateLeftJoin)
            val leftJoinNext = propagateLeftJoin || (joinType == JoinType.LEFT)
            return next.selectFrom(path, propagateLeftJoin = leftJoinNext)
        }

        override fun selectChain(base: Path<in E>): Path<out T> {
            // "Chain" select is guaranteed to never use joins, only chaining Path.get(...)
            val value = base.get<X>(attribute.name)
            return next.selectChain(value)
        }

        private fun selectAttribute(base: Path<in E>, forceLeftJoin: Boolean): Pair<Path<X>, JoinType> {
            val path = base.get<X>(attribute.name)
            val pathAttribute = path.model as? SingularAttribute<*, *>

            return when {
                // Use left/inner join for associations
                pathAttribute != null && pathAttribute.isAssociation && base is From<*, E> -> {
                    val joinType = if (forceLeftJoin || pathAttribute.isOptional) JoinType.LEFT else JoinType.INNER
                    base.reusableJoin<E, X>(attribute.name, joinType) to joinType
                }

                // Use From.get(Attribute) for value types (i.e. embeddable, basic)
                else -> path to JoinType.INNER
            }
        }

        override fun readValue(entity: E): T? {
            // NOTE:
            // Reading a head attribute may produce a non-initialized lazy proxy.
            // If this happens, reading attributes of that proxy will fail because the
            // access by reflection does not trigger the intended initialization.
            // Thus, initialization is performed explicitly here.
            return entity
                ?.let { attribute.get(it) ?: emplaceNullEmbeddable(forWrite = false) }
                ?.let { ProxyResolverService.resolve(it) }
                ?.let { next.readValue(it) }
        }

        override fun writeValue(entity: E, value: T?) {
            entity
                ?.let { attribute.get(it) ?: emplaceNullEmbeddable(forWrite = true) }
                ?.let { ProxyResolverService.resolve(it) }
                ?.let { head ->
                    // Pass write-value to next path segment
                    next.writeValue(head, value)

                    // Re-assign the value after write as it may now be an emplace-embeddable or an initialized proxy
                    attribute.writePropertyValue(entity, head)
                }
        }

        override fun toString(): String = "${attribute.name}.$next"
    }

    /** End of a path with an attribute */
    data class Tail<E, T>(
        val attribute: KProperty1<in E, T?>,
    ) : AttributePath<E, T> {
        override val first = attribute
        override val last = attribute
        override val attributes = listOf(attribute)

        @Suppress("UNCHECKED_CAST")
        override val selectedClass = attribute.returnType.jvmErasure as KClass<T & Any>

        private val emplaceNullEmbeddable = attribute.nullEmbeddableHandler()

        override fun selectFrom(base: Path<in E>, propagateLeftJoin: Boolean): Path<out T> {
            // Assumption: Tail attributes are always embeddable/basic,
            // so no relation join handling is necessary here
            return base.get(attribute.name)
        }

        override fun selectChain(base: Path<in E>): Path<out T> = selectFrom(base)

        override fun readValue(entity: E): T? {
            return entity?.let { attribute.get(it) ?: emplaceNullEmbeddable(forWrite = false) }
        }

        override fun writeValue(entity: E, value: T?) = when {
            // Silently ignore attempts to write to null
            entity == null -> Unit

            // Ignore attempts to write to immutable properties with a warning
            attribute !is KMutableProperty1<in E, T?> -> {
                attributePathLog.warn("Attempted to write value [$value] for non-mutable property ${attribute.name}")
            }

            // Succeed writing non-null value to property
            value != null -> {
                attribute.set(entity, value)
            }

            // Ignore attempts to write null into non-nullable properties with a warning
            !attribute.returnType.isMarkedNullable -> {
                attributePathLog.warn("Attempted to write null-value into non-nullable property ${attribute.name}")
            }

            // Succeed writing null value to nullable property
            else -> {
                @Suppress("UNCHECKED_CAST")
                attribute as KMutableProperty1<E, T?>
                attribute.set(entity, value)
            }
        }

        override fun toString(): String = attribute.name
    }
}

// Utility for replacing null-valued Embeddable fields with default-constructed values
sealed interface NullEmbeddableHandler<out X> {
    operator fun invoke(forWrite: Boolean): X?

    object PassNull : NullEmbeddableHandler<Nothing> {
        override fun invoke(forWrite: Boolean): Nothing? = null
    }

    class EmplaceEmbeddable<X>(
        embeddableTypeClass: Class<X>,
        private val nullableProperty: Boolean,
    ) : NullEmbeddableHandler<X> {
        private val constructor = ClassUtils.getConstructorIfAvailable<X>(embeddableTypeClass)
        override fun invoke(forWrite: Boolean): X? = constructor?.let {
            if (!forWrite && nullableProperty) null // don't emplace when reading a nullable property
            else BeanUtils.instantiateClass(it)
        }
    }
}

private fun <X> KProperty1<*, X>?.nullEmbeddableHandler(): NullEmbeddableHandler<X> {
    // Special case: JPA represents embeddable values as null if all fields of the embeddable instance are null.
    // This behavior is not desired for non-nullable properties (except for primary keys),
    // so create a default no-arg instance instead to represent the value.
    if (this == null) return NullEmbeddableHandler.PassNull
    if (!hasJavaAnnotation<Embedded>()) return NullEmbeddableHandler.PassNull
    if (hasJavaAnnotation<EmbeddedId>()) return NullEmbeddableHandler.PassNull
    if (hasJavaAnnotation<Id>()) return NullEmbeddableHandler.PassNull

    @Suppress("UNCHECKED_CAST") val attributeValueClass = returnType.jvmErasure.java as Class<X>
    return NullEmbeddableHandler.EmplaceEmbeddable(attributeValueClass, nullableProperty = returnType.isMarkedNullable)
}

private inline fun <reified A : Annotation> KProperty1<*, *>.hasJavaAnnotation(): Boolean {
    return javaField?.isAnnotationPresent(A::class.java)
        ?: javaGetter?.isAnnotationPresent(A::class.java)
        ?: false
}

// Utility for assigning values to a KProperty1
private fun <T, X> KProperty1<T, X>.writePropertyValue(instance: T, value: X) = when (this) {
    // Simple case: Mutable property, use Kotlin built-in setter mechanism
    is KMutableProperty1<T, X> -> set(instance, value)

    // Otherwise, attempt to use Java field reflection to assign field value
    else -> javaField
        ?.takeIf { it.trySetAccessible() }
        ?.set(instance, value)
}
