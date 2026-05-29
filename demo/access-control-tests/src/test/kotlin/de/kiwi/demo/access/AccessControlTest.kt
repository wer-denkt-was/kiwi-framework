package de.kiwi.demo.access

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.samskivert.mustache.Mustache
import de.kiwi.api.application.service.UserDescriptorProvider
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.auditLog.KiwiAuditLogConfiguration
import de.kiwi.framework.KiwiFrameworkConfiguration
import de.kiwi.demo.access.instrument.ExplainInstrument
import de.kiwi.demo.access.instrument.LogPerformanceProcessor
import de.kiwi.demo.access.instrument.PerformanceInstrument
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
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
@Import(KiwiFrameworkConfiguration::class, KiwiAuditLogConfiguration::class)
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
