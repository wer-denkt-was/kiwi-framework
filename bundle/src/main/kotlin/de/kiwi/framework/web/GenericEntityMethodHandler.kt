package de.kiwi.framework.web

import com.fasterxml.jackson.databind.JsonNode
import de.kiwi.api.application.service.CommandQueryBus
import de.kiwi.api.application.service.EntityTypeProvider
import de.kiwi.api.application.service.QuerySchemaProvider
import de.kiwi.api.application.service.UserDescriptorProvider
import de.kiwi.api.domain.EntityId
import de.kiwi.api.generic.GenericEntityFilter
import de.kiwi.api.schema.*
import de.kiwi.framework.application.generic.GenericHandlerProvider
import de.kiwi.framework.application.generic.GenericMetadataProvider
import de.kiwi.framework.support.Accelerator
import de.kiwi.framework.web.KiwiRouteHelper.Companion.ACCELERATE_PARAM
import de.kiwi.framework.web.KiwiRouteHelper.Companion.FIELDSET_DEFAULT_VALUE
import de.kiwi.framework.web.KiwiRouteHelper.Companion.FIELDSET_PARAM
import de.kiwi.framework.web.KiwiRouteHelper.Companion.FIELDS_PARAM
import de.kiwi.framework.web.KiwiRouteHelper.Companion.ID_VARIABLE
import org.springframework.core.convert.ConversionService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.bind.WebDataBinder
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.body
import org.springframework.web.servlet.function.paramOrNull

@Component
class GenericEntityMethodHandler(
    private val commandQueryBus: CommandQueryBus,
    private val entityTypeProvider: EntityTypeProvider,
    private val querySchemaProvider: QuerySchemaProvider,
    private val genericHandlerProvider: GenericHandlerProvider,
    private val genericMetadataProvider: GenericMetadataProvider,
    private val userDescriptorProvider: UserDescriptorProvider,
    private val paginationResolver: PaginationResolver,
    private val fieldsetValidationService: FieldsetValidationService,
    private val conversionService: ConversionService,
) {
    private val fieldsetParser = DynamicFieldsetParser(querySchemaProvider)
    private val columnFilterConversion = StandardColumnFilterConversion(conversionService, entityTypeProvider)
    private val columnFilter = DynamicColumnFilter(columnFilterConversion)

    fun handleList(request: ServerRequest, entityClass: Class<*>): ServerResponse {
        val userDescriptor = userDescriptorProvider.currentUser()

        val fieldsetName = request.paramOrNull(FIELDSET_PARAM) ?: FIELDSET_DEFAULT_VALUE
        val fieldNames = request.paramOrNull(FIELDS_PARAM)
        val accelerate = request.paramOrNull(ACCELERATE_PARAM).toBoolean()
        val pagination = paginationResolver.resolvePagination(request)

        val (schema, fieldset) = resolveEntitySchemaAndFieldset(entityClass, fieldsetName, fieldNames)
        val entityFilter = querySchemaProvider.findFilterType(schema.entityClass)
            ?.let { entityFilterType -> request.bind(entityFilterType, ::initBinder) }
            ?: implicitEntityFilter()

        columnFilter.addColumnFilters(schema, entityFilter, request.params())

        val query = genericHandlerProvider.entityQuery(schema.entityClass)
        query.filter = entityFilter
        query.fieldset = fieldset
        query.pagination = pagination

        val page = commandQueryBus.resolve(query, userDescriptor)

        val responseBody: Any = if (accelerate) Accelerator.acceleratePage(page) else page
        return ServerResponse.ok().body(responseBody)
    }

    private fun initBinder(binder: WebDataBinder) {
        binder.conversionService = conversionService
    }

    fun handleFindById(request: ServerRequest, entityClass: Class<*>): ServerResponse {
        val userDescriptor = userDescriptorProvider.currentUser()

        val idValue = request.pathVariable(ID_VARIABLE) // See FrameworkRouterFunctionConfig
        val fieldsetName = request.paramOrNull(FIELDSET_PARAM) ?: FIELDSET_DEFAULT_VALUE
        val fieldNames = request.paramOrNull(FIELDS_PARAM)
        val accelerate = request.paramOrNull(ACCELERATE_PARAM).toBoolean()

        val (schema, fieldset) = resolveEntitySchemaAndFieldset(entityClass, fieldsetName, fieldNames)

        val query = genericHandlerProvider.entityQuery(schema.entityClass)
        query.id = EntityId.fromStringAnonymous(idValue)
        query.fieldset = fieldset

        val page = commandQueryBus.resolve(query, userDescriptor)
        val entity = page.firstOrNull() ?: return ServerResponse.notFound().build()
        val responseBody: Any = if (accelerate) Accelerator.accelerate(entity) else entity
        return ServerResponse.ok().body(responseBody)
    }

    fun handleCreate(request: ServerRequest, entityClass: Class<*>): ServerResponse {
        val userDescriptor = userDescriptorProvider.currentUser()

        val requestBody = request.body<JsonNode>()
        val command = genericHandlerProvider.createCommand(entityClass, requestBody)
        val (responseBody, _) = commandQueryBus.submit(command, userDescriptor)

        return ServerResponse.status(HttpStatus.CREATED).body(responseBody ?: Unit)
    }

    fun handleUpdate(request: ServerRequest, entityClass: Class<*>): ServerResponse {
        val userDescriptor = userDescriptorProvider.currentUser()

        val idValue = request.pathVariable(ID_VARIABLE) // See FrameworkRouterFunctionConfig
        val entityId = EntityId.fromStringAnonymous(idValue)
        val requestBody = request.body<JsonNode>()
        val command = genericHandlerProvider.updateCommand(entityClass, entityId, requestBody)
        val (responseBody, _) = commandQueryBus.submit(command, userDescriptor)

        return ServerResponse.ok().body(responseBody ?: Unit)
    }

    fun handleDelete(request: ServerRequest, entityClass: Class<*>): ServerResponse {
        val userDescriptor = userDescriptorProvider.currentUser()

        val idValue = request.pathVariable(ID_VARIABLE) // See FrameworkRouterFunctionConfig
        val entityId = EntityId.fromStringAnonymous(idValue)
        val command = genericHandlerProvider.deleteCommand(entityClass, entityId)
        val (responseBody, _) = commandQueryBus.submit(command, userDescriptor)

        return ServerResponse.ok().body(responseBody ?: Unit)
    }

    fun handleEntitySchema(request: ServerRequest, entityClass: Class<*>): ServerResponse {
        val metadata = genericMetadataProvider.genericMetadata(entityClass) ?: return ServerResponse.notFound().build()
        return ServerResponse.ok().body(metadata)
    }

    // --- Internal functions ---

    private data class EntitySchemaAndFieldset(
        val schema: EntitySchema<Any>,
        val fieldset: Fieldset<Any>,
    )

    private fun resolveEntitySchemaAndFieldset(
        entityClass: Class<*>,
        fieldsetName: String,
        fieldNames: String?,
    ): EntitySchemaAndFieldset {
        // Resolve EntityType by typeName lookup
        val entityType = entityTypeProvider.findEntityType(entityClass)?.javaType
            ?: throw IllegalArgumentException("Unknown entity type: ${entityClass.name}")

        // Resolve EntitySchema by EntityType
        val entityTypeName = entityType.simpleName
        val entitySchema = querySchemaProvider.findEntitySchema(entityType)
            ?: throw IllegalArgumentException("Missing EntitySchema<$entityTypeName> definition")

        @Suppress("UNCHECKED_CAST")
        entitySchema as EntitySchema<Any>

        // Resolve FieldsetsAvailable by EntityType, or fall back to implicit FieldsetsAvailable
        val fieldsets = querySchemaProvider.findAvailableFieldsets(entityType)
            ?: implicitFieldsetsAvailable(entitySchema)

        // Resolve Fieldset by parsing fieldNames or by fieldsetName lookup
        val fieldset = fieldNames
            ?.takeIf { it.isNotEmpty() }
            ?.let { fieldsetParser.parseFieldset(fieldsets, it) }
            ?: fieldsets.require(fieldsetName)

        @Suppress("UNCHECKED_CAST")
        fieldset as Fieldset<Any>

        // Assert that fieldset is valid for querying, e.g. no recursive fieldsets
        fieldsetValidationService.assertFieldsetValid(fieldset)

        return EntitySchemaAndFieldset(entitySchema, fieldset)
    }

    private fun <E : Any> implicitFieldsetsAvailable(schema: EntitySchema<E>): FieldsetsAvailable<E> {
        // If no FieldsetsAvailable object has been explicitly defined,
        // generate an implicit aggregate of fieldsets, with only the implicit default fieldset defined
        return object : FieldsetsAvailable<E>(schema) {
            override val default: Fieldset<E> = schema.implicitDefaultFieldset()
        }
    }

    private fun <E : Any> implicitEntityFilter(): GenericEntityFilter<E> {
        // If no EntityFilter class has been defined explicitly,
        // generate an implicit EntityFilter subclass without any filter properties
        return object : GenericEntityFilter<E>() { /* No filter properties */ }
    }
}
