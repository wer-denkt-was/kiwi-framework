package de.kiwi.framework.infrastructure.generic

import tools.jackson.databind.ObjectMapper
import de.kiwi.api.application.generic.GenericEntityQueryHandler
import de.kiwi.api.application.service.EntityTypeProvider
import de.kiwi.api.application.service.QuerySchemaProvider
import de.kiwi.api.schema.QuerySchemaService
import de.kiwi.framework.application.generic.GenericHandlerProvider
import de.kiwi.framework.application.generic.GenericMetadataProvider
import de.kiwi.framework.infrastructure.jpa.EntityTypeProviderConfiguration
import de.kiwi.framework.infrastructure.schema.QuerySchemaConfiguration
import de.kiwi.framework.infrastructure.util.ClassPathScannerUtil
import de.kiwi.framework.infrastructure.util.FrameworkUtilConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

/**
 * Creates beans responsible for handling generic create, read, update, and delete commands/queries.
 */
@Configuration(proxyBeanMethods = false)
@Import(
    QuerySchemaConfiguration::class,
    EntityTypeProviderConfiguration::class,
    FrameworkUtilConfiguration::class,
)
class GenericHandlerConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun genericEntityQueryHandler(querySchemaService: QuerySchemaService) =
        GenericEntityQueryHandler(querySchemaService)

    @Bean
    @ConditionalOnMissingBean
    fun genericHandlerProvider(objectMapper: ObjectMapper, scanner: ClassPathScannerUtil): GenericHandlerProvider =
        SpringGenericHandlerProvider(objectMapper, scanner)

    @Bean
    @ConditionalOnMissingBean
    fun genericMetadataProvider(
        objectMapper: ObjectMapper,
        entityTypeProvider: EntityTypeProvider,
        querySchemaProvider: QuerySchemaProvider,
        genericHandlerProvider: GenericHandlerProvider,
    ): GenericMetadataProvider =
        GenericMetadataProvider(objectMapper, entityTypeProvider, querySchemaProvider, genericHandlerProvider)
}
