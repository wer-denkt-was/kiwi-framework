package de.kiwi.framework.web

import de.kiwi.api.application.service.QuerySchemaProvider
import de.kiwi.api.application.service.UserDescriptorProvider
import de.kiwi.framework.KiwiFrameworkProperties
import de.kiwi.framework.application.generic.GenericHandlerProvider
import de.kiwi.framework.application.olap.AnalyticalReportProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.web.servlet.function.RouterFunctionDsl
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
@EnableConfigurationProperties(KiwiFrameworkProperties::class)
class KiwiRouterFunctionConfig(
    private val helper: KiwiRouteHelper,
    private val userDescriptorProvider: UserDescriptorProvider,
    private val exceptionResponseManager: ExceptionResponseManager,
) {
    @Bean
    @Order(20) // Ordered after analyticalReportRouter
    @ConditionalOnBean(GenericHandlerProvider::class, GenericEntityMethodHandler::class, QuerySchemaProvider::class)
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
            PUT(helper.byIdPath(path)) { request -> entityMethodHandler.handleUpdate(request, entityClass) }
        }
        helper.forEachEntityPath(genericHandlerProvider.deleteClasses.keys) { entityClass, path ->
            DELETE(helper.byIdPath(path)) { request -> entityMethodHandler.handleDelete(request, entityClass) }
        }
        onError<Exception> { ex, _ -> handleException(ex) }
    }

    @Bean
    @Order(10) // Ordered before genericEntityRouter
    @ConditionalOnBean(AnalyticalReportProvider::class, AnalyticalReportMethodHandler::class)
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
        val user = userDescriptorProvider.currentUser()
        val response = exceptionResponseManager.resolveException(ex, user)
        return ServerResponse.status(response.status).body(response.body)
    }
}

private fun routerOrNull(routes: (RouterFunctionDsl.() -> Unit)) = try {
    router(routes)
} catch (_: IllegalStateException) {
    null
}
