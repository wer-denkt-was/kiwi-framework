package de.kiwi.framework.application.olap

import de.kiwi.api.application.query.QueryHandler
import de.kiwi.api.application.service.EntityTypeProvider
import de.kiwi.api.domain.EntityId
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.olap.*
import de.kiwi.api.util.CriteriaDsl
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.Tuple
import jakarta.persistence.criteria.*
import org.springframework.core.convert.ConversionService
import org.springframework.data.domain.Sort
import java.time.format.DateTimeFormatter

/**
 * Handler for [AnalyticalReportQueries][AnalyticalReportQuery],
 * querying and aggregating data using an [EntityManager] to create [AnalyticalReports][AnalyticalReport].
 */
class AnalyticalReportQueryHandler(
    private val conversionService: ConversionService,
    private val entityTypeProvider: EntityTypeProvider,
) : QueryHandler<AnalyticalReportQuery<*>, AnalyticalReport> {

    @PersistenceContext
    lateinit var entityManager: EntityManager

    override fun handle(query: AnalyticalReportQuery<*>, user: UserDescriptor): AnalyticalReport {
        @Suppress("UNCHECKED_CAST")
        return handleTyped(query as AnalyticalReportQuery<Any>, user)
    }

    private fun <E : Any> handleTyped(reportQuery: AnalyticalReportQuery<E>, user: UserDescriptor): AnalyticalReport {
        // Create a tuple-query, use selection root from the report-query
        val builder: CriteriaBuilder = entityManager.criteriaBuilder
        val criteriaQuery: CriteriaQuery<Tuple> = builder.createTupleQuery()
        val root = with(reportQuery) { criteriaQuery.queryFrom() }

        // Create dimension expressions, metric expressions, and slice predicates
        val context = CriteriaDsl(builder, criteriaQuery)
        val expressions = ReportExpressions(reportQuery, context, root)

        // Shortcut if no dimensions or metrics were specified
        if (expressions.isEmpty()) {
            return AnalyticalReport(emptyList(), emptyList())
        }

        // Create access-control predicate (0- or 1-item list)
        val accessControlPredicates = reportQuery
            .accessControlFilter(user)
            ?.toPredicate(root, criteriaQuery, builder)
            ?.let { listOf(it) }
            ?: emptyList()

        // Select dimensions and metrics, group by dimensions
        criteriaQuery.multiselect(expressions.allExpressions)
        criteriaQuery.groupBy(expressions.dimensionExpressions)

        // Filter by slice- and access-control predicates
        val predicates = expressions.slicePredicates + accessControlPredicates
        criteriaQuery.where(*predicates.toTypedArray())

        // Apply sorting
        criteriaQuery.orderBy(createOrderBy(reportQuery.sort, expressions))

        // Execute tuple query
        val resultSet = entityManager.createQuery(criteriaQuery).resultList

        // Convert tuples to an analytical report
        val headers = expressions.dimensionNames + expressions.metricNames
        val body = resultSet.map { row ->
            val dimensionValues = expressions.dimensionNames.map { name -> expressions.dimensionValue(row, name) }
            val metricValues = expressions.metricNames.map { name -> expressions.metricValue(row, name) }
            val rowValues = dimensionValues + metricValues
            rowValues.map { processRowValue(it) }
        }

        return AnalyticalReport(headers, body)
    }

    private inner class ReportExpressions<E : Any>(
        private val reportQuery: AnalyticalReportQuery<E>,
        private val context: CriteriaDsl,
        private val root: Root<E>,
    ) {
        val dimensionsByName = reportQuery.dimensions.associateWith { name ->
            with(dimensionSelector(name)) { context.selectValue(root) }
        }
        val metricsByName = reportQuery.metrics.associateWith { name ->
            with(metricAggregator(name)) { context.selectValue(root) }
        }

        val allExpressionsByName = dimensionsByName + metricsByName

        val dimensionNames = dimensionsByName.keys.toList()
        val metricNames = metricsByName.keys.toList()

        val dimensionExpressions = dimensionsByName.values.toList()
        val metricExpressions = metricsByName.values.toList()
        val allExpressions = (dimensionExpressions + metricExpressions).distinct()

        val slicePredicates = reportQuery.slices.map { (name, slice) ->
            val selector = dimensionSelector(name)
            val expression = with(selector) { context.selectValue(root) }
            createSlicePredicate(slice, selector, expression)
        }

        fun isEmpty() = allExpressionsByName.isEmpty()
        fun metricValue(row: Tuple, name: String) = metricsByName[name]?.let { row[it] }
        fun dimensionValue(row: Tuple, name: String) = dimensionsByName[name]?.let { row[it] }

        private fun dimensionSelector(name: String) = reportQuery.fields.dimensionSelector(name)
            ?: throw UnknownReportField(name, UnknownReportField.FieldType.Dimension)

        private fun metricAggregator(name: String) = reportQuery.fields.metricAggregator(name)
            ?: throw UnknownReportField(name, UnknownReportField.FieldType.Metric)

        @Suppress("UNCHECKED_CAST")
        private fun createSlicePredicate(
            slice: Slice,
            selector: TypedSelector<E, *>,
            expression: Expression<*>,
        ): Predicate {
            // Upcast selector/expression type to Any, as type compatibility is assumed
            val valueConverter = sliceValueConverter(selector.valueType as Class<Any>)
            return with(slice) { context.filter(expression as Expression<Any>, valueConverter) }
        }
    }

    private fun <T> sliceValueConverter(valueType: Class<T>) = fun(value: Any): T? {
        return when (valueType) {
            // Same type, no conversion required
            value.javaClass -> {
                @Suppress("UNCHECKED_CAST")
                value as T
            }

            // Allow for looking up classes by name
            Class::class.java -> {
                val entityType = entityTypeProvider.findEntityType(value.toString())
                @Suppress("UNCHECKED_CAST")
                entityType?.javaType as T?
            }

            // Use conversions by default
            else -> conversionService.convert(value, valueType)
        }
    }

    private fun createOrderBy(sort: Sort, expressions: ReportExpressions<*>): List<Order> {
        // Skip for unsorted
        if (sort.isUnsorted) {
            return emptyList()
        }

        // Map sort to criteria order-by list
        val builder = entityManager.criteriaBuilder
        val expressionsByLowerName = expressions.allExpressionsByName.mapKeys { (name, _) -> name.lowercase() }
        return sort.mapNotNull { order ->
            val lowerName = order.property.lowercase()
            expressionsByLowerName[lowerName]?.let { expr ->
                when (order.direction) {
                    Sort.Direction.ASC -> builder.asc(expr)
                    Sort.Direction.DESC -> builder.desc(expr)
                }
            }
        }
    }

    private fun processRowValue(value: Any?): Any? {
        return when (value) {
            // Keep trivial values
            null -> null
            is Number -> value
            is String -> value
            is Boolean -> value
            // Convert EntityId to string
            is EntityId -> value.toString()
            // Convert java.sql time values to ISO strings
            is java.sql.Date -> value.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
            is java.sql.Time -> value.toLocalTime().format(DateTimeFormatter.ISO_LOCAL_TIME)
            is java.sql.Timestamp -> value.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            // Convert java.lang.Enum to name of enum instance
            is Enum<*> -> value.name
            // Convert java.lang.Class to name of the associated entity
            is Class<*> -> entityTypeProvider.findEntityType(value)?.name ?: value.simpleName
            // Otherwise, keep value as-is
            else -> value
        }
    }
}
