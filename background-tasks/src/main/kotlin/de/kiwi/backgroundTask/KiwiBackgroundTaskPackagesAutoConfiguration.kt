package de.kiwi.backgroundTask

import de.kiwi.backgroundTask.domain.BackgroundTask
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
 * Registers the package of the background task entities ([BackgroundTask], `BackgroundTaskData`) for JPA, before the
 * JPA auto-configurations scan the entities and repositories: As an autoconfiguration package, and as an entity scan
 * package if the application uses `@EntityScan`.
 */
@AutoConfiguration(before = [HibernateJpaAutoConfiguration::class, DataJpaRepositoriesAutoConfiguration::class])
@ConditionalOnBooleanProperty("kiwi.background-task.enabled", matchIfMissing = true)
@Import(KiwiBackgroundTaskPackagesAutoConfiguration.Registrar::class)
class KiwiBackgroundTaskPackagesAutoConfiguration {

    /** Registers the entity package of the background tasks */
    internal class Registrar : ImportBeanDefinitionRegistrar {
        override fun registerBeanDefinitions(metadata: AnnotationMetadata, registry: BeanDefinitionRegistry) {
            val entityPackage = BackgroundTask::class.java.packageName
            AutoConfigurationPackages.register(registry, entityPackage)

            if (registry.containsBeanDefinition(EntityScanPackages::class.java.name)) {
                EntityScanPackages.register(registry, entityPackage)
            }
        }
    }
}
