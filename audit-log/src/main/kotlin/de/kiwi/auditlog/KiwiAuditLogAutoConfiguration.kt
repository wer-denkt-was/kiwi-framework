package de.kiwi.auditlog

import de.kiwi.api.application.service.AuditLoggerService
import de.kiwi.api.application.service.TransactionService
import de.kiwi.api.util.slf4j
import de.kiwi.auditlog.KiwiAuditLogProperties.SerializerType
import de.kiwi.auditlog.application.AuditLogRepository
import de.kiwi.auditlog.application.AuditableDetailsSerializer
import de.kiwi.auditlog.infrastructure.KiwiAuditLoggerService
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import tools.jackson.databind.ObjectMapper

private val log = slf4j<KiwiAuditLogAutoConfiguration>()

/**
 * Autoconfiguration of the persistent Kiwi audit-log, storing audit-log entries in the database.
 * The audit-log entity and repository packages are registered by [KiwiAuditLogPackagesAutoConfiguration].
 * Can be disabled using `kiwi.audit-log.enabled=false`, which disables audit-logging altogether.
 */
@AutoConfiguration(after = [HibernateJpaAutoConfiguration::class, DataJpaRepositoriesAutoConfiguration::class])
@ConditionalOnBooleanProperty("kiwi.audit-log.enabled", matchIfMissing = true)
@EnableConfigurationProperties(KiwiAuditLogProperties::class)
class KiwiAuditLogAutoConfiguration(private val props: KiwiAuditLogProperties) {

    /**
     * Registers the [AuditLogRepository], unless it has already been registered, either by Spring Boot
     * using the autoconfiguration packages, or by the application's own `@EnableJpaRepositories`.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(AuditLogRepository::class)
    @EnableJpaRepositories(basePackageClasses = [AuditLogRepository::class])
    internal class AuditLogRepositoryConfiguration

    @Bean
    @ConditionalOnMissingBean
    fun auditLoggerService(
        auditLogRepositoryProvider: ObjectProvider<AuditLogRepository>,
        auditableDetailsSerializer: AuditableDetailsSerializer,
        transactionService: TransactionService,
    ): AuditLoggerService {
        log.info("Initializing Kiwi AuditLoggerService (serializer=${props.serializer})")
        val auditLogRepository = requireNotNull(auditLogRepositoryProvider.ifUnique) {
            "Missing ${AuditLogRepository::class.simpleName} for Kiwi AuditLoggerService"
        }
        return KiwiAuditLoggerService(
            auditLogRepository = auditLogRepository,
            detailsSerializer = auditableDetailsSerializer,
            transactionService = transactionService,
            includeSeverity = props.includeSeverity,
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun auditableDetailsSerializer(
        objectMapperProvider: ObjectProvider<ObjectMapper>,
    ): AuditableDetailsSerializer {
        return when (props.serializer) {
            SerializerType.Jackson -> {
                val objectMapper = requireNotNull(objectMapperProvider.ifUnique) {
                    "Missing Jackson ObjectMapper for AuditableDetailsSerializer"
                }
                AuditableDetailsSerializer { objectMapper.writeValueAsString(it) }
            }
        }
    }
}
