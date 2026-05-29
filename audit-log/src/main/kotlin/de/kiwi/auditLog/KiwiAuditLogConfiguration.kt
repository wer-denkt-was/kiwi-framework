package de.kiwi.auditLog

import com.fasterxml.jackson.databind.ObjectMapper
import de.kiwi.api.application.service.AuditLoggerService
import de.kiwi.api.application.service.TransactionService
import de.kiwi.api.util.slf4j
import de.kiwi.auditLog.KiwiAuditLogProperties.SerializerType
import de.kiwi.auditLog.application.AuditLogRepository
import de.kiwi.auditLog.application.AuditableDetailsSerializer
import de.kiwi.auditLog.infrastructure.KiwiAuditLoggerService
import kotlinx.serialization.encodeToString
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.util.ClassUtils
import kotlin.text.encodeToByteArray
import kotlinx.serialization.json.Json as KotlinJson

private val log = slf4j<KiwiAuditLogConfiguration>()

@Configuration
@EntityScan("de.kiwi.auditLog.domain")
@EnableJpaRepositories("de.kiwi.auditLog.application")
@EnableConfigurationProperties(KiwiAuditLogProperties::class)
class KiwiAuditLogConfiguration(private val props: KiwiAuditLogProperties) {

    @Bean
    fun auditLoggerService(
        auditLogRepositoryProvider: ObjectProvider<AuditLogRepository>,
        auditableDetailsSerializer: AuditableDetailsSerializer,
        transactionService: TransactionService,
    ): AuditLoggerService {
        return if (props.enabled) {
            log.info("Initializing Kiwi AuditLoggerService (serializer=${props.serializer})")
            val auditLogRepository = requireNotNull(auditLogRepositoryProvider.ifUnique) {
                "Missing ${AuditLogRepository::class.simpleName} for Kiwi AuditLoggerService"
            }
            KiwiAuditLoggerService(
                auditLogRepository = auditLogRepository,
                detailsSerializer = auditableDetailsSerializer,
                transactionService = transactionService,
                includeSeverity = props.includeSeverity,
            )
        } else {
            log.info("AuditLoggerService is disabled")
            AuditLoggerService.DoNothing
        }
    }

    @Bean
    fun auditableDetailsSerializer(
        objectMapperProvider: ObjectProvider<ObjectMapper>,
    ): AuditableDetailsSerializer {
        return when (props.serializer) {
            SerializerType.Jackson -> {
                val objectMapper = requireNotNull(objectMapperProvider.ifUnique) {
                    "Missing Jackson ObjectMapper for AuditableDetailsSerializer"
                }
                AuditableDetailsSerializer { objectMapper.writeValueAsBytes(it) }
            }
        }
    }
}
