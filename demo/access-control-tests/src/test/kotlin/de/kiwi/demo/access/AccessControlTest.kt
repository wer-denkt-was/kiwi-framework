package de.kiwi.demo.access

import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import com.samskivert.mustache.Mustache
import de.kiwi.api.application.service.UserDescriptorProvider
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.auditlog.KiwiAuditLogAutoConfiguration
import de.kiwi.auditlog.KiwiAuditLogPackagesAutoConfiguration
import de.kiwi.framework.KiwiFrameworkAutoConfiguration
import de.kiwi.framework.infrastructure.bus.KiwiBusAutoConfiguration
import de.kiwi.framework.infrastructure.mail.KiwiMailAutoConfiguration
import de.kiwi.demo.access.instrument.ExplainInstrument
import de.kiwi.demo.access.instrument.LogPerformanceProcessor
import de.kiwi.demo.access.instrument.PerformanceInstrument
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.context.support.ConversionServiceFactoryBean
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@MustBeDocumented
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AccessControlTestConfig::class)
annotation class AccessControlTest

@SpringBootConfiguration
@EntityScan("de.kiwi.demo.access")
@ComponentScan("de.kiwi.demo.access")
@EnableJpaRepositories("de.kiwi.demo.access")
@ImportAutoConfiguration(
    KiwiFrameworkAutoConfiguration::class,
    KiwiBusAutoConfiguration::class,
    KiwiMailAutoConfiguration::class,
    KiwiAuditLogPackagesAutoConfiguration::class,
    KiwiAuditLogAutoConfiguration::class,
)
class AccessControlTestConfig {

    @Bean
    fun conversionService() = ConversionServiceFactoryBean()

    @Bean
    fun objectMapper() = jacksonObjectMapper()

    @Bean
    fun mustacheCompiler() = Mustache.compiler()!!

    @Bean
    fun userAccountHolder() = TestUserAccountHolder()

    @Bean
    fun explainInstrument(objectMapper: ObjectMapper) = ExplainInstrument(objectMapper)

    @Bean
    fun performanceInstrument(explainInstrument: ExplainInstrument) = PerformanceInstrument(
        explainInstrument = explainInstrument,
        processors = listOf(
            LogPerformanceProcessor(ansiOutput = true),
        )
    )
}
