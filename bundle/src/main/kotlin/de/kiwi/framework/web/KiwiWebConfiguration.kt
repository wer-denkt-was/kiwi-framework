package de.kiwi.framework.web

import de.kiwi.api.application.service.UserDescriptorProvider
import de.kiwi.api.domain.EntityIdToNumberConverter
import de.kiwi.api.domain.EntityIdToStringConverter
import de.kiwi.api.domain.NumberToEntityIdConverterFactory
import de.kiwi.api.domain.StringToEntityIdConverterFactory
import de.kiwi.framework.KiwiFrameworkProperties
import de.kiwi.framework.infrastructure.security.UserDescriptorArgumentResolver
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.domain.Pageable
import org.springframework.data.web.config.PageableHandlerMethodArgumentResolverCustomizer
import org.springframework.data.web.config.SortHandlerMethodArgumentResolverCustomizer
import org.springframework.format.FormatterRegistry
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
@EnableConfigurationProperties(KiwiFrameworkProperties::class)
class KiwiWebConfiguration(
    private val props: KiwiFrameworkProperties,
    private val userDescriptorProvider: UserDescriptorProvider,
) : WebMvcConfigurer {

    @Bean
    fun frameworkExceptionResponseResolverProvider() =
        KiwiBuiltinExceptionResponseResolverProvider()

    @Bean
    fun exceptionResponseManager(resolvers: ObjectProvider<ExceptionResponseManager.ResolverProvider>) =
        ExceptionResponseManager(resolvers)

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

    override fun configurePathMatch(configurer: PathMatchConfigurer) {
        @Suppress("DEPRECATION")
        configurer.setUseTrailingSlashMatch(true)
    }
}
