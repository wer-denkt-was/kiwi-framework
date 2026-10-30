package de.kiwi.framework.web

import de.kiwi.api.domain.Auditable
import de.kiwi.api.domain.DomainException
import de.kiwi.api.generic.GenericEntityFilter
import de.kiwi.api.schema.ColumnFilterConversion
import de.kiwi.api.schema.ColumnFilterOperator
import de.kiwi.api.schema.EntitySchema
import de.kiwi.api.schema.ColumnField
import org.springframework.util.MultiValueMap
import kotlin.reflect.full.memberProperties

// Use "name:operator=value" for filter parameters
internal const val COLUMN_OPERATOR_SEPARATOR = ':'

/**
 * Helper class for applying dynamic default column filters based on request parameters
 * to a [GenericEntityFilter].
 */
class DynamicColumnFilter(
    private val columnFilterConversion: ColumnFilterConversion,
) {
    fun <E : Any> addColumnFilters(
        schema: EntitySchema<E>,
        filter: GenericEntityFilter<E>,
        params: MultiValueMap<String, String>,
    ) {
        // Parse column filters from params
        val filterDescriptors = params.map { (paramName, values) ->
            parseFilterDescriptor(paramName, values)
        }

        // Columns can not be filtered implicitly if an explicit EntityFilter property with the same name already exists
        val entityFilterProperties = filter::class.memberProperties.map { it.name }.toSet()
        val entitySchemaColumns = schema.allColumnsIncludingSubtypes()
        val filterableColumns = entitySchemaColumns.filter { it.fieldName !in entityFilterProperties }

        filterableColumns.forEach { column ->
            filterDescriptors
                .filter { descriptor -> descriptor.fieldName.equals(column.fieldName, ignoreCase = true) }
                .forEach { descriptor ->
                    descriptor.lookupFilterOperator(column)?.let { operator ->
                        filter.setColumnFilter(
                            values = descriptor.values,
                            column = column,
                            operator = operator,
                            conversion = columnFilterConversion,
                        )
                    }
                }
        }
    }

    private fun ColumnFilterDescriptor.lookupFilterOperator(column: ColumnField<*, *, *>): ColumnFilterOperator? {
        val filters = column.type.filterSet
        return operator
            ?.let { filters[it] ?: throw UnknownColumnFilterOperator(column, it) }
            ?: filters.defaultOperator
    }

    data class UnknownColumnFilterOperator(
        val column: ColumnField<*, *, *>,
        val operator: String,
    ) : DomainException(
        status = Status.BadRequest,
        code = "unknown-column-filter-operator",
        args = mapOf("columnName" to column.fieldName, "operator" to operator)
    ) {
        override val severity = Auditable.Severity.Disabled
    }
}

// Parse "foo:bar" as (fieldName="foo", operator="bar") or "foo" as (fieldName="foo", operator=null)
private fun parseFilterDescriptor(paramName: String, values: List<String>): ColumnFilterDescriptor {
    val separatorIndex = paramName.lastIndexOf(COLUMN_OPERATOR_SEPARATOR)
    return if (separatorIndex >= 0) {
        ColumnFilterDescriptor(
            fieldName = paramName.take(separatorIndex),
            operator = paramName.substring(separatorIndex + 1),
            values = values
        )
    } else {
        ColumnFilterDescriptor(
            fieldName = paramName,
            operator = null,
            values = values,
        )
    }
}

private data class ColumnFilterDescriptor(
    val fieldName: String,
    val operator: String?,
    val values: List<String>,
)
