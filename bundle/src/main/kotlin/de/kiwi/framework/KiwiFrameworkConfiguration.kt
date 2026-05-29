package de.kiwi.framework

import de.kiwi.api.application.service.AuthenticationTokenService
import de.kiwi.api.application.service.ClockProvider
import de.kiwi.api.application.service.TransactionService
import de.kiwi.api.application.service.UserDescriptorProvider
import de.kiwi.api.domain.EntityIdJsonModule
import de.kiwi.framework.infrastructure.bus.CommandQueryEventBusConfiguration
import de.kiwi.framework.infrastructure.generic.GenericHandlerConfiguration
import de.kiwi.framework.infrastructure.jpa.EntityTypeProviderConfiguration
import de.kiwi.framework.infrastructure.mail.MailSenderConfiguration
import de.kiwi.framework.infrastructure.olap.AnalyticalReportConfiguration
import de.kiwi.framework.infrastructure.schema.FieldAccessControlInitializer
import de.kiwi.framework.infrastructure.security.AuthenticationUserDescriptorConverter
import de.kiwi.framework.infrastructure.security.JwtTokenService
import de.kiwi.framework.infrastructure.security.SpringUserDescriptorProvider
import de.kiwi.framework.infrastructure.spring.SpringTransactionService
import de.kiwi.framework.web.KiwiWebConfiguration
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset

@Configuration
@EnableConfigurationProperties(KiwiFrameworkProperties::class)
@EntityScan("de.kiwi.api", "de.kiwi.framework")
@ComponentScan("de.kiwi.framework")
@Import(
    EntityTypeProviderConfiguration::class,
    CommandQueryEventBusConfiguration::class,
    GenericHandlerConfiguration::class,
    AnalyticalReportConfiguration::class,
    FieldAccessControlInitializer::class,
    KiwiWebConfiguration::class,
    MailSenderConfiguration::class,
)
class KiwiFrameworkConfiguration(private val props: KiwiFrameworkProperties) {

    @Bean
    fun entityIdJsonModule() = EntityIdJsonModule()

    @Bean
    @ConditionalOnMissingBean
    fun userDescriptorProvider(
        converterProvider: ObjectProvider<AuthenticationUserDescriptorConverter>,
    ): UserDescriptorProvider {
        val converters = converterProvider.toList()
        return SpringUserDescriptorProvider(converters)
    }

    @Bean
    @ConditionalOnMissingBean(TransactionService::class)
    fun transactionService(manager: PlatformTransactionManager): TransactionService {
        return SpringTransactionService(manager)
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty("kiwi.jwt.enabled", matchIfMissing = true)
    fun authenticationTokenService(clockProvider: ClockProvider): AuthenticationTokenService {
        return JwtTokenService(props = props.jwt, clockProvider = clockProvider)
    }

    @Bean
    @ConditionalOnMissingBean
    fun clockProvider(): ClockProvider {
        val zone = props.clock.zone
            ?.let(ZoneOffset::of)
            ?: ZoneOffset.UTC

        val clock = props.clock.fixed
            ?.let { fixed -> Clock.fixed(LocalDateTime.parse(fixed).toInstant(zone), zone) }
            ?: Clock.system(zone)

        return ClockProvider { clock }
    }
}
