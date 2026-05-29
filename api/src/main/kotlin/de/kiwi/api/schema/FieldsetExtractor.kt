package de.kiwi.api.schema

import de.kiwi.api.domain.UserDescriptor

internal class EntityFieldsetExtractor<E : Any, N>(
    private val fieldset: EntityFieldset<E>,
    private val serializer: FieldsetSerializer<N>,
) {
    private val fieldsetCapacity = fieldset.columns.size +
            fieldset.toOne.size +
            fieldset.subtypes.size +
            fieldset.toMany.size +
            fieldset.toManyMap.size

    fun extract(entity: E, user: UserDescriptor? = null): N {
        val context = EntityFieldAccessControlContext(entity)

        return serializer.serializeObject(fieldsetCapacity) {
            extractColumns(entity, context, user)
            extractSubtypes(entity, context, user)
            extractToOne(entity, context, user)
            extractToMany(entity, context, user)
            extractToManyMap(entity, context, user)
        }
    }

    private fun FieldsetSerializer.ObjectContext.extractColumns(
        entity: E,
        context: FieldAccessControl.Context<E>,
        user: UserDescriptor?,
    ) {
        fieldset.columns.forEach { column ->
            if (column.checkFieldAccess(context, user)) {
                set(column.fieldName, column.readFromEntity(entity))
            }
        }
    }

    private fun FieldsetSerializer.ObjectContext.extractSubtypes(
        entity: E,
        context: EntityFieldAccessControlContext<E>,
        user: UserDescriptor?,
    ) {
        @Suppress("UNCHECKED_CAST")
        fieldset.subtypes.forEach { (subtype, subFieldset) ->
            subFieldset as Fieldset<Any>

            if (subtype.checkFieldAccess(context, user)) {
                val subtypeEntity = subtype.tryCast(entity)
                val value = subtypeEntity?.let { subFieldset.extract(it, user) }
                set(subtype.fieldName, value)
            }
        }
    }

    private fun FieldsetSerializer.ObjectContext.extractToOne(
        entity: E,
        context: FieldAccessControl.Context<E>,
        user: UserDescriptor?,
    ) {
        @Suppress("UNCHECKED_CAST")
        fieldset.toOne.forEach { (toOne, refFieldset) ->
            refFieldset as Fieldset<Any>

            if (toOne.checkFieldAccess(context, user)) {
                val target = toOne.attribute.get(entity)
                val value = target?.let { refFieldset.extract(it, user) }
                set(toOne.fieldName, value)
            }
        }
    }

    private fun FieldsetSerializer.ObjectContext.extractToMany(
        entity: E,
        context: FieldAccessControl.Context<E>,
        user: UserDescriptor?,
    ) {
        @Suppress("UNCHECKED_CAST")
        fieldset.toMany.forEach { (toMany, refFieldset) ->
            refFieldset as Fieldset<Any>

            if (toMany.checkFieldAccess(context, user)) {
                val targets = toMany.attribute.get(entity)
                val values = targets.let { items ->
                    val capacity = items.size
                    serializer.serializeList(capacity = capacity) {
                        items.forEach { item: Any? ->
                            val value = item?.let { refFieldset.extract(it, user) }
                            add(value)
                        }
                    }
                }
                set(toMany.fieldName, values)
            }
        }
    }

    private fun FieldsetSerializer.ObjectContext.extractToManyMap(
        entity: E,
        context: FieldAccessControl.Context<E>,
        user: UserDescriptor?,
    ) {
        @Suppress("UNCHECKED_CAST")
        fieldset.toManyMap.forEach { (toManyMap, refFieldsets) ->
            val (keyFieldset, valueFieldset) = refFieldsets
            keyFieldset as Fieldset<Any>
            valueFieldset as Fieldset<Any>

            if (toManyMap.checkFieldAccess(context, user)) {
                val targets = toManyMap.attribute.get(entity)
                val capacity = targets.size
                val values = serializer.serializeList(capacity = capacity) {
                    targets.forEach { (keyItem, valueItem) ->
                        val key = keyItem?.let { keyFieldset.extract(it, user) }
                        val value = valueItem?.let { valueFieldset.extract(it, user) }
                        val pair = serializer.serializePair(key, value)
                        add(pair)
                    }
                }
                set(toManyMap.fieldName, values)
            }
        }
    }

    private fun EntitySchemaField<E>.checkFieldAccess(
        context: FieldAccessControl.Context<E>,
        user: UserDescriptor?,
    ): Boolean {
        // Context to field is allowed if either no access-control check has been provided,
        // or if the provided access-control check allows access for the given context
        val fieldAccessControl = fieldset.schema.fieldAccessControl(this)
        return fieldAccessControl == null || user
            ?.let { fieldAccessControl.checkAccess(context, it) }
                ?: fieldAccessControl.checkAccess(context)
    }
}

internal class EntityFieldAccessControlContext<E : Any>(
    private val entity: E,
) : FieldAccessControl.Context<E> {

    override fun <T> get(column: ColumnField<E, T, *>): T? {
        return column.selector.readValue(entity)
    }

    override fun <R> getOrNull(toOne: ToOneField<E, R>): FieldAccessControl.Context<R & Any>? {
        val relation = toOne.attribute.get(entity)
        return relation?.let { EntityFieldAccessControlContext(it) }
    }

    override fun <C : E> downcast(subtype: SubtypeField<E, C, C>): FieldAccessControl.Context<C> {
        return subtype.tryCast(entity)
            ?.let { EntityFieldAccessControlContext(it) }
            ?: NullContext()
    }
}
