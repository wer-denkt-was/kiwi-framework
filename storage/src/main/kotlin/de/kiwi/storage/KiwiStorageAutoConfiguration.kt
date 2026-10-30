package de.kiwi.storage

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.smithy.kotlin.runtime.net.url.Url
import de.kiwi.api.application.GlobalSingletonBean
import de.kiwi.api.application.service.ClockProvider
import de.kiwi.api.application.service.FileStorageService
import de.kiwi.storage.KiwiStorageProperties.S3Properties.ObjectUriType
import de.kiwi.storage.KiwiStorageProperties.StorageProvider
import de.kiwi.storage.application.FileEntityRepository
import de.kiwi.storage.application.FileEntityService
import de.kiwi.storage.application.WhenFileDeletedThenRemoveFromStorage
import de.kiwi.storage.domain.FileEntity
import de.kiwi.storage.domain.SignedFileUriProvider
import de.kiwi.storage.infrastructure.DataUriFileStorageService
import de.kiwi.storage.infrastructure.NullFileStorageService
import de.kiwi.storage.infrastructure.s3.clientEndpointUrl
import de.kiwi.storage.infrastructure.local.HmacLocalFileTokenService
import de.kiwi.storage.infrastructure.local.LocalFileStorageService
import de.kiwi.storage.infrastructure.local.LocalFileTokenService
import de.kiwi.storage.infrastructure.s3.S3FileStorageService
import de.kiwi.storage.web.LocalFileController
import jakarta.servlet.ServletContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import java.time.Clock
import java.util.*

/**
 * Autoconfiguration of the Kiwi file storage, providing the [FileEntity] and a [FileStorageService]
 * according to the configured `kiwi.storage.provider`, which is required unless the application declares its own
 * [FileStorageService] bean (see [ConditionalOnStorageProvider]).
 * The storage entity and repository packages are registered by [KiwiStoragePackagesAutoConfiguration].
 * Every bean can be replaced by declaring a bean of the same type.
 */
@AutoConfiguration(
    afterName = ["de.kiwi.framework.KiwiFrameworkAutoConfiguration"],
    after = [HibernateJpaAutoConfiguration::class, DataJpaRepositoriesAutoConfiguration::class],
)
@EnableConfigurationProperties(KiwiStorageProperties::class)
class KiwiStorageAutoConfiguration(
    private val props: KiwiStorageProperties,
) {
    /**
     * Registers the [FileEntityRepository], unless it has already been registered, either by Spring Boot
     * using the autoconfiguration packages, or by the application's own `@EnableJpaRepositories`.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(FileEntityRepository::class)
    @EnableJpaRepositories(basePackageClasses = [FileEntityRepository::class])
    internal class FileEntityRepositoryConfiguration

    /**
     * Local file storage, storing files in the local file system and serving them using the [LocalFileController].
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnStorageProvider(StorageProvider.Local)
    internal class LocalFileStorageConfiguration(private val props: KiwiStorageProperties) {

        @Bean
        @ConditionalOnMissingBean(FileStorageService::class)
        fun localFileStorageService(
            tokenService: LocalFileTokenService,
            servletContextProvider: ObjectProvider<ServletContext>,
        ) = LocalFileStorageService(
            tokenService = tokenService,
            props = props.local,
            servletContext = servletContextProvider.ifAvailable,
        )

        @Bean
        @ConditionalOnMissingBean
        fun localFileTokenService(clockProviderProvider: ObjectProvider<ClockProvider>): LocalFileTokenService {
            val secretKey = props.local.token.secretKey
            check(secretKey.isNotEmpty()) { "Missing kiwi.storage.local.token.secret-key" }

            val clockProvider = clockProviderProvider.getIfAvailable { ClockProvider { Clock.systemUTC() } }
            return HmacLocalFileTokenService(
                secretKey = Base64.getDecoder().decode(secretKey),
                clockProvider = clockProvider,
            )
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
        fun localFileController(localFileStorageService: LocalFileStorageService) =
            LocalFileController(localFileStorageService)
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnStorageProvider(StorageProvider.S3)
    internal class S3FileStorageConfiguration(private val props: KiwiStorageProperties) {
        @Bean
        @ConditionalOnMissingBean
        fun s3FileStorageService(context: ApplicationContext): FileStorageService {
            val client = S3Client {
                endpointUrl = props.s3.clientEndpointUrl()?.let { Url.parse(it) }
                forcePathStyle = props.s3.objectUriType == ObjectUriType.PathPrefix
                region = requireProp(props.s3.region, propName = "s3.region")
                clientName = context.applicationName
                credentialsProvider = StaticCredentialsProvider {
                    accessKeyId = requireProp(props.s3.accessKey, propName = "s3.access-key")
                    secretAccessKey = requireProp(props.s3.secretKey, propName = "s3.secret-key")
                }
            }
            return S3FileStorageService(client = client, properties = props.s3)
        }

        private fun requireProp(value: String?, propName: String): String {
            return value
                ?.takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException("Missing kiwi.storage.$propName")
        }
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnStorageProvider(StorageProvider.DataUri)
    fun dataUriFileStorageService(): FileStorageService = DataUriFileStorageService

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnStorageProvider(StorageProvider.None)
    fun nullFileStorageService(): FileStorageService = NullFileStorageService

    @Bean
    @ConditionalOnMissingBean
    fun signedFileUriProvider(service: FileStorageService): SignedFileUriProvider {
        return SignedFileUriProvider { uri, duration -> service.signFileUri(uri, duration).signedUri }
    }

    @Bean
    fun signedFileUriProviderGlobalSingletonBean(provider: ObjectProvider<SignedFileUriProvider>) =
        GlobalSingletonBean(SignedFileUriProvider.Global, provider)

    @Bean
    @ConditionalOnBean(FileEntityRepository::class)
    @ConditionalOnMissingBean
    fun fileEntityService(
        fileRepository: FileEntityRepository,
        storageService: FileStorageService,
        clockProvider: ClockProvider,
    ) = FileEntityService(fileRepository, storageService, clockProvider)

    @Bean
    @ConditionalOnBean(FileStorageService::class)
    fun whenFileDeletedThenRemoveFromStorage(fileStorageService: FileStorageService) =
        WhenFileDeletedThenRemoveFromStorage(
            enabled = props.entity.onDeleteRemoveFromStorage,
            fileStorageService = fileStorageService,
        )
}
