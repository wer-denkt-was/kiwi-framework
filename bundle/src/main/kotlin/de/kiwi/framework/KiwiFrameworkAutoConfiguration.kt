package de.kiwi.framework

import de.kiwi.api.application.GlobalSingletonBean
import de.kiwi.api.application.service.ClockProvider
import de.kiwi.api.application.service.TransactionService
import de.kiwi.api.application.service.UserDescriptorProvider
import de.kiwi.api.domain.EntityIdJsonModule
import de.kiwi.framework.infrastructure.generic.GenericHandlerConfiguration
import de.kiwi.framework.infrastructure.jpa.EntityTypeProviderConfiguration
import de.kiwi.framework.infrastructure.olap.AnalyticalReportConfiguration
import de.kiwi.framework.infrastructure.schema.FieldsetValidationConfiguration
import de.kiwi.framework.infrastructure.schema.QuerySchemaConfiguration
import de.kiwi.framework.infrastructure.security.AuthenticationUserDescriptorConverter
import de.kiwi.framework.infrastructure.security.SpringUserDescriptorProvider
import de.kiwi.framework.infrastructure.spring.SpringTransactionService
import de.kiwi.framework.infrastructure.util.FrameworkUtilConfiguration
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Autoconfiguration of the Kiwi Framework core:
 * Query schemata, generic entity handlers, analytical reports, user descriptors, transactions and clock.
 *
 * Every bean can be replaced by declaring a bean of the same type.
 * @see de.kiwi.framework.infrastructure.bus.KiwiBusAutoConfiguration
 * @see de.kiwi.framework.web.KiwiWebAutoConfiguration
 */
@AutoConfiguration(
    after = [
        JacksonAutoConfiguration::class,
        HibernateJpaAutoConfiguration::class,
        TransactionAutoConfiguration::class,
    ],
)
@EnableConfigurationProperties(KiwiFrameworkProperties::class)
@Import(
    FrameworkUtilConfiguration::class,
    EntityTypeProviderConfiguration::class,
    QuerySchemaConfiguration::class,
    FieldsetValidationConfiguration::class,
    GenericHandlerConfiguration::class,
    AnalyticalReportConfiguration::class,
)
class KiwiFrameworkAutoConfiguration(private val props: KiwiFrameworkProperties) {

    @Bean
    @ConditionalOnMissingBean
    fun entityIdJsonModule() = EntityIdJsonModule()

    @Bean
    @ConditionalOnMissingBean
    fun userDescriptorProvider(
        converterProvider: ObjectProvider<AuthenticationUserDescriptorConverter>,
    ): UserDescriptorProvider {
        val converters = converterProvider.toList()
        return SpringUserDescriptorProvider(converters, rolePrefix = props.security.rolePrefix)
    }

    @Bean
    fun userDescriptorGlobalSingletonBean(provider: ObjectProvider<UserDescriptorProvider>) =
        GlobalSingletonBean(UserDescriptorProvider.Global, provider)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(PlatformTransactionManager::class)
    fun transactionService(manager: PlatformTransactionManager): TransactionService {
        return SpringTransactionService(
            manager = manager,
            readIsolation = props.transactions.readIsolation,
            writeIsolation = props.transactions.writeIsolation,
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun clockProvider(): ClockProvider {
        val zone = props.clock.zone
            ?.let(ZoneId::of)
            ?: ZoneOffset.UTC

        val clock = props.clock.fixed
            ?.let { fixed -> Clock.fixed(LocalDateTime.parse(fixed).atZone(zone).toInstant(), zone) }
            ?: Clock.system(zone)

        return ClockProvider { clock }
    }
}
