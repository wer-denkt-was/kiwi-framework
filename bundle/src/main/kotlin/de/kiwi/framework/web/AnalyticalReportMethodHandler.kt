package de.kiwi.framework.web

import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.exc.MismatchedInputException
import tools.jackson.module.kotlin.readValue
import de.kiwi.api.application.query.QueryBus
import de.kiwi.api.application.service.UserDescriptorProvider
import de.kiwi.api.domain.EntityMismatchedInput
import de.kiwi.api.olap.AnalyticalReportQuery
import de.kiwi.api.olap.Slice
import de.kiwi.api.util.splitMultiValueParameters
import de.kiwi.framework.application.olap.AnalyticalReportProvider
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

class AnalyticalReportMethodHandler(
    private val queryBus: QueryBus,
    private val objectMapper: ObjectMapper,
    private val paginationResolver: PaginationResolver,
    private val userDescriptorProvider: UserDescriptorProvider,
    private val analyticalReportProvider: AnalyticalReportProvider,
) {
    fun handleReport(request: ServerRequest, entityClass: Class<*>): ServerResponse {
        val userDescriptor = userDescriptorProvider.currentUser()

        val requestParams = request.params()
        val dimensions = requestParams.getOrDefault("_dimensions", emptyList()).toSet()
        val metrics = requestParams.getOrDefault("_metrics", emptyList()).toSet()
        val slices = LinkedMultiValueMap(requestParams)

        val sort = paginationResolver.resolvePagination(request).sort

        // Create query, set dimensions, metrics and slices
        val reportQuery = analyticalReportProvider.reportQuery(entityClass)
        reportQuery.dimensions.addAll(parseFieldNames(dimensions))
        reportQuery.metrics.addAll(parseFieldNames(metrics))
        reportQuery.slices.putAll(parseSlices(reportQuery, slices))
        reportQuery.sort = sort

        // Resolve query
        val report = queryBus.resolve(reportQuery, userDescriptor)
        return ServerResponse.ok().body(report)
    }

    fun handleReportSchema(request: ServerRequest, entityClass: Class<*>): ServerResponse {
        val schema = analyticalReportProvider.reportSchema(entityClass)
        return ServerResponse.ok().body(schema)
    }

    // --- Internal functions ---

    private fun parseFieldNames(fields: Set<String>?): Set<String> {
        return fields
            ?.splitMultiValueParameters()
            ?.toSet()
            ?: emptySet()
    }

    private fun parseSlices(
        query: AnalyticalReportQuery<*>,
        sliceParams: MultiValueMap<String, String>,
    ): Map<String, Slice> {
        try {
            return sliceParams.entries
                .filter { (name, _) -> query.fields.dimensionAvailable(name) }
                .associate { (name, sliceValues) ->
                    name to (sliceValues.singleOrNull()
                        ?.let { singleSliceValue -> objectMapper.readValue<Slice>(singleSliceValue) }
                        ?: Slice(and = sliceValues.map { objectMapper.readValue<Slice>(it) }))
                }
        } catch (ex: MismatchedInputException) {
            val mismatchedFields = ex.path.map { it.description }
            throw EntityMismatchedInput(ex.targetType, mismatchedFields)
        }
    }
}
