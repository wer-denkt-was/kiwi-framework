package de.kiwi.framework.infrastructure.schema

import de.kiwi.api.schema.QuerySchemaService
import de.kiwi.framework.infrastructure.util.ClassPathScannerUtil
import de.kiwi.framework.infrastructure.util.FrameworkUtilConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

/**
 * Creates beans responsible for handling schemata and fieldsets.
 */
@Configuration
@Import(FrameworkUtilConfiguration::class)
class QuerySchemaConfiguration {

    @Bean
    fun querySchemaService() = JpaQuerySchemaService()

    @Bean
    fun schemaQueryBuilder(schemaService: QuerySchemaService) = SchemaQueryBuilder(schemaService)

    @Bean
    fun querySchemaProvider(scanner: ClassPathScannerUtil) = SpringQuerySchemaProvider(scanner)
}
