package de.kiwi.api.util

import de.kiwi.api.domain.AccessControlSpecification
import de.kiwi.api.domain.EntityNotFound
import jakarta.persistence.EntityManager
import jakarta.persistence.criteria.*
import jakarta.persistence.metamodel.CollectionAttribute
import jakarta.persistence.metamodel.ListAttribute
import jakarta.persistence.metamodel.MapAttribute
import jakarta.persistence.metamodel.SetAttribute
import jakarta.persistence.metamodel.SingularAttribute
import org.hibernate.query.sqm.tree.domain.SqmPath
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.repository.CrudRepository
import org.springframework.util.ClassUtils
import kotlin.jvm.optionals.getOrNull
import kotlin.reflect.KProperty1

/**
 * Retrieve an entity by its [ID] using [CrudRepository.findById],
 * or throw an [EntityNotFound] exception if none was found.
 * @see CrudRepository.findById
 * @see EntityNotFound
 */
inline fun <reified T : Any, ID : Any> CrudRepository<T, ID>.requireById(id: ID): T =
    findById(id).getOrNull() ?: throw EntityNotFound<T>(id)

/**
 * Reified version of [EntityManager.find].
 */
inline fun <reified T> EntityManager.find(id: Any): T? = find(T::class.java, id)

/**
 * Execute [EntityManager.flush] followed by [EntityManager.clear].
 */
fun EntityManager.flushAndClear() {
    flush()
    clear()
}

/**
 * Convenience function for creating [Specification]s using the [CriteriaBuilder] as context.
 * The resulting specification is an [AccessControlSpecification], so it can also be applied within subqueries,
 * in which case [CriteriaDsl.queryOrNull] is `null`.
 */
fun <E : Any> where(filter: DslSpecification<E>): Specification<E> =
    AccessControlSpecification { from, abstractQuery, builder ->
        CriteriaDsl(builder, abstractQuery as? CriteriaQuery<*>).filter(from)
    }

typealias DslSpecification<E> = CriteriaDsl.(Root<E>) -> Predicate?

private val hibernateSqmPathPresent =
    ClassUtils.isPresent("org.hibernate.query.sqm.tree.domain.SqmPath", null)

/**
 * Resolve an attribute path by name, which may also be declared by a subtype of this path's entity type.
 * Since Hibernate 7, subtype attributes are only resolved when requested explicitly using [SqmPath.get],
 * otherwise this falls back to the standard [Path.get].
 */
@Suppress("UNCHECKED_CAST")
internal fun <Y> Path<*>.getIncludingSubtypes(attributeName: String): Path<Y> =
    if (hibernateSqmPathPresent && this is SqmPath<*>) get(attributeName, true) as Path<Y>
    else get(attributeName)

/**
 * Extension of [CriteriaBuilder] that marks the implementor is wrapping an underlying
 * CriteriaBuilder delegate, which can be accessed using [unwrap].
 */
interface CriteriaBuilderWrapper : CriteriaBuilder {
    fun unwrap(): CriteriaBuilder
}

/**
 * Convenience extension of [CriteriaBuilder] proving access to an associated [CriteriaQuery]
 */
class CriteriaDsl(
    val builder: CriteriaBuilder,
    val queryOrNull: CriteriaQuery<*>?,
) : CriteriaBuilderWrapper, CriteriaBuilder by builder {
    val query get() = checkNotNull(queryOrNull) { "Missing CriteriaQuery" }
    override fun unwrap() = builder
}

/**
 * Convenience function to completely unwrap a [CriteriaBuilder] that may be wrapped
 * (e.g. as a [CriteriaBuilderWrapper] or [CriteriaDsl]) recursively, returning
 * the actual underlying CriteriaBuilder instance.
 */
fun CriteriaBuilder.unwrapCompletely(): CriteriaBuilder = when (this) {
    is CriteriaDsl -> builder.unwrapCompletely()
    is CriteriaBuilderWrapper -> unwrap().unwrapCompletely()
    else -> this
}

/**
 * Convenience function to [completely unwrap][unwrapCompletely] and attempt to cast this [CriteriaBuilder]
 * into a specialized sub-interface to access non-standard JPA functionality, i.e.
 * use the Hibernate-specific `org.hibernate.query.criteria.HibernateCriteriaBuilder`.
 */
inline fun <reified X : CriteriaBuilder> CriteriaBuilder.trySpecialized(): X? {
    val underlying = unwrapCompletely()
    return underlying as? X
}

/**
 * Get an existing [Join] for the given attribute, or add a new join to this [Path],
 * if this path is a [From] expression. Otherwise, [Path.get] the given attribute.
 */
fun <E : Any, R> Path<E>.reusableJoin(property: KProperty1<*, R>, joinType: JoinType): Path<R> {
    return reusableJoin(property.name, joinType)
}

/**
 * Get an existing [Join] for the given attribute, or add a new join to this [Path],
 * if this path is a [From] expression. Otherwise, [Path.get] the given attribute.
 */
fun <E, R> Path<E>.reusableJoin(attributeName: String, joinType: JoinType): Path<R> {
    // Only possible if the base path is a from- or join-expression
    if (this !is From<*, E>) {
        return get(attributeName)
    }

    // Reuse existing join path if join type and joined attribute match
    // Otherwise, add a new join to the from-expression
    val existing = joins.find { (it.joinType == joinType) && (it.attribute.name == attributeName) }

    @Suppress("UNCHECKED_CAST")
    return existing as Join<E, R>?
        ?: joinIncludingSubtypes(attributeName, joinType)
}

/**
 * Join an attribute by name, which may also be declared by a subtype of this path's entity type.
 * Since Hibernate 7, [From.join] by attribute name does not resolve subtype attributes,
 * so singular and plural attributes are resolved explicitly, and joined using the resolved attribute instead.
 * Without Hibernate, this falls back to the standard [From.join] by attribute name.
 */
@Suppress("UNCHECKED_CAST")
fun <E, R> From<*, E>.joinIncludingSubtypes(attributeName: String, joinType: JoinType): Join<E, R> {
    if (hibernateSqmPathPresent && this is SqmPath<*>) {
        when (val attribute = referencedPathSource.getSubPathSource(attributeName, true)) {
            is SingularAttribute<*, *> -> return join(attribute as SingularAttribute<in E, R>, joinType)
            is SetAttribute<*, *> -> return join(attribute as SetAttribute<in E, R>, joinType)
            is ListAttribute<*, *> -> return join(attribute as ListAttribute<in E, R>, joinType)
            is MapAttribute<*, *, *> -> return join(attribute as MapAttribute<in E, Any?, R>, joinType)
            is CollectionAttribute<*, *> -> return join(attribute as CollectionAttribute<in E, R>, joinType)
        }
    }
    return join(attributeName, joinType)
}
