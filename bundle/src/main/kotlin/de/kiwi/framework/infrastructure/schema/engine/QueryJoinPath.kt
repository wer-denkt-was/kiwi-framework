package de.kiwi.framework.infrastructure.schema.engine

import jakarta.persistence.OrderColumn
import jakarta.persistence.criteria.*
import jakarta.persistence.metamodel.*
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Represents a recursive join from a root entity, via a chain of parents, to a child entity.
 * @param R Root entity type this join path originates from
 * @param C Leaf child entity type this join path leads to
 */
internal sealed interface QueryJoinPath<R : Any, C> {
    /**
     * Recursively join from a given root down to the child entity type
     */
    fun joinFrom(root: Root<R>): From<*, C>

    /**
     * @param R Root entity type
     */
    class RootPath<R : Any> : QueryJoinPath<R, R> {
        override fun joinFrom(root: Root<R>): Root<R> = root
    }

    /**
     * @param R Root entity type, root ancestor of [P] and [C]
     * @param P Parent entity type, immediate parent of [C]
     * @param C Child entity type at the leaf of the join path
     */
    class CollectionPath<R : Any, P : Any, C>(
        val parent: QueryJoinPath<R, P>,
        val child: PluralAttribute<in P, *, C>,
        val joinType: JoinType,
    ) : QueryJoinPath<R, C> {
        override fun joinFrom(root: Root<R>): PluralJoin<P, out Collection<C>, C> {
            val base: From<*, P> = parent.joinFrom(root)
            return when (child) {
                is ListAttribute<P, C> -> base.join(child, joinType)
                is SetAttribute<P, C> -> base.join(child, joinType)
                is CollectionAttribute<P, C> -> base.join(child, joinType)
                else -> throw IllegalArgumentException("Unexpected plural attribute: $child")
            }
        }

        fun indexExpression(join: PluralJoin<P, out Collection<C>, C>): Expression<Int>? = when {
            child.collectionType != PluralAttribute.CollectionType.LIST -> null
            child !is ListAttribute<P, C> -> null
            join !is ListJoin<P, C> -> null
            !hasOrderColumn(child) -> null
            else -> join.index()
        }

        private fun hasOrderColumn(attribute: ListAttribute<P, C>): Boolean {
            val member = attribute.javaMember ?: return false
            return when (member) {
                is Field -> member.isAnnotationPresent(OrderColumn::class.java)
                is Method -> member.isAnnotationPresent(OrderColumn::class.java)
                else -> false
            }
        }
    }

    /**
     * Join path to the value entity of a MapJoin
     * @param R Root entity type, root ancestor of [P], [K] and [V]
     * @param P Parent entity type, immediate parent of [K] and [V]
     * @param K Key type of entity at leaf of the join path
     * @param V Value type of entity at leaf of the join path
     */
    class MapValuePath<R : Any, P : Any, K, V>(
        val parent: QueryJoinPath<R, P>,
        val child: MapAttribute<in P, K, V>,
    ) : QueryJoinPath<R, V> {
        override fun joinFrom(root: Root<R>): MapJoin<P, K, V> {
            val base: From<*, P> = parent.joinFrom(root)
            return base.join(child)
        }
    }

    /**
     * Join path to the key entity of a MapJoin, obtained from the corresponding [MapValuePath]
     */
    class MapKeyPath<R : Any, P : Any, K, V>(
        val mapPath: MapValuePath<R, P, K, V>,
    ) : QueryJoinPath<R, K> {
        override fun joinFrom(root: Root<R>): From<*, K> {
            val base: From<*, P> = mapPath.parent.joinFrom(root)
            val mapJoin = base.join(mapPath.child)
            return mapJoin.key() as From<*, K>
        }
    }
}
