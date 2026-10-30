package de.kiwi.auditlog

import de.kiwi.api.application.service.AuditLoggerService
import de.kiwi.api.application.service.TransactionService
import de.kiwi.auditlog.application.AuditLogRepository
import de.kiwi.auditlog.domain.AuditLogEntry
import de.kiwi.auditlog.infrastructure.KiwiAuditLoggerService
import de.kiwi.testapp.OtherEntity
import de.kiwi.testapp.OtherEntityRepository
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurationPackage
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

class KiwiAuditLogAutoConfigurationTest {

    /** Application relying on the auto-configuration packages for entity and repository scanning */
    @Configuration(proxyBeanMethods = false)
    @AutoConfigurationPackage(basePackageClasses = [OtherEntity::class])
    class DefaultPackagesApplication

    /** Application declaring its entity and repository packages explicitly */
    @Configuration(proxyBeanMethods = false)
    @AutoConfigurationPackage(basePackageClasses = [OtherEntity::class])
    @EntityScan(basePackageClasses = [OtherEntity::class])
    @EnableJpaRepositories(basePackageClasses = [OtherEntityRepository::class])
    class ExplicitPackagesApplication

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                DataSourceAutoConfiguration::class.java,
                HibernateJpaAutoConfiguration::class.java,
                DataJpaRepositoriesAutoConfiguration::class.java,
                TransactionAutoConfiguration::class.java,
                JacksonAutoConfiguration::class.java,
                KiwiAuditLogPackagesAutoConfiguration::class.java,
                KiwiAuditLogAutoConfiguration::class.java,
            )
        )
        .withBean(TransactionService::class.java, { TransactionService.DoNothing })
        .withPropertyValues(
            "spring.datasource.url=jdbc:h2:mem:audit-log-autoconfiguration-test",
            "spring.jpa.hibernate.ddl-auto=create-drop",
        )

    @Test
    fun registersEntityAndRepositoryWithDefaultPackages() {
        contextRunner.withUserConfiguration(DefaultPackagesApplication::class.java).run { context ->
            assertThat(context).hasNotFailed()
            assertManagedTypes(context.getBean(EntityManagerFactory::class.java))
            assertThat(context).hasSingleBean(AuditLogRepository::class.java)
            assertThat(context).hasSingleBean(OtherEntityRepository::class.java)
            assertThat(context.getBean(AuditLoggerService::class.java)).isInstanceOf(KiwiAuditLoggerService::class.java)
        }
    }

    @Test
    fun registersEntityAndRepositoryWithExplicitPackages() {
        contextRunner.withUserConfiguration(ExplicitPackagesApplication::class.java).run { context ->
            assertThat(context).hasNotFailed()
            assertManagedTypes(context.getBean(EntityManagerFactory::class.java))
            assertThat(context).hasSingleBean(AuditLogRepository::class.java)
            assertThat(context).hasSingleBean(OtherEntityRepository::class.java)
        }
    }

    @Test
    fun disabledAuditLogRegistersNothing() {
        contextRunner
            .withUserConfiguration(DefaultPackagesApplication::class.java)
            .withPropertyValues("kiwi.audit-log.enabled=false")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(AuditLogRepository::class.java)
                assertThat(context).doesNotHaveBean(AuditLoggerService::class.java)
                val managedTypes = context.getBean(EntityManagerFactory::class.java).metamodel.entities.map { it.javaType }
                assertThat(managedTypes).doesNotContain(AuditLogEntry::class.java)
            }
    }

    private fun assertManagedTypes(entityManagerFactory: EntityManagerFactory) {
        val managedTypes = entityManagerFactory.metamodel.entities.map { it.javaType }
        assertThat(managedTypes).contains(AuditLogEntry::class.java, OtherEntity::class.java)
    }
}
