package de.kiwi.framework.infrastructure.schema

import de.kiwi.api.application.service.QuerySchemaProvider
import de.kiwi.api.schema.QuerySchemaService
import de.kiwi.framework.infrastructure.util.ClassPathScannerUtil
import de.kiwi.framework.infrastructure.util.FrameworkUtilConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

/**
 * Creates beans responsible for handling schemata and fieldsets.
 */
@Configuration(proxyBeanMethods = false)
@Import(FrameworkUtilConfiguration::class)
class QuerySchemaConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun querySchemaService(): QuerySchemaService = JpaQuerySchemaService()

    @Bean
    @ConditionalOnMissingBean
    fun schemaQueryBuilder(schemaService: QuerySchemaService) = SchemaQueryBuilder(schemaService)

    @Bean
    @ConditionalOnMissingBean
    fun querySchemaProvider(scanner: ClassPathScannerUtil): QuerySchemaProvider = SpringQuerySchemaProvider(scanner)
}
