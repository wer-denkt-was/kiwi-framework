package de.kiwi.framework.infrastructure.schema

import de.kiwi.api.domain.Auditable
import de.kiwi.api.domain.DomainException
import de.kiwi.api.schema.EntitySchema
import de.kiwi.api.schema.SingleValueSchema
import de.kiwi.api.schema.ToOneField
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.Expression
import jakarta.persistence.criteria.Path
import jakarta.persistence.criteria.Root
import org.springframework.data.domain.Sort
import jakarta.persistence.criteria.Order as JpaOrder

private const val PROPERTY_SEPARATOR = '.'

internal class JpaQuerySortBuilder<E : Any>(
    private val root: Root<E>,
    private val schema: EntitySchema<E>,
    private val builder: CriteriaBuilder,
) {
    fun createOrderBy(sort: Sort): List<JpaOrder> {
        return if (sort.isUnsorted) emptyList()
        else sort.mapNotNull { toJpaOrder(it) }
    }

    private fun toJpaOrder(order: Sort.Order): JpaOrder? {
        val property = order.property
        val properties = parseProperties(order.property) ?: throw InvalidQuerySort(property)

        @Suppress("UNCHECKED_CAST")
        root as Path<Any>
        @Suppress("UNCHECKED_CAST")
        schema as EntitySchema<Any>
        val expression = parseExpression(root, schema, properties) ?: throw InvalidQuerySort(property)

        return when (order.direction) {
            Sort.Direction.ASC -> builder.asc(expression)
            Sort.Direction.DESC -> builder.desc(expression)
        }
    }

    private fun parseProperties(dotSeparatedProperties: String): List<String>? {
        return dotSeparatedProperties
            .split(PROPERTY_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .takeIf { it.isNotEmpty() }
    }

    private fun parseExpression(from: Path<Any>, schema: EntitySchema<Any>, properties: List<String>): Expression<*>? {
        val property = properties.firstOrNull() ?: return from
        val remaining = properties.drop(1)

        // Find column for property
        schema.columns.find { it.fieldName.equals(property, ignoreCase = true) }?.let { column ->
            val selection = column.selectFrom(from, builder)
            return selection as? Expression<*> ?: throw InvalidQuerySort(property)

        }

        // Find subtype for property
        schema.subtypeReferences.find { it.fieldName.equals(property, ignoreCase = true) }?.let { subtype ->
            @Suppress("UNCHECKED_CAST") val fromSubtype = subtype.treatFrom(from, builder) as Path<Any>
            return when (val subtypeSchema = subtype.subtypeSchema) {
                is SingleValueSchema<*, *> -> fromSubtype
                is EntitySchema<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    subtypeSchema as EntitySchema<Any>
                    parseExpression(fromSubtype, subtypeSchema, remaining)
                }
            }
        }

        // Find to-one relation for property
        schema.toOneReferences.find { it.fieldName.equals(property, ignoreCase = true) }?.let { toOne ->
            @Suppress("UNCHECKED_CAST")
            toOne as ToOneField<Any, Any>
            val targetJoin = toOne.joinFrom(from)
            return when (val targetSchema = toOne.toOne) {
                is SingleValueSchema<*, *> -> targetJoin
                is EntitySchema<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    targetSchema as EntitySchema<Any>
                    parseExpression(targetJoin, targetSchema, remaining)
                }
            }
        }

        // Property not found
        return null
    }
}

internal data class InvalidQuerySort(val expression: String) : DomainException(
    status = Status.BadRequest,
    code = "invalid-query-sort",
    args = mapOf("expression" to expression)
) {
    override val severity = Auditable.Severity.Disabled
}
