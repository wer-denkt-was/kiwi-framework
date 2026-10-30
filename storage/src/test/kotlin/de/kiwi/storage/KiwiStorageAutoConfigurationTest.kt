package de.kiwi.storage

import de.kiwi.api.application.service.ClockProvider
import de.kiwi.api.application.service.FileStorageService
import de.kiwi.storage.application.FileEntityRepository
import de.kiwi.storage.application.FileEntityService
import de.kiwi.storage.domain.FileEntity
import de.kiwi.storage.infrastructure.DataUriFileStorageService
import de.kiwi.storage.infrastructure.NullFileStorageService
import de.kiwi.storage.infrastructure.local.LocalFileStorageService
import de.kiwi.storage.infrastructure.local.LocalFileTokenService
import de.kiwi.testapp.OtherEntity
import de.kiwi.testapp.OtherEntityRepository
import de.kiwi.storage.web.LocalFileController
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.autoconfigure.AutoConfigurationPackage
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import java.nio.file.Path

class KiwiStorageAutoConfigurationTest {

    @TempDir
    lateinit var storageDirectory: Path

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

    /** Application declaring its own file storage */
    @Configuration(proxyBeanMethods = false)
    class OwnFileStorageConfiguration {
        @Bean
        fun ownFileStorageService(): FileStorageService = DataUriFileStorageService
    }

    private val autoConfigurations = AutoConfigurations.of(
        DataSourceAutoConfiguration::class.java,
        HibernateJpaAutoConfiguration::class.java,
        DataJpaRepositoriesAutoConfiguration::class.java,
        TransactionAutoConfiguration::class.java,
        KiwiStoragePackagesAutoConfiguration::class.java,
        KiwiStorageAutoConfiguration::class.java,
    )

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(autoConfigurations)
        .withBean(ClockProvider::class.java, { ClockProvider.SystemUTC })
        .withPropertyValues(
            "spring.datasource.url=jdbc:h2:mem:storage-autoconfiguration-test",
            "spring.jpa.hibernate.ddl-auto=create-drop",
        )

    @Test
    fun registersEntityAndRepositoryWithDefaultPackages() {
        contextRunner
            .withUserConfiguration(DefaultPackagesApplication::class.java)
            .withPropertyValues("kiwi.storage.provider=none")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertManagedTypes(context.getBean(EntityManagerFactory::class.java), FileEntity::class.java, OtherEntity::class.java)
                assertThat(context).hasSingleBean(FileEntityRepository::class.java)
                assertThat(context).hasSingleBean(OtherEntityRepository::class.java)
            }
    }

    @Test
    fun registersEntityAndRepositoryWithExplicitPackages() {
        contextRunner
            .withUserConfiguration(ExplicitPackagesApplication::class.java)
            .withPropertyValues("kiwi.storage.provider=none")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertManagedTypes(context.getBean(EntityManagerFactory::class.java), FileEntity::class.java, OtherEntity::class.java)
                assertThat(context).hasSingleBean(FileEntityRepository::class.java)
                assertThat(context).hasSingleBean(OtherEntityRepository::class.java)
            }
    }

    @Test
    fun missingStorageProviderFailsStartup() {
        contextRunner.withUserConfiguration(DefaultPackagesApplication::class.java).run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).rootCause()
                .isInstanceOf(InvalidConfigurationPropertyValueException::class.java)
                .hasMessageContaining("kiwi.storage.provider")
        }
    }

    @Test
    fun invalidStorageProviderFailsStartup() {
        contextRunner
            .withUserConfiguration(DefaultPackagesApplication::class.java)
            .withPropertyValues("kiwi.storage.provider=ftp")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasStackTraceContaining("kiwi.storage.provider")
            }
    }

    @Test
    fun ownFileStorageServiceDoesNotRequireStorageProvider() {
        contextRunner
            .withUserConfiguration(DefaultPackagesApplication::class.java, OwnFileStorageConfiguration::class.java)
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(FileStorageService::class.java)
                assertThat(context.getBean(FileStorageService::class.java)).isSameAs(DataUriFileStorageService)
                assertThat(context).hasSingleBean(FileEntityService::class.java)
            }
    }

    @Test
    fun ownFileStorageServiceReplacesStorageProvider() {
        contextRunner
            .withUserConfiguration(DefaultPackagesApplication::class.java)
            .withBean(FileStorageService::class.java, { DataUriFileStorageService })
            .withPropertyValues("kiwi.storage.provider=none")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(FileStorageService::class.java)
                assertThat(context.getBean(FileStorageService::class.java)).isSameAs(DataUriFileStorageService)
            }
    }

    @Test
    fun noneStorageProviderDiscardsFiles() {
        contextRunner
            .withUserConfiguration(DefaultPackagesApplication::class.java)
            .withPropertyValues("kiwi.storage.provider=none")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(FileStorageService::class.java)).isSameAs(NullFileStorageService)
            }
    }

    @Test
    fun selectsStorageProviderWithRelaxedBinding() {
        contextRunner
            .withUserConfiguration(DefaultPackagesApplication::class.java)
            .withPropertyValues("kiwi.storage.provider=DataUri")
            .run { context ->
                assertThat(context.getBean(FileStorageService::class.java)).isSameAs(DataUriFileStorageService)
            }
    }

    @Test
    fun localProviderRequiresSecretKey() {
        contextRunner
            .withUserConfiguration(DefaultPackagesApplication::class.java)
            .withPropertyValues("kiwi.storage.provider=local", "kiwi.storage.local.parent-directory=$storageDirectory")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).rootCause().hasMessageContaining("kiwi.storage.local.token.secret-key")
            }
    }

    @Test
    fun localProviderRegistersController() {
        WebApplicationContextRunner()
            .withConfiguration(autoConfigurations)
            .withBean(ClockProvider::class.java, { ClockProvider.SystemUTC })
            .withUserConfiguration(DefaultPackagesApplication::class.java)
            .withPropertyValues(
                "spring.datasource.url=jdbc:h2:mem:storage-autoconfiguration-web-test",
                "kiwi.storage.provider=local",
                "kiwi.storage.local.parent-directory=$storageDirectory",
                "kiwi.storage.local.token.secret-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(LocalFileStorageService::class.java)
                assertThat(context).hasSingleBean(LocalFileTokenService::class.java)
                assertThat(context).hasSingleBean(LocalFileController::class.java)
            }
    }

    @Test
    fun otherProviderDoesNotRegisterController() {
        WebApplicationContextRunner()
            .withConfiguration(autoConfigurations)
            .withBean(ClockProvider::class.java, { ClockProvider.SystemUTC })
            .withUserConfiguration(DefaultPackagesApplication::class.java)
            .withPropertyValues(
                "spring.datasource.url=jdbc:h2:mem:storage-autoconfiguration-web-test-2",
                "kiwi.storage.provider=data-uri",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(LocalFileController::class.java)
                assertThat(context).doesNotHaveBean(LocalFileTokenService::class.java)
            }
    }

    private fun assertManagedTypes(entityManagerFactory: EntityManagerFactory, vararg entityTypes: Class<*>) {
        val managedTypes = entityManagerFactory.metamodel.entities.map { it.javaType }
        assertThat(managedTypes).contains(*entityTypes)
    }
}
