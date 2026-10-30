package de.kiwi.framework.web

import de.kiwi.api.application.query.QueryBus
import de.kiwi.api.application.service.CommandQueryBus
import de.kiwi.api.application.service.EntityTypeProvider
import de.kiwi.api.application.service.QuerySchemaProvider
import de.kiwi.api.application.service.UserDescriptorProvider
import de.kiwi.framework.KiwiFrameworkProperties
import de.kiwi.api.schema.FieldsetValidationService
import de.kiwi.framework.application.generic.GenericHandlerProvider
import de.kiwi.framework.application.generic.GenericMetadataProvider
import de.kiwi.framework.application.olap.AnalyticalReportProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.core.convert.ConversionService
import tools.jackson.databind.ObjectMapper
import org.springframework.web.servlet.function.RouterFunctionDsl
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

/**
 * Router functions for the generic entity and analytical report endpoints.
 * Imported by [KiwiWebAutoConfiguration].
 */
@Configuration(proxyBeanMethods = false)
class KiwiRouterFunctionConfig(
    private val helper: KiwiRouteHelper,
    private val exceptionResponseManager: ExceptionResponseManager,
) {
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty("kiwi.web.generic-controller", matchIfMissing = true)
    fun genericEntityMethodHandler(
        commandQueryBus: CommandQueryBus,
        entityTypeProvider: EntityTypeProvider,
        querySchemaProvider: QuerySchemaProvider,
        genericHandlerProvider: GenericHandlerProvider,
        genericMetadataProvider: GenericMetadataProvider,
        userDescriptorProvider: UserDescriptorProvider,
        paginationResolver: PaginationResolver,
        fieldsetValidationService: FieldsetValidationService,
        conversionService: ConversionService,
    ) = GenericEntityMethodHandler(
        commandQueryBus = commandQueryBus,
        entityTypeProvider = entityTypeProvider,
        querySchemaProvider = querySchemaProvider,
        genericHandlerProvider = genericHandlerProvider,
        genericMetadataProvider = genericMetadataProvider,
        userDescriptorProvider = userDescriptorProvider,
        paginationResolver = paginationResolver,
        fieldsetValidationService = fieldsetValidationService,
        conversionService = conversionService,
    )

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty("kiwi.web.report-controller", matchIfMissing = true)
    fun analyticalReportMethodHandler(
        queryBus: QueryBus,
        objectMapper: ObjectMapper,
        paginationResolver: PaginationResolver,
        userDescriptorProvider: UserDescriptorProvider,
        analyticalReportProvider: AnalyticalReportProvider,
    ) = AnalyticalReportMethodHandler(
        queryBus = queryBus,
        objectMapper = objectMapper,
        paginationResolver = paginationResolver,
        userDescriptorProvider = userDescriptorProvider,
        analyticalReportProvider = analyticalReportProvider,
    )

    @Bean
    @Order(20) // Ordered after analyticalReportRouter
    @ConditionalOnProperty("kiwi.web.generic-controller", matchIfMissing = true)
    fun genericEntityRouter(
        genericHandlerProvider: GenericHandlerProvider,
        entityMethodHandler: GenericEntityMethodHandler,
        querySchemaProvider: QuerySchemaProvider,
    ) = routerOrNull {
        helper.forEachEntityPath(querySchemaProvider.schemaEntityClasses) { entityClass, path ->
            GET(helper.schemaPath(path)) { request -> entityMethodHandler.handleEntitySchema(request, entityClass) }
        }
        helper.forEachEntityPath(genericHandlerProvider.queryClasses.keys) { entityClass, path ->
            GET(helper.basePath(path)) { request -> entityMethodHandler.handleList(request, entityClass) }
            GET(helper.byIdPath(path)) { request -> entityMethodHandler.handleFindById(request, entityClass) }
        }
        helper.forEachEntityPath(genericHandlerProvider.createClasses.keys) { entityClass, path ->
            POST(helper.basePath(path)) { request -> entityMethodHandler.handleCreate(request, entityClass) }
        }
        helper.forEachEntityPath(genericHandlerProvider.updateClasses.keys) { entityClass, path ->
            PATCH(helper.byIdPath(path)) { request -> entityMethodHandler.handleUpdate(request, entityClass) }
        }
        helper.forEachEntityPath(genericHandlerProvider.deleteClasses.keys) { entityClass, path ->
            DELETE(helper.byIdPath(path)) { request -> entityMethodHandler.handleDelete(request, entityClass) }
        }
        onError<Exception> { ex, _ -> handleException(ex) }
    }

    @Bean
    @Order(10) // Ordered before genericEntityRouter
    @ConditionalOnProperty("kiwi.web.report-controller", matchIfMissing = true)
    fun analyticalReportRouter(
        analyticalReportProvider: AnalyticalReportProvider,
        reportMethodHandler: AnalyticalReportMethodHandler,
    ) = routerOrNull {
        helper.forEachEntityPath(analyticalReportProvider.reportEntityClasses) { entityClass, path ->
            GET(helper.reportPath(path)) { req -> reportMethodHandler.handleReport(req, entityClass) }
            GET(helper.reportSchemaPath(path)) { req -> reportMethodHandler.handleReportSchema(req, entityClass) }
        }
        onError<Exception> { ex, _ -> handleException(ex) }
    }

    private fun handleException(ex: Throwable): ServerResponse {
        val response = exceptionResponseManager.resolveException(ex)
        return ServerResponse.status(response.status).body(response.body)
    }
}

private fun routerOrNull(routes: (RouterFunctionDsl.() -> Unit)) = try {
    router(routes)
} catch (_: IllegalStateException) {
    null
}
