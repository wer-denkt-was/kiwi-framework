package de.kiwi.framework.web

import de.kiwi.api.application.service.UserDescriptorProvider
import de.kiwi.api.domain.EntityIdToNumberConverter
import de.kiwi.api.domain.EntityIdToStringConverter
import de.kiwi.api.domain.NumberToEntityIdConverterFactory
import de.kiwi.api.domain.StringToEntityIdConverterFactory
import de.kiwi.api.application.service.EntityTypeProvider
import de.kiwi.framework.KiwiFrameworkAutoConfiguration
import de.kiwi.framework.KiwiFrameworkProperties
import de.kiwi.framework.infrastructure.bus.KiwiBusAutoConfiguration
import de.kiwi.framework.infrastructure.security.UserDescriptorArgumentResolver
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.domain.Pageable
import org.springframework.data.web.config.PageableHandlerMethodArgumentResolverCustomizer
import org.springframework.data.web.config.SortHandlerMethodArgumentResolverCustomizer
import org.springframework.format.FormatterRegistry
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Auto-configuration of the Kiwi web layer for servlet web applications:
 * Generic entity and analytical report endpoints, exception handling, pagination and argument resolvers.
 * Can be disabled using `kiwi.web.enabled=false`.
 */
@AutoConfiguration(after = [KiwiFrameworkAutoConfiguration::class, KiwiBusAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBooleanProperty("kiwi.web.enabled", matchIfMissing = true)
@EnableConfigurationProperties(KiwiFrameworkProperties::class)
@Import(KiwiRouterFunctionConfig::class, KiwiGlobalCorsConfig::class)
class KiwiWebAutoConfiguration(
    private val props: KiwiFrameworkProperties,
    private val userDescriptorProvider: UserDescriptorProvider,
) : WebMvcConfigurer {

    @Bean
    fun frameworkExceptionResponseResolverProvider() =
        KiwiBuiltinExceptionResponseResolverProvider()

    @Bean
    @ConditionalOnMissingBean
    fun exceptionResponseManager(resolvers: ObjectProvider<ExceptionResponseManager.ResolverProvider>) =
        ExceptionResponseManager(resolvers)

    @Bean
    @ConditionalOnMissingBean
    fun kiwiControllerAdvice(exceptionResponseManager: ExceptionResponseManager) =
        KiwiControllerAdvice(exceptionResponseManager)

    @Bean
    @ConditionalOnMissingBean
    fun kiwiRouteHelper(entityTypeProvider: EntityTypeProvider) =
        KiwiRouteHelper(props, entityTypeProvider)

    @Bean
    fun pageableArgumentCustomizer() = PageableHandlerMethodArgumentResolverCustomizer {
        it.setMaxPageSize(props.pagination.maxPageSize)
        it.setFallbackPageable(Pageable.ofSize(props.pagination.defaultPageSize))
        it.setPageParameterName(props.pagination.pageNumberParameter)
        it.setSizeParameterName(props.pagination.pageSizeParameter)
    }

    @Bean
    fun sortArgumentCustomizer() = SortHandlerMethodArgumentResolverCustomizer {
        it.setSortParameter(props.pagination.sortParameter)
    }

    @Bean
    @ConditionalOnMissingBean
    fun paginationResolver() = PaginationResolver(props.pagination)

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        val userResolver = UserDescriptorArgumentResolver(userDescriptorProvider)
        resolvers.add(userResolver)
    }

    override fun addFormatters(registry: FormatterRegistry) {
        registry.addConverterFactory(StringToEntityIdConverterFactory)
        registry.addConverterFactory(NumberToEntityIdConverterFactory)
        registry.addConverter(EntityIdToStringConverter)
        registry.addConverter(EntityIdToNumberConverter)
    }
}
