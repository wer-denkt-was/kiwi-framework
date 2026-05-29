package de.kiwi.api.domain

import de.kiwi.api.util.CriteriaDsl
import jakarta.persistence.criteria.*
import jakarta.persistence.metamodel.SingularAttribute
import org.springframework.data.jpa.domain.Specification
import java.util.*
import kotlin.jvm.optionals.getOrNull
import de.kiwi.api.util.where as specificationWhere

/**
 * Root provider for [AccessControlBuilder] instances, entrypoint to the relation-based access control system.
 * A default instance of this provider is expected to be available using the [ServiceLoader] facility.
 */
interface AccessControlProvider {
    /**
     * @param U User entity type
     */
    fun <U : Any> provideBuilder(
        userEntityClass: Class<U>,
        userMatch: AccessControlUserMatch<U>,
    ): AccessControlBuilder<U>

    fun <P : Any, C : Any> provideChildAccess(
        parentEntityClass: Class<P>,
        parentAccessControl: AccessControl<P>,
        childMatch: AccessControlParentChildMatch<P, C>,
    ): AccessControl<C>

    companion object {
        val default by lazy { loadProvider() }

        fun loadProvider(classLoader: ClassLoader? = null): AccessControlProvider {
            val serviceLoader = classLoader
                ?.let { ServiceLoader.load(AccessControlProvider::class.java, classLoader) }
                ?: ServiceLoader.load(AccessControlProvider::class.java)

            return serviceLoader.findFirst().getOrNull()
                ?: throw UnsupportedOperationException("AccessControlProvider service unavailable")
        }
    }
}

/**
 * Interface to create [AccessControl] instances using [AccessControlBuilder.invoke].
 * A builder specifies the user-entity underlying the access control, and how it matches to a given [UserDescriptor].
 * @param U User entity type
 */
interface AccessControlBuilder<U : Any> {
    /**
     * @param E Accessed entity type
     */
    operator fun <E : Any> invoke(
        accessControl: AccessControlEntityMatch<E, U>,
    ): AccessControl<E>

    companion object {
        /**
         * Create a new [AccessControlBuilder] for checking user access to entities using a given
         * UserEntity type [U] with a function matching UserEntities to a [UserDescriptor].
         * @param U User entity type used for access control
         */
        inline fun <reified U : Any> byUserRelation(
            provider: AccessControlProvider = AccessControlProvider.default,
            noinline userSpecification: AccessControlUserMatch<U>,
        ): AccessControlBuilder<U> {
            return provider.provideBuilder(userEntityClass = U::class.java, userMatch = userSpecification)
        }
    }
}

/**
 * Interface providing [AccessControl.checkAuthorized] to build an access control [Specification],
 * returning a [Predicate] which only allows a given [UserDescriptor] to see entities they are allowed to access.
 * Instances of this interface can be created using [AccessControlBuilder.invoke].
 * @param E Accessed entity type
 */
fun interface AccessControl<E : Any> {

    /**
     * Generate a [Specification] filtering access to the root entity of a query for the given [UserDescriptor]
     */
    fun checkAuthorized(user: UserDescriptor): AccessControlSpecification<E>

    /**
     * Generate a [Specification] filtering access to the root entity of a query for the given [UserDescriptor],
     * or allowing full unfiltered access if the given [UserDescriptor.isAdmin] property is `true`
     */
    fun adminOrCheckAuthorized(user: UserDescriptor): AccessControlSpecification<E> =
        if (user.isAdmin) accessControlUnrestricted()
        else checkAuthorized(user)

    /** Compose this [AccessControl] instance with another using [Specification.or] disjunction */
    infix fun or(other: AccessControl<E>) = AccessControl { user ->
        checkAuthorized(user) or other.checkAuthorized(user)
    }

    /** Compose this [AccessControl] instance with another using [Specification.and] conjunction */
    infix fun and(other: AccessControl<E>) = AccessControl { user ->
        checkAuthorized(user) and other.checkAuthorized(user)
    }

    /** Create a negated instance of this [AccessControl] using [Specification.not] inversion */
    operator fun not() = AccessControl { user -> !checkAuthorized(user) }

    /**
     * Compose this [AccessControl] instance with a precondition [Specification] that must be satisfied
     * additionally before user authorization is checked, using [Specification.and] conjunction
     */
    infix fun and(specification: Specification<E>) = AccessControl { user ->
        specification.lift() and checkAuthorized(user)
    }

    /**
     * Compose this [AccessControl] instance with a precondition [Specification] that can be satisfied
     * to allow access instead of user authorization being checked, using [Specification.or] disjunction
     */
    infix fun or(specification: Specification<E>) = AccessControl { user ->
        specification.lift() or checkAuthorized(user)
    }

    companion object {
        /**
         * Create an [AccessControl] instance from a function that produces a [Specification] for a given [UserDescriptor].
         * This will implicitly lift the given [Specification] to a specialized [AccessControlSpecification].
         * @param filter Function defining a [Specification] for a given [UserDescriptor]
         * @return [AccessControl] instance using the provided specification logic
         */
        inline fun <E : Any> of(crossinline filter: (UserDescriptor) -> Specification<E>) =
            AccessControl { user ->
                val specification = filter(user)
                specification.lift()
            }

        /**
         * Create an [AccessControl] instance from a specification function that produces a [Predicate] for a given entity [Root] and [UserDescriptor].
         * @param filter Specification lambda producing a [Predicate] for the entity type [E] and a user
         * @return [AccessControl] instance using the provided specification logic
         * @see de.kiwi.api.util.where
         */
        inline fun <E : Any> where(crossinline filter: CriteriaDsl.(Root<E>, UserDescriptor) -> Predicate?) =
            AccessControl { user ->
                val specification = specificationWhere { root -> filter(root, user) }
                specification.lift()
            }

        /**
         * Combine multiple [AccessControl] instances using logical AND (conjunction).
         * The resulting [AccessControl] only grants access if all provided access-controls grant access.
         * @param controls The [AccessControl] instances to combine
         * @return An [AccessControl] instance representing the conjunction of all controls
         */
        fun <E : Any> allOf(vararg controls: AccessControl<E>) = AccessControl<E> { user ->
            controls.fold(accessControlUnrestricted()) { conjunction, accessControl ->
                conjunction and accessControl.checkAuthorized(user)
            }
        }

        /**
         * Combine multiple [AccessControl] instances using logical OR (disjunction)
         * The resulting [AccessControl] grants access if any of the provided access-controls grant access.
         * @param controls The [AccessControl] instances to combine
         * @return An [AccessControl] instance representing the disjunction of all controls
         */
        fun <E : Any> anyOf(vararg controls: AccessControl<E>) = AccessControl<E> { user ->
            controls.fold(accessControlUnrestricted()) { disjunction, accessControl ->
                disjunction or accessControl.checkAuthorized(user)
            }
        }
    }
}

/**
 * Define an [AccessControl] for a child entity of type [C] based on an existing parent [AccessControl] for type [P],
 * using a [childMatch] predicate function to relate matching parent and child entities.
 *
 * This provides a convenient way to propagate access rights from a parent entity to its child entities
 * by specifying a predicate representing the parent-child relationship.
 *
 * @param provider The [AccessControlProvider] to use (defaults to [AccessControlProvider.default])
 * @param childMatch Predicate function specifying how to match a parent- and child entity for access control
 * @return An [AccessControl] instance for the child entity type [C], inheriting access control from its parent [P]
 */
inline fun <reified P : Any, C : Any> AccessControl<P>.childAccess(
    provider: AccessControlProvider = AccessControlProvider.default,
    noinline childMatch: AccessControlParentChildMatch<P, C>,
): AccessControl<C> {
    return provider.provideChildAccess(
        parentEntityClass = P::class.java,
        parentAccessControl = this,
        childMatch = childMatch,
    )
}

/**
 * Derive an [AccessControl] from an existing instance that performs a _side effect_ in addition to
 * the given access-control predicate.
 * This side effect does not modify the [Predicate] returned from the [AccessControlSpecification.accessControlPredicate],
 * but can modify the given [Root], for example adding [Root.fetch] joins for eagerly loading associations.
 *
 * @param effect Side effect block that is applied to the given [Root] before an access-control [Predicate] is created
 * @return An [AccessControl] instance derived from the receiver, with the additionally attached side effect
 */
inline fun <E : Any> AccessControl<E>.withSideEffect(crossinline effect: CriteriaDsl.(Root<E>) -> Unit): AccessControl<E> {
    return object : AccessControlWithSideEffect<E>(this) {
        override fun applySideEffect(context: CriteriaDsl, root: Root<E>) {
            context.effect(root)
        }
    }
}

/**
 * Define an [AccessControl] for a child entity of type [C] based on an existing parent [AccessControl] for type [P],
 * using a [childPath] function to specify the navigation from child to parent entity.
 *
 * This provides a convenient way to propagate access rights from parent to child entities when there is a direct
 * reference or navigation path from the child to its parent.
 *
 * @param provider The [AccessControlProvider] to use (defaults to [AccessControlProvider.default])
 * @param childPath Function specifying how to navigate from the child entity to its parent
 * @return An [AccessControl] instance for the child entity type [C], inheriting access control from its parent [P]
 */
inline fun <reified P : Any, C : Any> AccessControl<P>.childAccess(
    provider: AccessControlProvider = AccessControlProvider.default,
    crossinline childPath: AccessControlParentChildPath<P, C>,
): AccessControl<C> {
    return childAccess(provider) { parent, child ->
        val childToParent = childPath(child)
        equal(parent, childToParent)
    }
}

/**
 * Extension function to create a find-by-ID specification for [BaseEntity] classes
 * in conjunction with applying the given [AccessControl] restriction.
 */
fun <E : BaseEntity<ID>, ID : EntityId> AccessControl<E>.filterByIdAuthorized(
    id: ID,
    user: UserDescriptor,
    allowAdmin: Boolean = true,
): Specification<E> {
    val byId = specificationWhere<E> { root -> equal(root[BaseEntity_.id][EntityId_.idValue], id.value) }
    val authorized = if (allowAdmin) adminOrCheckAuthorized(user) else checkAuthorized(user)
    return byId.and(authorized)
}

/**
 * Function specifying a predicate matching a user entity with a given [UserDescriptor],
 * for example using the [UserDescriptor.userId] or [UserDescriptor.identity].
 * @param U User entity type
 */
typealias AccessControlUserMatch<U> =
        AccessControlContext.(userRoot: Root<U>, user: UserDescriptor) -> Predicate

/**
 * Function specifying a predicate matching if a user entity has an access relation to a target entity.
 * @param U User entity type
 * @param E Accessed entity type
 */
typealias AccessControlEntityMatch<E, U> =
        AccessControlContext.(entityRoot: Root<E>, userRoot: Root<U>) -> Predicate

/**
 * Function specifying a predicate matching a parent entity to a related child entity.
 * @param P Parent entity type
 * @param C Child entity type
 */
typealias AccessControlParentChildMatch<P, C> =
        AccessControlContext.(parentRoot: Root<P>, childRoot: Root<C>) -> Predicate

/**
 * Function specifying the matching path from a child to a related parent entity.
 * @param P Parent entity type
 * @param C Child entity type
 */
typealias AccessControlParentChildPath<P, C> =
        AccessControlContext.(childRoot: Root<C>) -> Expression<P>

/**
 * This specialized Specification interface allows specifications to be used as part of a nested subquery.
 * Instances must be called with a non-null [CriteriaQuery] parameter, otherwise an [IllegalStateException] is thrown.
 */
fun interface AccessControlSpecification<E : Any> : Specification<E> {
    /**
     * @see Specification.toPredicate
     */
    override fun toPredicate(root: Root<E>, query: CriteriaQuery<*>?, builder: CriteriaBuilder): Predicate? {
        checkNotNull(query) { "Missing CriteriaQuery in AccessControlSpecification" }
        return accessControlPredicate(root, query, builder)
    }

    /**
     * Specialized version of [Specification.toPredicate], accepting a generalized [AbstractQuery]
     * instead of specifically a [CriteriaQuery] instance
     */
    fun accessControlPredicate(root: Root<E>, abstractQuery: AbstractQuery<*>, builder: CriteriaBuilder): Predicate?

    /**
     * @see Specification.not
     */
    operator fun not() = AccessControlSpecification { root, query, builder ->
        val predicate = accessControlPredicate(root, query, builder) ?: builder.conjunction()
        builder.not(predicate)
    }

    /**
     * @see Specification.or
     */
    override infix fun or(other: Specification<E>?) = AccessControlSpecification { root, query, builder ->
        val left = accessControlPredicate(root, query, builder)
        val right = other.lift().accessControlPredicate(root, query, builder)
        builder.composeNullable(left, right, CriteriaBuilder::or)
    }

    /**
     * @see Specification.and
     */
    override infix fun and(other: Specification<E>?) = AccessControlSpecification { root, query, builder ->
        val left = accessControlPredicate(root, query, builder)
        val right = other.lift().accessControlPredicate(root, query, builder)
        builder.composeNullable(left, right, CriteriaBuilder::and)
    }
}

/**
 * Context interface encapsulating the state of an access control subquery.
 * Extends [CriteriaBuilder] to allow building context-based query operations.
 */
interface AccessControlContext : CriteriaBuilder {
    /** Criteria query currently responsible for the target entity */
    val entityQuery: AbstractQuery<*>

    /** Criteria subquery performing access-control correlated to the target entity */
    val accessControlSubquery: Subquery<*>

    /**
     * **Optimized join, reusing joins from parent query within subquery**.
     *
     * Optimization method:
     * - Unoptimized: `from A join B, subquery (correlate A as A' join B', from U join Bu, where B' = Bu)`
     * - Optimized:   `from A join B, subquery (correlate B as B', from U join Bu, where B' = Bu)`
     *
     * __Example__:
     * ```sql
     * -- Unoptimized --
     * select root_p, root_c
     * from Parent as root_p join root_p.children as root_c
     * where exists (select 1
     *   from Child as sub_c, UserAccount as u join u.authorized_children as auth_c
     *   where u.identity = :user_identity
     *     and sub_c.parent = root_p -- This essentially performs correlated(root_p).join(children)
     *     and auth_c = sub_c);
     *
     * -- Optimized with crossCorrelatedJoin --
     * select root_p, root_c
     * from Parent as root_p join root_p.children as root_c
     * where exists (select 1
     *   from UserAccount as u join u.authorized_children as auth_c
     *   where u.identity = :user_identity
     *     and auth_c = root_c);
     * ```
     *
     * If no join already exists in the parent query for the given root,
     * a new join exclusive to the correlated subquery will be created.
     */
    fun <X : Any, Y : Any> From<*, X>.crossCorrelatedJoin(
        attribute: SingularAttribute<X, Y>,
        joinType: JoinType = JoinType.INNER,
    ): Join<X, Y>

    fun <X : Any, Y : Any> From<*, X>.crossCorrelatedJoin(
        attributeName: String,
        joinType: JoinType = JoinType.INNER,
    ): Join<X, Y>
}

/**
 * Adopt this [Specification] as a specialized [AccessControlSpecification],
 * either by casting or by wrapping a new instance that attempts to downcast
 * the given [AbstractQuery] into a [CriteriaQuery], or passes `null` otherwise
 */
@PublishedApi
internal fun <E : Any> Specification<E>?.lift(): AccessControlSpecification<E> = when (this) {
    is AccessControlSpecification<E> -> this
    null -> accessControlUnrestricted()
    else -> AccessControlSpecification { root, abstractQuery, builder ->
        val query = abstractQuery as? CriteriaQuery<*>
        toPredicate(root, query, builder)
    }
}

@Suppress("UNCHECKED_CAST")
private fun <E : Any> accessControlUnrestricted() = accessControlAlwaysNull as AccessControlSpecification<E>

private val accessControlAlwaysNull = AccessControlSpecification<Nothing> { _, _, _ -> null }

private inline fun CriteriaBuilder.composeNullable(
    left: Predicate?,
    right: Predicate?,
    combine: CriteriaBuilder.(left: Predicate, right: Predicate) -> Predicate?,
): Predicate? = when {
    left == null -> right
    right == null -> left
    else -> combine(left, right)
}

@PublishedApi
internal abstract class AccessControlWithSideEffect<E : Any>(
    private val delegate: AccessControl<E>,
) : AccessControl<E> by delegate {

    abstract fun applySideEffect(context: CriteriaDsl, root: Root<E>)

    override fun checkAuthorized(user: UserDescriptor): AccessControlSpecification<E> =
        delegate.checkAuthorized(user).withSideEffect()

    override fun adminOrCheckAuthorized(user: UserDescriptor): AccessControlSpecification<E> =
        delegate.adminOrCheckAuthorized(user).withSideEffect()

    private fun AccessControlSpecification<E>.withSideEffect() =
        AccessControlSpecification { root, abstractQuery, builder ->
            val queryOrNull = abstractQuery as? CriteriaQuery<*>
            applySideEffect(CriteriaDsl(builder, queryOrNull), root)
            accessControlPredicate(root, abstractQuery, builder)
        }
}
