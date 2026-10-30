package de.kiwi.api.application.generic

import tools.jackson.databind.JsonNode
import de.kiwi.api.application.query.QueryHandler
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.generic.GenericEntityQuery
import de.kiwi.api.generic.PageResult
import de.kiwi.api.schema.QuerySchemaService
import org.springframework.data.jpa.domain.Specification

/**
 * Query handler for [GenericEntityQueries][GenericEntityQuery],
 * using a [QuerySchemaService] to generate and execute queries.
 */
class GenericEntityQueryHandler(
    private val querySchemaService: QuerySchemaService,
) : QueryHandler<GenericEntityQuery<*>, PageResult<JsonNode>> {

    override fun handle(query: GenericEntityQuery<*>, user: UserDescriptor): PageResult<JsonNode> {
        @Suppress("UNCHECKED_CAST")
        return handleTyped(query as GenericEntityQuery<Any>, user)
    }

    private fun <E : Any> handleTyped(query: GenericEntityQuery<E>, user: UserDescriptor): PageResult<JsonNode> {
        val byId = query.id?.let { query.filterById(it) } ?: Specification.unrestricted()
        val byFilter = query.filter ?: Specification.unrestricted()
        val byAccess = query.accessControlFilter(user) ?: Specification.unrestricted()
        val byCustom = query.customFilter(user) ?: Specification.unrestricted()

        val specification = Specification.allOf(byId, byFilter, byAccess, byCustom)

        val page = querySchemaService.findPage(
            fieldset = query.fieldset,
            specification = specification,
            pagination = query.pagination,
            totalLimit = query.totalLimit,
            user = user,
        )

        return PageResult.fromPage(page)
    }
}
