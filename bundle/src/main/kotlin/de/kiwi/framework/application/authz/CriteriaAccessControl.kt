package de.kiwi.framework.application.authz

import de.kiwi.api.domain.*
import de.kiwi.api.util.joinIncludingSubtypes
import jakarta.persistence.criteria.*
import jakarta.persistence.metamodel.SingularAttribute

class CriteriaAccessControlProvider : AccessControlProvider {
    override fun <U : Any> provideBuilder(
        userEntityClass: Class<U>,
        userMatch: AccessControlUserMatch<U>,
    ): AccessControlBuilder<U> {
        return CriteriaAccessControlBuilder(userEntityClass, userMatch)
    }

    override fun <P : Any, C : Any> provideChildAccess(
        parentEntityClass: Class<P>,
        parentAccessControl: AccessControl<P>,
        childMatch: AccessControlParentChildMatch<P, C>,
    ): AccessControl<C> {
        return CriteriaParentChildAccessControl(parentEntityClass, parentAccessControl, childMatch)
    }
}

private class CriteriaAccessControlBuilder<U : Any>(
    private val userEntityClass: Class<U>,
    private val userMatch: AccessControlUserMatch<U>,
) : AccessControlBuilder<U> {
    override operator fun <E : Any> invoke(
        accessControl: AccessControlEntityMatch<E, U>,
    ) = CriteriaAccessControl(accessControl)

    inner class CriteriaAccessControl<E : Any>(
        private val entityMatch: AccessControlEntityMatch<E, U>,
    ) : AccessControl<E> {

        /**
         * The form of the query conforms to this pattern:
         * ```sql
         * FROM Entity e WHERE EXISTS (
         *  SELECT 1
         *  FROM User u JOIN [...]
         *  WHERE {{ userSpecification(u, :user) }}
         *    AND {{ accessControl(e, u) }}
         * )
         * ```
         * Where the subquery predicate checks the correlation between the authenticated `User u` and
         * the correlated `Entity e` using relational access control.
         */
        override fun checkAuthorized(user: UserDescriptor): AccessControlSpecification<E> {
            return AccessControlSpecification { entityRoot, query, builder ->
                val subquery = query.subquery(Boolean::class.java)
                val context = CriteriaAccessControlContext(query, subquery, builder)

                val userEntityRoot = subquery.from(userEntityClass)
                val correlatedRoot = subquery.correlate(entityRoot)

                subquery.select(builder.literal(true))
                subquery.where(
                    context.userMatch(userEntityRoot, user),
                    context.entityMatch(correlatedRoot, userEntityRoot),
                )

                builder.exists(subquery)
            }
        }
    }
}

private class CriteriaParentChildAccessControl<P : Any, C : Any>(
    private val parentEntityClass: Class<P>,
    private val parentAccess: AccessControl<P>,
    private val childMatch: AccessControlParentChildMatch<P, C>,
) : AccessControl<C> {
    /**
     * The form of the query conforms to this pattern:
     * ```sql
     * FROM Child c WHERE EXISTS (
     *   SELECT 1
     *   FROM Parent AS p
     *   WHERE {{ childMatch(p, c) }}
     *     AND {{ parentAccess.checkAuthorized(user).toPredicate(p) }}
     * )
     * ```
     */
    override fun checkAuthorized(user: UserDescriptor): AccessControlSpecification<C> {
        val parentAccessSpecification = parentAccess.checkAuthorized(user)
        return AccessControlSpecification { childRoot, query, builder ->
            val subquery = query.subquery(Boolean::class.java)
            val context = CriteriaAccessControlContext(query, subquery, builder)

            val parentRoot = subquery.from(parentEntityClass)
            val correlatedChildRoot = subquery.correlate(childRoot)

            val matchChildPredicate = context.childMatch(parentRoot, correlatedChildRoot)
            val parentAccessPredicate = parentAccessSpecification
                .accessControlPredicate(parentRoot, subquery, builder)
                ?: builder.conjunction()

            subquery.select(builder.literal(true))
            subquery.where(matchChildPredicate, parentAccessPredicate)

            builder.exists(subquery)
        }
    }
}

private class CriteriaAccessControlContext(
    override val entityQuery: AbstractQuery<*>,
    override val accessControlSubquery: Subquery<*>,
    builder: CriteriaBuilder,
) : AccessControlContext, CriteriaBuilder by builder {

    override fun <X : Any, Y : Any> From<*, X>.crossCorrelatedJoin(
        attribute: SingularAttribute<X, Y>,
        joinType: JoinType,
    ): Join<X, Y> {
        @Suppress("UNCHECKED_CAST")
        return this
            .takeIf { isCorrelated }
            ?.correlationParent?.joins
            ?.find { (it.attribute == attribute) && (it.joinType == joinType) }
            ?.let { accessControlSubquery.correlate(it as Join<X, Y>) }
            ?: this.join(attribute, joinType)
    }

    override fun <X : Any, Y : Any> From<*, X>.crossCorrelatedJoin(
        attributeName: String,
        joinType: JoinType,
    ): Join<X, Y> {
        @Suppress("UNCHECKED_CAST")
        return this
            .takeIf { isCorrelated }
            ?.correlationParent?.joins
            ?.find { (it.attribute.name == attributeName) && (it.joinType == joinType) }
            ?.let { accessControlSubquery.correlate(it as Join<X, Y>) }
            ?: this.joinIncludingSubtypes(attributeName, joinType)
    }
}
