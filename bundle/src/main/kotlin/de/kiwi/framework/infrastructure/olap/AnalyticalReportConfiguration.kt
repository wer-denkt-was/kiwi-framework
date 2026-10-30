package de.kiwi.framework.infrastructure.olap

import de.kiwi.api.application.service.EntityTypeProvider
import de.kiwi.framework.application.olap.AnalyticalReportProvider
import de.kiwi.framework.application.olap.AnalyticalReportQueryHandler
import de.kiwi.framework.infrastructure.jpa.EntityTypeProviderConfiguration
import de.kiwi.framework.infrastructure.util.ClassPathScannerUtil
import de.kiwi.framework.infrastructure.util.FrameworkUtilConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.convert.ConversionService

@Configuration(proxyBeanMethods = false)
@Import(EntityTypeProviderConfiguration::class, FrameworkUtilConfiguration::class)
class AnalyticalReportConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun analyticalReportQueryHandler(
        conversionService: ConversionService,
        entityTypeProvider: EntityTypeProvider,
    ) = AnalyticalReportQueryHandler(
        conversionService = conversionService,
        entityTypeProvider = entityTypeProvider,
    )

    @Bean
    @ConditionalOnMissingBean
    fun analyticalReportProvider(scanner: ClassPathScannerUtil): AnalyticalReportProvider =
        SpringAnalyticalReportProvider(scanner)
}
