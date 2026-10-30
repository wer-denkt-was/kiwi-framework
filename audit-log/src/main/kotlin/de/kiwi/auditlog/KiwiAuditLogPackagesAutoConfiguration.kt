package de.kiwi.auditlog

import de.kiwi.auditlog.domain.AuditLogEntry
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurationPackages
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration
import org.springframework.boot.persistence.autoconfigure.EntityScanPackages
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar
import org.springframework.core.type.AnnotationMetadata

/**
 * Registers the audit-log entity package for JPA entity scanning, in addition to the application's own packages:
 * - As autoconfiguration package, used by Spring Boot for entity scanning by default
 * - As entity-scan package, if the application declares `@EntityScan`, which replaces the autoconfiguration packages for entity scanning
 *
 * Ordered before the JPA auto-configurations, as these resolve the packages while registering their beans.
 * The repository package is not registered, as Spring Data registers repositories of overlapping packages twice:
 * The repository is registered by [KiwiAuditLogAutoConfiguration] instead.
 */
@AutoConfiguration(before = [HibernateJpaAutoConfiguration::class, DataJpaRepositoriesAutoConfiguration::class])
@ConditionalOnBooleanProperty("kiwi.audit-log.enabled", matchIfMissing = true)
@Import(KiwiAuditLogPackagesAutoConfiguration.Registrar::class)
class KiwiAuditLogPackagesAutoConfiguration {

    internal class Registrar : ImportBeanDefinitionRegistrar {
        override fun registerBeanDefinitions(metadata: AnnotationMetadata, registry: BeanDefinitionRegistry) {
            val entityPackage = AuditLogEntry::class.java.packageName
            AutoConfigurationPackages.register(registry, entityPackage)
            if (registry.containsBeanDefinition(EntityScanPackages::class.java.name)) {
                EntityScanPackages.register(registry, entityPackage)
            }
        }
    }
}
