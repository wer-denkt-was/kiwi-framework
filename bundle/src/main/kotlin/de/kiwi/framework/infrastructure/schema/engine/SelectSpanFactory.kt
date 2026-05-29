package de.kiwi.framework.infrastructure.schema.engine

import de.kiwi.api.schema.*
import jakarta.persistence.EntityManager
import jakarta.persistence.criteria.Path
import jakarta.persistence.criteria.Selection

internal class SelectSpanFactory(
    private val entityManager: EntityManager,
) {
    private val builder = entityManager.criteriaBuilder

    fun <E : Any> fieldsetSelectSpan(
        from: Path<E>,
        fieldset: Fieldset<E>,
        expressions: ExpressionContext,
    ): SelectSpan = when (fieldset) {
        is EntityFieldset<E> -> entitySelectSpan(from, fieldset, expressions)
        is SingleColumnFieldset<E, *, *> -> singleColumnSpan(from, fieldset.singleColumn, expressions)
        is SingleValueFieldset<E, *> -> singleValueSpan(from, fieldset.schema)
    }

    private fun <E : Any> entitySelectSpan(
        from: Path<E>,
        entityFieldset: EntityFieldset<E>,
        expressions: ExpressionContext,
    ): SelectSpan {
        val entitySchema = entityFieldset.schema

        // Select columns from entity fieldset, omitting non-selectable columns
        val columnSpans = entityFieldset.columns.associate { column ->
            column.fieldName to singleColumnSpan(from, column, expressions)
                .withFieldAccessControl(entitySchema.fieldAccessControl(column), from, expressions)
        }

        // Select subtype-fields from entity fieldset
        @Suppress("UNCHECKED_CAST")
        val subtypeSpans = entityFieldset.subtypes.entries
            .associate { (subtype, subtypeFieldset) ->
                // subtype <C : E> must have to same generic type C as Fieldset<C>, here in-place as "Nothing"
                // Note: "Nothing" inherits from every type, and is used as a universal subtype-class here
                val fromSubtype = subtype.treatFrom(from, builder) as Path<Nothing>
                subtypeFieldset as Fieldset<Nothing>
                subtype.fieldName to subtypeColumnSpan(subtype, fromSubtype, subtypeFieldset, expressions)
                    .withFieldAccessControl(entitySchema.fieldAccessControl(subtype), from, expressions)
            }

        // Select nested to-one relation columns from entity fieldset
        @Suppress("UNCHECKED_CAST")
        val nestedSpans = entityFieldset.toOne.entries.associate { (toOne, refFieldset) ->
            // toOne must relate as QueryToOne<E, R> to Fieldset<R>, here in-place as "Any"
            // Note: Everything inherits from "Any", and it is used as a universal referenced type here
            toOne as ToOneField<E, Any>
            refFieldset as Fieldset<Any>
            toOne.fieldName to toOneRelationSpan(from, toOne, refFieldset, expressions)
                .withFieldAccessControl(entitySchema.fieldAccessControl(toOne), from, expressions)
        }

        return SelectSpan.Nested(columnSpans + subtypeSpans + nestedSpans)
    }

    private fun <E : Any> singleValueSpan(
        value: Path<E>,
        schema: SingleValueSchema<E, *>,
    ): SelectSpan.Single<*> = SelectSpan.Single(
        isPrimary = false,
        isNullable = false,
        singleSelection = value,
        columnType = schema,
    )

    private fun <E : Any, T> singleColumnSpan(
        from: Path<E>,
        column: ColumnField<E, T, *>,
        expressions: ExpressionContext,
    ): SelectSpan.Single<T> {
        val columnSelection = column.selectFrom(from, builder)

        // Register column-selection in ExpressionContext for FieldAccessControl-evaluation
        expressions[column] = columnSelection

        return SelectSpan.Single(
            isPrimary = column.primaryKey,
            isNullable = column.nullable,
            singleSelection = columnSelection,
            columnType = column.type,
        )
    }

    private fun <E : Any, C : E> subtypeColumnSpan(
        subtype: SubtypeField<E, *, *>,
        fromSubtype: Path<C>,
        subtypeFieldset: Fieldset<C>,
        expressions: ExpressionContext,
    ): SelectSpan.ForSubtype {
        val subtypeExpressions = expressions.subtype(subtype)
        val nestedSelectSpan = fieldsetSelectSpan(fromSubtype, subtypeFieldset, subtypeExpressions)
        return SelectSpan.ForSubtype(nestedSelectSpan)
    }

    private fun <E : Any, R : Any> toOneRelationSpan(
        fromRoot: Path<E>,
        toOneRelation: ToOneField<E, R>,
        referencedFieldset: Fieldset<R>,
        expressions: ExpressionContext,
    ): SelectSpan {
        // Join from root to relation
        val fromParent = toOneRelation.joinFrom(fromRoot)

        // Create sub-context for expressions within relation
        val toOneExpressions = expressions.nested(toOneRelation)

        // If to-one relation contains nested to-many queries, create a new root node for this relation
        if (referencedFieldset is EntityFieldset<R> && referencedFieldset.hasAnyToManyRelations) {
            return toOneWithChildQueriesSpan(fromParent, referencedFieldset, toOneExpressions)
        }

        return fieldsetSelectSpan(fromParent, referencedFieldset, toOneExpressions)
    }

    private val EntityFieldset<*>.hasAnyToManyRelations
        get() = toMany.isNotEmpty() || toManyMap.isNotEmpty()

    private fun <R : Any> toOneWithChildQueriesSpan(
        originToOne: Path<R>,
        referencedFieldset: EntityFieldset<R>,
        toOneExpressions: ExpressionContext,
    ): SelectSpan.WithChildQueries {
        val baseSpan = entitySelectSpan(originToOne, referencedFieldset, toOneExpressions)
        val primaryKeyColumn = referencedFieldset.schema.primaryKeys.singleOrNull()
            ?: throw IllegalStateException("Target entity of to-one relation requires a single primary key")

        val selectPrimaryKey = primaryKeyColumn.selectFrom(originToOne, builder)

        val subRootPath = QueryJoinPath.RootPath<R>()
        val subNodeFactory = QueryNodeFactory(referencedFieldset, entityManager, this)

        val childQueries = subNodeFactory.createChildQueries(
            parentSourcePath = subRootPath,
            parentFieldset = referencedFieldset,
        )
        val additionalSelections = additionalToManyFieldsAccessControlSelections(
            from = originToOne,
            fieldset = referencedFieldset,
            expressions = toOneExpressions,
        )

        return SelectSpan.WithChildQueries(
            baseSpan = baseSpan,
            parentPrimaryKey = selectPrimaryKey,
            additionalSelections = additionalSelections,
            childQueries = childQueries,
            expressions = toOneExpressions
        )
    }

    // Wrap SelectSpan into a SelectSpan.WithFieldAccessControl if requested by a FieldAccessControl instance
    private fun <E : Any> SelectSpan.withFieldAccessControl(
        control: FieldAccessControl<E>?,
        from: Path<E>,
        expressions: ExpressionContext,
    ): SelectSpan {
        if (control == null) {
            return this // Skip if no access-control is provided
        }

        // Add selections required to evaluate field-access control to selection
        val requiredSelections = control.requirements
            .flatMap { it.selection(from, expressions) }

        return SelectSpan.WithFieldAccessControl(
            baseSpan = this,
            control = control,
            expressions = expressions,
            requiredSelections = requiredSelections,
        )
    }

    private fun <E : Any> FieldAccessControl.Requirement<E>.selection(
        from: Path<E>,
        expressions: ExpressionContext,
    ): List<Selection<*>> {
        return when (this) {
            is FieldAccessControl.Requirement.Column<E> -> listOf(columnSelection(from, expressions))
            is FieldAccessControl.Requirement.Relation<E, *> -> nestedSelections(from, expressions)
        }
    }

    private fun <E : Any> FieldAccessControl.Requirement.Column<E>.columnSelection(
        from: Path<E>,
        expressions: ExpressionContext,
    ): Selection<*> {
        @Suppress("UNCHECKED_CAST") val invariantColumn = column as ColumnField<E, Any, *>
        val expression = invariantColumn.selectFrom(from, builder)
        expressions[invariantColumn] = expression
        return expression
    }

    private fun <E : Any, R> FieldAccessControl.Requirement.Relation<E, R>.nestedSelections(
        from: Path<E>,
        expressions: ExpressionContext,
    ): List<Selection<*>> {
        val nestedExpressions = expressions.nested(toOne)
        @Suppress("UNCHECKED_CAST") val join = toOne.joinFrom(from) as Path<R & Any>
        return nested.flatMap { it.selection(join, nestedExpressions) }
    }

    fun <E : Any> additionalToManyFieldsAccessControlSelections(
        from: Path<E>,
        fieldset: Fieldset<E>,
        expressions: ExpressionContext,
    ): List<Selection<*>> {
        if (fieldset !is EntityFieldset<E>) {
            return emptyList()
        }

        val schema = fieldset.schema
        val toManyControls = fieldset.toMany.mapNotNull { (toMany, _) -> schema.fieldAccessControl(toMany) }
        val toManyMapControls = fieldset.toManyMap.mapNotNull { (toManyMap, _) -> schema.fieldAccessControl(toManyMap) }
        val toManyRequirements = (toManyControls + toManyMapControls).flatMap { it.requirements }
        return toManyRequirements.flatMap { requirement -> requirement.selection(from, expressions) }
    }
}
