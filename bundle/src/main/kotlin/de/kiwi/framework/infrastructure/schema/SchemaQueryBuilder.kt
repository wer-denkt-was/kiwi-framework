package de.kiwi.framework.infrastructure.schema

import tools.jackson.databind.JsonNode
import de.kiwi.api.application.service.QueryBuilder
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.schema.EntityFieldset
import de.kiwi.api.schema.QuerySchemaService
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification

class SchemaQueryBuilder(private val schemaService: QuerySchemaService) : QueryBuilder {

    override fun <E : Any> select(fieldset: EntityFieldset<E>): QueryBuilder.Select<E> {
        return SelectBuilder(fieldset)
    }

    inner class SelectBuilder<E : Any>(private val fieldset: EntityFieldset<E>) : QueryBuilder.Select<E> {
        private val specifications: MutableList<Specification<E>> = ArrayList()
        private var sort: Sort = Sort.unsorted()

        override fun where(specification: Specification<E>): QueryBuilder.Select<E> {
            specifications.add(specification)
            return this
        }

        override fun sort(sort: Sort): QueryBuilder.Select<E> {
            this.sort = sort
            return this
        }

        override fun page(pagination: Pageable, user: UserDescriptor?): Page<JsonNode> {
            val specification = Specification.allOf(specifications)
            return schemaService.findPage(fieldset, specification, pagination, sort, user = user)
        }

        override fun all(user: UserDescriptor?): List<JsonNode> {
            val specification = Specification.allOf(specifications)
            return schemaService.findAll(fieldset, specification, sort, user = user)
        }

        override fun single(user: UserDescriptor?): JsonNode? {
            val specification = Specification.allOf(specifications)
            return schemaService.findOne(fieldset, specification, user = user)
        }
    }
}
