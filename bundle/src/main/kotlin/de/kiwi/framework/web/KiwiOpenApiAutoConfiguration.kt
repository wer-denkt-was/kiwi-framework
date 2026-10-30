package de.kiwi.framework.web

import com.fasterxml.jackson.databind.JavaType
import de.kiwi.api.application.service.QuerySchemaProvider
import de.kiwi.api.domain.Command
import de.kiwi.api.domain.EntityId
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.generic.GenericBodyCommand
import de.kiwi.api.generic.GenericEntityCommand
import de.kiwi.api.generic.GenericEntityFilter
import de.kiwi.api.generic.PageResult
import de.kiwi.api.olap.AnalyticalReport
import de.kiwi.api.olap.AnalyticalReportSchema
import de.kiwi.api.olap.Slice
import de.kiwi.api.schema.EntitySchema
import de.kiwi.api.schema.FieldsetsAvailable
import de.kiwi.framework.KiwiFrameworkProperties
import de.kiwi.framework.application.generic.GenericHandlerProvider
import de.kiwi.framework.application.generic.GenericMetadata
import de.kiwi.framework.application.olap.AnalyticalReportProvider
import de.kiwi.framework.web.KiwiOpenApiHelper.CommandClassRole
import tools.jackson.databind.ObjectMapper
import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverter
import io.swagger.v3.core.converter.ModelConverterContext
import io.swagger.v3.core.converter.ModelConverters
import io.swagger.v3.oas.models.*
import io.swagger.v3.oas.models.media.*
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import io.swagger.v3.oas.models.tags.Tag
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springdoc.core.customizers.ParameterCustomizer
import org.springdoc.core.utils.SpringDocUtils
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.core.ResolvableType
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.util.TypeUtils
import java.lang.reflect.Type
import java.math.BigDecimal
import java.util.*
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.memberProperties
import org.springframework.http.MediaType as MediaTypeEnum

/**
 * Auto-configuration documenting the Kiwi web endpoints using SpringDoc OpenAPI, if available.
 * Can be disabled using `kiwi.web.open-api.enabled=false`.
 */
@AutoConfiguration(after = [KiwiWebAutoConfiguration::class])
@ConditionalOnClass(name = ["io.swagger.v3.oas.models.OpenAPI", "org.springdoc.core.customizers.OpenApiCustomizer"])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBooleanProperty("kiwi.web.open-api.enabled", matchIfMissing = true)
@ConditionalOnBean(KiwiRouteHelper::class)
@EnableConfigurationProperties(KiwiFrameworkProperties::class)
class KiwiOpenApiAutoConfiguration(
    private val props: KiwiFrameworkProperties,
    private val routeHelper: KiwiRouteHelper,
    objectMapper: ObjectMapper,
) {
    private val modelHelper = KiwiOpenApiHelper(objectMapper)

    private val messageBundle = ResourceBundleMessageSource().apply {
        setBasename("kiwi-openapi-messages")
        setDefaultLocale(Locale.ENGLISH)
    }

    companion object {
        /** Static initializer: Add UserDescriptor to ignored request-wrapper types in SpringDoc OpenAPI */
        init {
            val staticConfig = SpringDocUtils.getConfig()
            staticConfig.addRequestWrapperToIgnore(UserDescriptor::class.java)
        }
    }

    @Bean
    @Order(20) // Ordered after analyticalReportRouter
    @ConditionalOnBean(GenericHandlerProvider::class, GenericEntityMethodHandler::class, QuerySchemaProvider::class)
    @ConditionalOnProperty("kiwi.web.generic-controller", matchIfMissing = true)
    fun genericEntityOpenApiCustomizer(
        querySchemaProvider: QuerySchemaProvider,
        genericHandlerProvider: GenericHandlerProvider,
    ) = OpenApiCustomizer { api ->
        registerEntityQueryEndpoints(api, genericHandlerProvider, querySchemaProvider)
        registerEntityCommandEndpoints(api, genericHandlerProvider, querySchemaProvider)
        registerEntitySchemaMetadataEndpoints(api, genericHandlerProvider, querySchemaProvider)
    }

    @Bean
    @Order(10) // Ordered before genericEntityRouter
    @ConditionalOnBean(AnalyticalReportProvider::class, AnalyticalReportMethodHandler::class)
    @ConditionalOnProperty("kiwi.web.report-controller", matchIfMissing = true)
    fun analyticalReportOpenApiCustomizer(
        analyticalReportProvider: AnalyticalReportProvider,
    ) = OpenApiCustomizer { api ->
        val components = api.getOrCreateComponents()
        val errorResponseRef = components.createErrorApiResponse()

        // Register response for "GET /<entity>/_report" requests
        val reportModelRef = components.readAndRegisterSchema<AnalyticalReport>("kiwi.report.")
        val reportResponseRef = components.registerApiResponse("kiwi.report.analyticalReportResponse") {
            description = message("response.report")
            content = Content()
            content.createJson { schema = refSchema(reportModelRef) }
        }

        // Register response for "GET /<entity>/_report/_schema" requests
        val reportMetadataModelRef = components.readAndRegisterSchema<AnalyticalReportSchema>("kiwi.report.meta.")
        val reportMetadataResponseRef = components.registerApiResponse("kiwi.schema.reportSchemaResponse") {
            description = message("response.report-schema")
            content = Content()
            content.createJson { schema = refSchema(reportMetadataModelRef) }
        }

        // Register schema for slice query-parameters
        val sliceModelRef = components.readAndRegisterSchema<Slice>("kiwi.report.")

        routeHelper.forEachEntityPath(
            entityClasses = analyticalReportProvider.reportEntityClasses,
            onlyCanonical = true,
        ) { entityClass, path ->
            val reportQuery = analyticalReportProvider.reportQuery(entityClass)

            // GET /Entity/_report
            api.createOperation(method = HttpMethod.GET, path = routeHelper.reportPath(path)) { op ->
                op.tags = listOf(api.entityTag(entityClass))
                op.operationId = "kiwi.report.${entityClass.simpleName}"
                op.summary = message("endpoint.report", entityClass.simpleName)

                // "_dimensions", "_metrics" report-query parameters
                op.createQueryParameter("_dimensions") {
                    required = true
                    explode = true // repeatable parameter
                    description = message("parameter.dimensions")
                    schema = ArraySchema()
                        .items(StringSchema()._enum(reportQuery.fields.dimensions.keys.toList()))
                        .minItems(1)
                }
                op.createQueryParameter("_metrics") {
                    required = true
                    explode = true // repeatable parameter
                    description = message("parameter.metrics")
                    schema = ArraySchema()
                        .items(StringSchema()._enum(reportQuery.fields.metrics.keys.toList()))
                        .minItems(1)
                }

                // Slice parameters
                reportQuery.fields.dimensions.keys.forEach { parameter ->
                    op.createQueryParameter(parameter = parameter) {
                        required = false
                        description = message("parameter.slice", parameter)
                        content = Content()
                        content.createJson { schema = refSchema(sliceModelRef) }
                    }
                }

                op.responses = ApiResponses()
                op.responses.createApiResponse(null) { `$ref` = errorResponseRef }
                op.responses.createApiResponse(HttpStatus.OK) { `$ref` = reportResponseRef }
            }

            // GET /Entity/_report/_schema
            api.createOperation(method = HttpMethod.GET, path = routeHelper.reportSchemaPath(path)) { op ->
                op.tags = listOf(api.entityTag(entityClass))
                op.operationId = "kiwi.schema.report.${entityClass.simpleName}"
                op.summary = message("endpoint.schema.report", entityClass.simpleName)

                op.responses = ApiResponses()
                op.responses.createApiResponse(HttpStatus.OK) { `$ref` = reportMetadataResponseRef }
            }
        }
    }

    @Bean
    fun kiwiFrameworkParametersCustomizer() = ParameterCustomizer { parameterModel, methodParameter ->
        parameterModel ?: return@ParameterCustomizer null
        val parameterType = methodParameter.parameterType
        when {
            // Ignore UserDescriptor parameter, resolved using UserDescriptorArgumentResolver
            UserDescriptor::class.java.isAssignableFrom(parameterType) -> null

            // EntityId parameter, adjust type description to match EntityId deserializer
            EntityId::class.java.isAssignableFrom(parameterType) -> parameterModel.also {
                parameterModel.schema = entityIdSchema()
            }

            // Default parameter
            else -> parameterModel
        }
    }

    @Bean
    fun kiwiFrameworkEntityIdModelConverter(): ModelConverter = EntityIdModelConverter()

    /** Generic entity command endpoints (create, update, delete) */
    private fun registerEntityCommandEndpoints(
        api: OpenAPI,
        genericHandlerProvider: GenericHandlerProvider,
        querySchemaProvider: QuerySchemaProvider,
    ) {
        val components = api.getOrCreateComponents()
        val errorResponseRef = components.createErrorApiResponse()

        // POST /<Entity>
        routeHelper.forEachEntityPath(
            entityClassMap = genericHandlerProvider.createClasses,
            onlyCanonical = true
        ) { entityClass, path, createClass ->
            val (requestModel, responseModel) = if (createClass.isGenericBodyCommand()) {
                val entitySchema = querySchemaProvider.findEntitySchema(entityClass)
                val entitySchemaResponseModel = modelHelper.entitySchemaModel(api, entitySchema)
                @Suppress("UNCHECKED_CAST")
                val commandRequestModel =
                    modelHelper.genericBodyCommandModel(api, createClass as Class<out GenericBodyCommand<*>>)
                commandRequestModel to entitySchemaResponseModel
            } else {
                val commandModels = modelHelper.commandClassModels(api, createClass, CommandClassRole.Create)
                commandModels.requestModel to commandModels.responseModel
            }

            // POST /Entity
            api.createOperation(method = HttpMethod.POST, path = routeHelper.basePath(path)) { op ->
                op.tags = listOf(api.entityTag(entityClass))
                op.operationId = "kiwi.command.${entityClass.simpleName}.create"
                op.summary = message("endpoint.command.create", entityClass.simpleName)

                op.requestBody = RequestBody()
                op.requestBody.required = true
                op.requestBody.description = message("endpoint.command.create.body")
                op.requestBody.content = Content()
                op.requestBody.content.createJson { schema = requestModel }

                op.responses = ApiResponses()
                op.responses.createApiResponse(null) { `$ref` = errorResponseRef }
                op.responses.createApiResponse(HttpStatus.CREATED) {
                    content = Content()
                    content.createJson { schema = responseModel }
                }
            }
        }

        // PATCH /<Entity>/{id}
        routeHelper.forEachEntityPath(
            entityClassMap = genericHandlerProvider.updateClasses,
            onlyCanonical = true
        ) { entityClass, path, updateClass ->
            val (requestModel, responseModel) = if (updateClass.isGenericBodyCommand()) {
                val entitySchema = querySchemaProvider.findEntitySchema(entityClass)
                val entitySchemaResponseModel = modelHelper.entitySchemaModel(api, entitySchema)
                @Suppress("UNCHECKED_CAST")
                val commandRequestModel =
                    modelHelper.genericBodyCommandModel(api, updateClass as Class<out GenericBodyCommand<*>>)
                commandRequestModel to entitySchemaResponseModel
            } else {
                val commandModels = modelHelper.commandClassModels(api, updateClass, CommandClassRole.Update)
                commandModels.requestModel to commandModels.responseModel
            }

            api.createOperation(method = HttpMethod.PATCH, path = routeHelper.byIdPath(path)) { op ->
                op.tags = listOf(api.entityTag(entityClass))
                op.operationId = "kiwi.command.${entityClass.simpleName}.update"
                op.summary = message("endpoint.command.update", entityClass.simpleName)

                op.createEntityIdPathVariable()

                op.requestBody = RequestBody()
                op.requestBody.required = true
                op.requestBody.description = message("endpoint.command.update.body")
                op.requestBody.content = Content()
                op.requestBody.content.createJson { schema = requestModel }

                op.responses = ApiResponses()
                op.responses.createApiResponse(null) { `$ref` = errorResponseRef }
                op.responses.createApiResponse(HttpStatus.OK) {
                    content = Content()
                    content.createJson { schema = responseModel }
                }
            }
        }

        // DELETE /Entity/{id}
        routeHelper.forEachEntityPath(
            entityClassMap = genericHandlerProvider.deleteClasses,
            onlyCanonical = true
        ) { entityClass, path, deleteClass ->
            val (_, responseSchema) = if (deleteClass.isGenericCommand()) {
                val entitySchema = querySchemaProvider.findEntitySchema(entityClass)
                val entitySchemaModel = modelHelper.entitySchemaModel(api, entitySchema)
                Unit to entitySchemaModel
            } else {
                val commandModels = modelHelper.commandClassModels(api, deleteClass, CommandClassRole.Delete)
                commandModels.requestModel to commandModels.responseModel
            }

            api.createOperation(method = HttpMethod.DELETE, path = routeHelper.byIdPath(path)) { op ->
                op.tags = listOf(api.entityTag(entityClass))
                op.operationId = "kiwi.command.${entityClass.simpleName}.delete"
                op.summary = message("endpoint.command.delete", entityClass.simpleName)

                op.createEntityIdPathVariable()

                op.responses = ApiResponses()
                op.responses.createApiResponse(null) { `$ref` = errorResponseRef }
                op.responses.createApiResponse(HttpStatus.OK) {
                    content = Content()
                    content.createJson { schema = responseSchema }
                }
            }
        }
    }

    /** Register "GET /Entity" and "GET /<Entity>/{id}" endpoints */
    private fun registerEntityQueryEndpoints(
        api: OpenAPI,
        genericHandlerProvider: GenericHandlerProvider,
        querySchemaProvider: QuerySchemaProvider,
    ) {
        val components = api.getOrCreateComponents()
        val errorResponseRef = components.createErrorApiResponse()

        routeHelper.forEachEntityPath(
            entityClasses = genericHandlerProvider.queryClasses.keys,
            onlyCanonical = true
        ) { entityClass, path ->
            val entitySchema = querySchemaProvider.findEntitySchema(entityClass)
            val entityFieldsets = querySchemaProvider.findAvailableFieldsets(entityClass)
            val entityFilter = querySchemaProvider.findFilterType(entityClass)

            val entitySchemaModel = modelHelper.entitySchemaModel(api, entitySchema)

            // GET /Entity
            api.createOperation(method = HttpMethod.GET, path = routeHelper.basePath(path)) { op ->
                op.tags = listOf(api.entityTag(entityClass))
                op.operationId = "kiwi.query.${entityClass.simpleName}.page"
                op.summary = message("endpoint.query.page", entityClass.simpleName)

                op.createPaginationQueryParameters()
                op.createFieldsetQueryParameters(entityFieldsets)
                op.createFilterQueryParameters(entitySchema, entityFilter)

                op.responses = ApiResponses()
                op.responses.createApiResponse(null) { `$ref` = errorResponseRef }
                op.responses.createApiResponse(HttpStatus.OK) {
                    content = Content()
                    content.createJson {
                        schema = pageResultSchema(entitySchemaModel)
                    }
                }
            }

            // GET /Entity/{id}
            api.createOperation(method = HttpMethod.GET, path = routeHelper.byIdPath(path)) { op ->
                op.tags = listOf(api.entityTag(entityClass))
                op.operationId = "kiwi.query.${entityClass.simpleName}.byId"
                op.summary = message("endpoint.query.by-id", entityClass.simpleName)

                op.createEntityIdPathVariable()
                op.createFieldsetQueryParameters(entityFieldsets)

                op.responses = ApiResponses()
                op.responses.createApiResponse(null) { `$ref` = errorResponseRef }
                op.responses.createApiResponse(HttpStatus.OK) {
                    content = Content()
                    content.createJson {
                        schema = entitySchemaModel
                    }
                }
                op.responses.createApiResponse(HttpStatus.NOT_FOUND) {
                    `$ref` = errorResponseRef
                    description = message("endpoint.query.by-id.not-found", entityClass.simpleName)
                }
            }
        }
    }

    /** Register "GET /<Entity>/_schema" endpoints */
    private fun registerEntitySchemaMetadataEndpoints(
        api: OpenAPI,
        genericHandlerProvider: GenericHandlerProvider,
        querySchemaProvider: QuerySchemaProvider,
    ) {
        val components = api.getOrCreateComponents()
        val genericEntitySchemaModelRef = components.readAndRegisterSchema<GenericMetadata>("kiwi.schema.meta.")
        val genericEntitySchemaResponseRef = components.registerApiResponse("kiwi.schema.entitySchemaResponse") {
            description = message("response.generic-entity-schema")
            content = Content()
            content.createJson { schema = refSchema(genericEntitySchemaModelRef) }
        }

        val schemaEntityClasses = querySchemaProvider.schemaEntityClasses.toSet()
        val commandOrQueryEntityClasses = buildSet {
            addAll(genericHandlerProvider.queryClasses.keys)
            addAll(genericHandlerProvider.createClasses.keys)
            addAll(genericHandlerProvider.updateClasses.keys)
            addAll(genericHandlerProvider.deleteClasses.keys)
        }
        val entityClasses = commandOrQueryEntityClasses intersect schemaEntityClasses

        routeHelper.forEachEntityPath(
            entityClasses = entityClasses,
            onlyCanonical = true
        ) { entityClass, path ->
            // GET /Entity/_schema
            api.createOperation(method = HttpMethod.GET, path = routeHelper.schemaPath(path)) { op ->
                op.tags = listOf(api.entityTag(entityClass))
                op.operationId = "kiwi.schema.${entityClass.simpleName}"
                op.summary = message("endpoint.schema", entityClass.simpleName)

                op.responses = ApiResponses()
                op.responses.createApiResponse(HttpStatus.OK) { `$ref` = genericEntitySchemaResponseRef }
            }
        }
    }

    private fun OpenAPI.entityTag(entityClass: Class<*>): String {
        val tagName = "kiwi.${entityClass.simpleName}"
        val tags = tags ?: mutableListOf<Tag>().also { tags = it }
        val tag = tags.find { it.name == tagName } ?: Tag().also { tag ->
            tag.name = tagName
            tag.description = message("tag.entity", entityClass.simpleName)
            tags.add(tag)
        }
        return tag.name
    }

    private fun Components.createErrorApiResponse(): String {
        val errorBodyModelRef = readAndRegisterSchema<ErrorResponse.ErrorBody>("kiwi.error.")
        return registerApiResponse("kiwi.error") {
            description = message("response.error")
            content = Content()
            content.createJson { schema = refSchema(errorBodyModelRef) }
        }
    }

    private fun Operation.createPaginationQueryParameters() {
        createQueryParameter(props.pagination.pageNumberParameter) {
            required = false
            schema = IntegerSchema()._default(0)
            schema.minimum = BigDecimal.valueOf(0L)
            description = message("parameter.page")
        }

        createQueryParameter(props.pagination.pageSizeParameter) {
            required = false
            schema = IntegerSchema()._default(props.pagination.defaultPageSize)
            schema.minimum = BigDecimal.valueOf(1L)
            schema.maximum = BigDecimal.valueOf(props.pagination.maxPageSize.toLong())
            description = message(
                "parameter.size",
                props.pagination.defaultPageSize.toString(),
                props.pagination.maxPageSize.toString(),
            )
        }

        createQueryParameter(props.pagination.sortParameter) {
            required = false
            explode = true // repeatable parameter
            style = Parameter.StyleEnum.SIMPLE
            description = message("parameter.sort")
            schema = ArraySchema()
                .items(StringSchema()._default(""))
                .minItems(1)
        }
    }

    private fun Operation.createFieldsetQueryParameters(fieldsets: FieldsetsAvailable<*>?) {
        createQueryParameter(KiwiRouteHelper.FIELDSET_PARAM) {
            required = false
            schema = fieldsetNamesSchema(fieldsets)
            description = message("parameter.fieldset")
        }
        createQueryParameter(KiwiRouteHelper.FIELDS_PARAM) {
            required = false
            schema = StringSchema()
            description = message("parameter.fields")
        }
        createQueryParameter(KiwiRouteHelper.ACCELERATE_PARAM) {
            required = false
            schema = BooleanSchema()._default(null)
            description = message("parameter.accelerate")
        }
    }


    private fun Operation.createFilterQueryParameters(
        entitySchema: EntitySchema<out Any>?,
        entityFilterClass: Class<out GenericEntityFilter<*>>?,
    ) {
        val detailsLevel = props.web.openApi.queryParamsDetails
        val entityFilterProperties = entityFilterClass?.kotlin?.memberProperties
            ?.mapNotNull { it as? KMutableProperty1<out GenericEntityFilter<*>, *> }
            ?: emptyList()

        if (detailsLevel.entityFilters) {
            entityFilterProperties.forEach { prop ->
                createQueryParameter(parameter = prop.name) {
                    required = false
                    description = message("parameter.generic-entity-filter", entityFilterClass?.name.toString())
                }
            }
        }

        val entityFilterPropertyNames = entityFilterProperties.mapTo(mutableSetOf()) { it.name }

        entitySchema?.columns?.forEach { column ->
            val entityName = entitySchema.entityClass.simpleName

            // Default operator -> Column name, but only if not overridden by entity-filter field
            if ((column.type.filterSet.defaultOperator != null) && (column.fieldName !in entityFilterPropertyNames)) {
                if (detailsLevel.defaultColumnFilters) {
                    createQueryParameter(parameter = column.fieldName) {
                        required = false
                        description = message("parameter.column-filter.default", entityName)
                    }
                }
            }

            // Operators -> Column name + ":" + Operator
            if (detailsLevel.operatorColumnFilters) {
                column.type.filterSet.operators.forEach { operator ->
                    val parameter = column.fieldName + COLUMN_OPERATOR_SEPARATOR + operator
                    createQueryParameter(parameter = parameter) {
                        required = false
                        description = message("parameter.column-filter", entityName, operator)
                    }
                }
            }
        }
    }

    private fun Operation.createEntityIdPathVariable() {
        createPathVariable(KiwiRouteHelper.ID_VARIABLE) {
            required = true
            schema = entityIdSchema()
        }
    }

    private fun message(code: String, vararg args: Any): String {
        val locale = props.web.openApi.locale
        return messageBundle.getMessage(code, args.ifEmpty { null }, locale)
    }
}

private fun OpenAPI.getOrCreatePath(path: String): PathItem {
    val paths = paths ?: Paths().also { paths = it }
    return paths.computeIfAbsent(path) { PathItem() }
}

private inline fun OpenAPI.createOperation(
    method: HttpMethod,
    path: String,
    build: (Operation) -> Unit,
): PathItem {
    val operation = Operation()
    build(operation)
    return when (method) {
        HttpMethod.GET -> getOrCreatePath(path).get(operation)
        HttpMethod.POST -> getOrCreatePath(path).post(operation)
        HttpMethod.PUT -> getOrCreatePath(path).put(operation)
        HttpMethod.PATCH -> getOrCreatePath(path).patch(operation)
        HttpMethod.DELETE -> getOrCreatePath(path).delete(operation)
        HttpMethod.HEAD -> getOrCreatePath(path).head(operation)
        HttpMethod.OPTIONS -> getOrCreatePath(path).options(operation)
        HttpMethod.TRACE -> getOrCreatePath(path).trace(operation)
        else -> throw IllegalArgumentException("Unsupported HTTP method: ${method.name()}")
    }
}

private inline fun ApiResponses.createApiResponse(httpStatus: HttpStatus?, build: ApiResponse.() -> Unit) {
    val apiResponse = ApiResponse()
    apiResponse.build()
    val statusValue = httpStatus?.value() ?: "default"
    addApiResponse(statusValue.toString(), apiResponse)
}

private inline fun Content.createMediaType(mediaType: MediaTypeEnum, build: MediaType.() -> Unit) {
    val mediaTypeItem = MediaType()
    mediaTypeItem.build()
    addMediaType(mediaType.toString(), mediaTypeItem)
}

private inline fun Content.createJson(build: MediaType.() -> Unit) =
    createMediaType(MediaTypeEnum.APPLICATION_JSON, build)

private inline fun <reified T> Components.readAndRegisterSchema(prefix: String): String =
    readAndRegisterSchema(prefix, T::class.java)

private fun Components.readAndRegisterSchema(prefix: String, schemaType: Class<*>): String {
    val key = prefix + schemaType.simpleName
    if (schemas == null || key !in schemas.keys) {
        val converters = ModelConverters.getInstance()
        val resolvedSchemas = converters.readAll(schemaType)
        resolvedSchemas.forEach { (name, schema) ->
            schema.relativize(prefix = prefix) // mutates schema
            addSchemas(prefix + name, schema)
        }
    }
    return "#/components/schemas/$key"
}

private inline fun Components.registerApiResponse(name: String, build: ApiResponse.() -> Unit): String {
    if (responses == null || name !in responses.keys) {
        val response = ApiResponse()
        response.build()
        addResponses(name, response)
    }
    return "#/components/responses/$name"
}

private inline fun Operation.createParameter(build: Parameter.() -> Unit) {
    val parameter = Parameter()
    parameter.build()
    addParametersItem(parameter)
}

private inline fun Operation.createPathVariable(variable: String, build: Parameter.() -> Unit) = createParameter {
    `in` = "path"
    name = variable
    build()
}

private inline fun Operation.createQueryParameter(parameter: String, build: Parameter.() -> Unit) = createParameter {
    `in` = "query"
    name = parameter
    build()
}

private fun fieldsetNamesSchema(fieldsets: FieldsetsAvailable<*>?): StringSchema {
    val schema = StringSchema()
    if (fieldsets != null) {
        schema._enum(fieldsets.allFieldsets.map { it.name })
    }
    return schema
}

private fun pageResultSchema(itemSchema: Schema<*>): Schema<PageResult<*>> {
    val schema = Schema<PageResult<*>>()
    schema.type = "object"
    schema.addRequiredProperty(PageResult<*>::items.name, ArraySchema().items(itemSchema))
    schema.addRequiredProperty(PageResult<*>::pageSize.name, IntegerSchema().format("int32"))
    schema.addRequiredProperty(PageResult<*>::pageNumber.name, IntegerSchema().format("int32"))
    schema.addRequiredProperty(PageResult<*>::totalPages.name, IntegerSchema().format("int32"))
    schema.addRequiredProperty(PageResult<*>::totalItems.name, IntegerSchema().format("int64"))
    return schema
}

private fun <S : Schema<T>, T> S.addRequiredProperty(key: String, property: Schema<*>): S {
    addProperty(key, property)
    val required = property.required ?: mutableListOf<String>().also { property.required = it }
    required.add(key)
    return this
}

private fun Class<out Command<*>>.isGenericCommand() =
    GenericEntityCommand::class.java.isAssignableFrom(this)

private fun Class<out Command<*>>.isGenericBodyCommand() =
    GenericBodyCommand::class.java.isAssignableFrom(this)

// ModelConverter registering EntityId types as strings in OpenAPI Schema model
private class EntityIdModelConverter : ModelConverter {
    override fun resolve(
        annotatedType: AnnotatedType,
        context: ModelConverterContext,
        chain: Iterator<ModelConverter?>,
    ): Schema<*>? {
        val type = annotatedType.type
        val entityIdType = EntityId::class.java
        if (TypeUtils.isAssignable(entityIdType, type)) {
            return entityIdSchema(type as? Class<*>)
        }

        val rawType = unwrapTypeClass(type)
        return when {
            TypeUtils.isAssignable(entityIdType, rawType) -> entityIdSchema(rawType)
            chain.hasNext() -> chain.next()?.resolve(annotatedType, context, chain)
            else -> null
        }
    }

    private fun unwrapTypeClass(type: Type): Class<*> = when (type) {
        is Class<*> -> type
        is JavaType -> type.rawClass
        is ResolvableType -> type.toClass()
        else -> ResolvableType.forType(type).toClass()
    }
}
