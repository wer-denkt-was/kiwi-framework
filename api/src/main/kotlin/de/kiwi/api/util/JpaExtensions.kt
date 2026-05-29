package de.kiwi.api.util

import de.kiwi.api.domain.EntityNotFound
import jakarta.persistence.EntityManager
import jakarta.persistence.criteria.*
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.repository.CrudRepository
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
 */
fun <E : Any> where(filter: DslSpecification<E>): Specification<E> =
    Specification { from, query, builder -> CriteriaDsl(builder, query).filter(from) }

typealias DslSpecification<E> = CriteriaDsl.(Root<E>) -> Predicate?

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
        ?: join<E, R>(attributeName, joinType)
}
