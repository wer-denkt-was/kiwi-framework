package de.kiwi.api.schema

import de.kiwi.api.application.service.UserDescriptorProvider
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.schema.FieldAccessControl.Companion.invoke
import de.kiwi.api.schema.FieldAccessControl.Requirement
import de.kiwi.api.util.slf4j

private val log = slf4j<FieldAccessControl<*>>()

/**
 * Entrypoint to field-based access control, filtering columns and relations
 * when accessing data using EntitySchema fieldsets.
 */
interface FieldAccessControl<E : Any> {

    /**
     * List the [Requirements][Requirement] (columns/relations) this [FieldAccessControl] needs
     * in the given [Context] to evaluate field access.
     */
    val requirements: Collection<Requirement<E>>

    /**
     * Check if the given [UserDescriptor] is allowed to access the associated field value within the given [Context].
     * The context is expected to provide values for the columns/relations indicated by [requirements].
     */
    fun checkAccess(context: Context<E>, user: UserDescriptor): Boolean

    /**
     * Check if the current user, determined using the [UserDescriptorProvider.Global],
     * is allowed to access the associated field value within the given [Context].
     * The context is expected to provide values for the columns/relations indicated by [requirements].
     */
    fun checkAccess(context: Context<E>) = checkAccess(context, user = globalUserDescriptor())

    /** Combine this [FieldAccessControl] with another, using a logical `or` disjunction operation */
    infix fun or(other: FieldAccessControl<E>) = anyOf(this, other)

    /** Combine this [FieldAccessControl] with another, using a logical `and` conjunction operation */
    infix fun and(other: FieldAccessControl<E>) = allOf(this, other)

    /** Invert this [FieldAccessControl], using a logical `not` inversion operation */
    operator fun not() = negate(this)

    /**
     * Provides access to the values of columns and related entities (relations) for a specific entity instance.
     *
     * The [Context] interface is used during [FieldAccessControl.checkAccess] to supply the necessary data for
     * evaluating field-level permissions. It allows retrieving the value of a column or navigating to the context of
     * a related entity via a to-one relation. Implementations of this interface are expected to provide the values
     * for all columns and relations listed in the [requirements] of the associated [FieldAccessControl].
     */
    interface Context<E : Any> {
        operator fun <T> get(column: ColumnField<E, T, *>): T?
        operator fun <R> get(toOne: ToOneField<E, R>): Context<R & Any> = getOrNull(toOne) ?: NullContext()
        fun <R> getOrNull(toOne: ToOneField<E, R>): Context<R & Any>?
        fun <C : E> downcast(subtype: SubtypeField<E, C, C>): Context<C>
    }

    /**
     * Represents a requirement for data needed to evaluate field-level access control.
     *
     * A [Requirement] specifies either a column or a relation (to-one) that must be provided in the [Context]
     * when performing access checks. This allows the [FieldAccessControl] to declare exactly which fields or
     * related entities are necessary for its logic, enabling efficient and precise permission evaluation.
     *
     * - [Column]: Indicates a specific column whose value is required.
     * - [Relation]: Indicates a to-one relation, along with nested requirements for the related entity.
     */
    sealed interface Requirement<E : Any> {
        data class Column<E : Any>(
            val column: ColumnField<E, *, *>,
        ) : Requirement<E>

        data class Relation<E : Any, R>(
            val toOne: ToOneField<E, R>,
            val nested: List<Requirement<R & Any>>,
        ) : Requirement<E>
    }

    companion object {
        /**
         * Construct a [FieldAccessControl] instance with the specified requirements and field-access checking logic.
         *
         * This operator function allows you to easily create a [FieldAccessControl] by providing:
         *  - a [require] lambda to declare which columns or relations are required for access evaluation
         *  - a [checkAccess] predicate that implements the access logic for a given [Context] and [UserDescriptor]
         *
         * Example usage:
         * ```kotlin
         * val control = FieldAccessControl<MyEntity>(
         *     require = {
         *         add(MyEntitySchema.isPublic)
         *         add(MyEntitySchema.relatedEntity) {
         *             add(RelatedSchema.authorUserId)
         *         }
         *     }
         * ) { context, user ->
         *     user.isAdmin ||
         *         context[MyEntitySchema.isPublic] ||
         *         context[MyEntitySchema.relatedEntity][RelatedSchema.authorUserId] == user.userId
         * }
         * ```
         *
         * @param require Lambda function to specify required columns/relations for access evaluation
         * @param checkAccess Predicate to determine if access is permitted for a given context and user
         * @return A [FieldAccessControl] instance providing the specified requirements and implementing its logic
         */
        inline operator fun <E : Any> invoke(
            noinline require: FieldAccessRequirementBuilder<E> = {},
            crossinline checkAccess: FieldAccessControlPredicate<E>,
        ) = object : FieldAccessControl<E> {
            override val requirements = FieldAccessRequirements.build(require)
            override fun checkAccess(context: Context<E>, user: UserDescriptor) = checkAccess(context, user)
        }

        /**
         * Constructs a [FieldAccessControl] instance by implicitly deriving requirements from the provided
         * field-access check logic.
         *
         * This function analyzes the [checkAccess] predicate to determine which columns and relations are accessed,
         * and automatically collects them as requirements. This allows you to define access control logic without
         * explicitly listing required fields.
         *
         * Requirement gathering works by executing the [checkAccess] predicate once with a special dummy [Context] that
         * records every column and relation accessed. Every column access will always return a *null* value during this
         * dummy execution. The resulting set of requirements is then used for field-access control evaluation.
         *
         * **Notice:** Implicit requirement gathering *will* fail if your [checkAccess] logic uses short-circuiting
         * (e.g., logical and/or) that prevents some context accesses from being executed during requirement collection.
         * In such cases, not all required fields will be detected. To avoid this, ensure all context accesses are
         * always evaluated during requirement gathering, or use the [explicit builder][invoke] instead.
         *
         * @param checkAccess Predicate to determine if access is permitted for a given context and user.
         * @return A [FieldAccessControl] instance with requirements inferred from the predicate logic.
         */
        fun <E : Any> withRequirementsFromContext(
            checkAccess: FieldAccessControlPredicate<E>,
        ) = object : FieldAccessControl<E> {
            override val requirements = gatherRequirementsFromPredicate(checkAccess)
            override fun checkAccess(context: Context<E>, user: UserDescriptor) = checkAccess(context, user)
        }

        fun <E : Any> allOf(vararg control: FieldAccessControl<E>): FieldAccessControl<E> =
            ConjunctionFieldAccessControl(control.toList())

        fun <E : Any> anyOf(vararg control: FieldAccessControl<E>): FieldAccessControl<E> =
            DisjunctionFieldAccessControl(control.toList())

        fun <E : Any> negate(control: FieldAccessControl<E>): FieldAccessControl<E> =
            NegateFieldAccessControl(control)

        fun <E : Any> permitAll(): FieldAccessControl<E> {
            @Suppress("UNCHECKED_CAST")
            return permitAll as FieldAccessControl<E>
        }

        private val permitAll = object : FieldAccessControl<Nothing> {
            override val requirements = emptyList<Nothing>()
            override fun checkAccess(context: Context<Nothing>, user: UserDescriptor) = true
        }

        private fun globalUserDescriptor(): UserDescriptor {
            return UserDescriptorProvider.currentUser()
        }
    }
}

/**
 * Predicate function evaluating for a given entity [FieldAccessControl.Context] and [UserDescriptor] if
 * access to an entity field is allowed
 */
typealias FieldAccessControlPredicate<E> = (context: FieldAccessControl.Context<E>, user: UserDescriptor) -> Boolean

/**
 * Builder function defining an [FieldAccessControl.Requirement] tree for a given [FieldAccessControl] instance
 */
typealias FieldAccessRequirementBuilder<E> = FieldAccessRequirements<E>.() -> Unit

fun interface FieldAccessUserContext {
    fun fieldAccessUserDescriptor(): UserDescriptor
}

interface FieldAccessRequirements<E : Any> {

    fun add(column: ColumnField<E, *, *>)

    fun add(vararg columns: ColumnField<E, *, *>) = columns.forEach { add(it) }

    fun <R> add(toOne: ToOneField<E, R>, build: FieldAccessRequirementBuilder<R & Any>)

    companion object {
        fun <E : Any> build(builder: FieldAccessRequirementBuilder<E>): List<Requirement<E>> {
            val context = RequirementsContext<E>()
            context.builder()
            return context.requirements.toList()
        }
    }

    private class RequirementsContext<E : Any> : FieldAccessRequirements<E> {
        val requirements = mutableListOf<Requirement<E>>()

        override fun add(column: ColumnField<E, *, *>) {
            requirements.add(Requirement.Column(column))
        }

        override fun <R> add(toOne: ToOneField<E, R>, build: FieldAccessRequirementBuilder<R & Any>) {
            val nested = RequirementsContext<R & Any>().apply(build).requirements
            requirements.add(Requirement.Relation(toOne, nested))
        }
    }
}

private class NegateFieldAccessControl<E : Any>(
    private val delegate: FieldAccessControl<E>,
) : FieldAccessControl<E> by delegate {
    override fun checkAccess(context: FieldAccessControl.Context<E>, user: UserDescriptor) =
        !(delegate.checkAccess(context, user))
}

private abstract class CompositeFieldAccessControl<E : Any>(
    private val items: List<FieldAccessControl<E>>,
) : FieldAccessControl<E> {

    override val requirements = items.flatMap { it.requirements }
    override fun checkAccess(context: FieldAccessControl.Context<E>, user: UserDescriptor) =
        items.fold(initialResult) { result, item -> combineResults(result, item.checkAccess(context, user)) }

    protected abstract val initialResult: Boolean
    protected abstract fun combineResults(left: Boolean, right: Boolean): Boolean
}

private class ConjunctionFieldAccessControl<E : Any>(
    items: List<FieldAccessControl<E>>,
) : CompositeFieldAccessControl<E>(items) {
    override val initialResult = true
    override fun combineResults(left: Boolean, right: Boolean) = left && right
}

private class DisjunctionFieldAccessControl<E : Any>(
    items: List<FieldAccessControl<E>>,
) : CompositeFieldAccessControl<E>(items) {
    override val initialResult = false
    override fun combineResults(left: Boolean, right: Boolean) = left || right
}

private fun <E : Any> gatherRequirementsFromPredicate(predicate: FieldAccessControlPredicate<E>): List<Requirement<E>> {
    // Ignores predicate return value, but context gathers requirements requested from context
    val context = GatheringDummyContext<E>()
    predicate(context, UserDescriptor.ANONYMOUS)
    return context.gatherRequirements()
}

private class GatheringDummyContext<E : Any> : FieldAccessControl.Context<E> {
    val columns = ArrayList<ColumnField<E, *, *>>()
    val toOneRelations = HashMap<ToOneField<E, *>, GatheringDummyContext<*>>()

    @Suppress("UNCHECKED_CAST")
    fun gatherRequirements(): List<Requirement<E>> {
        return buildList(capacity = columns.size + toOneRelations.size) {
            addAll(columns.map { column ->
                Requirement.Column(column)
            })
            addAll(toOneRelations.map { (toOne, child) ->
                toOne as ToOneField<E, Any>
                child as GatheringDummyContext<Any>
                Requirement.Relation(toOne, child.gatherRequirements())
            })
        }
    }

    override fun <T> get(column: ColumnField<E, T, *>): T? {
        columns.add(column)
        return null
    }

    override fun <R> getOrNull(toOne: ToOneField<E, R>): FieldAccessControl.Context<R & Any> {
        val childContext = GatheringDummyContext<R & Any>()
        toOneRelations[toOne] = childContext
        return childContext
    }

    override fun <C : E> downcast(subtype: SubtypeField<E, C, C>): FieldAccessControl.Context<C> {
        @Suppress("UNCHECKED_CAST")
        return this as FieldAccessControl.Context<C>
    }
}

internal class NullContext<E : Any> : FieldAccessControl.Context<E> {
    override fun <T> get(column: ColumnField<E, T, *>): T? = null
    override fun <R> getOrNull(toOne: ToOneField<E, R>) = null
    override fun <C : E> downcast(subtype: SubtypeField<E, C, C>) = NullContext<C>()
}
