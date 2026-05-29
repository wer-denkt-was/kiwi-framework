package de.kiwi.storage

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.smithy.kotlin.runtime.net.url.Url
import de.kiwi.api.application.service.AuthenticationTokenService
import de.kiwi.api.application.service.FileStorageService
import de.kiwi.storage.KiwiStorageProperties.S3Properties.ObjectUriType
import de.kiwi.storage.application.WhenFileDeletedThenRemoveFromStorage
import de.kiwi.storage.infrastructure.DataUriFileStorageService
import de.kiwi.storage.infrastructure.NullFileStorageService
import de.kiwi.storage.infrastructure.local.LocalFileStorageService
import de.kiwi.storage.infrastructure.s3.S3FileStorageService
import jakarta.servlet.ServletContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@Configuration
@EnableConfigurationProperties(KiwiStorageProperties::class)
@ComponentScan("de.kiwi.storage")
@EntityScan("de.kiwi.storage.domain")
@EnableJpaRepositories("de.kiwi.storage.application")
@Import(KiwiStorageSignedFileUriTypeConfiguration::class)
class KiwiStorageConfiguration(
    private val props: KiwiStorageProperties,
    private val context: ApplicationContext,
    private val tokenServiceProvider: ObjectProvider<AuthenticationTokenService>,
    private val servletContextProvider: ObjectProvider<ServletContext>,
) {
    @Bean
    @ConditionalOnMissingBean(FileStorageService::class)
    fun fileStorageService(): FileStorageService {
        return when (props.provider) {
            KiwiStorageProperties.StorageProvider.None -> NullFileStorageService
            KiwiStorageProperties.StorageProvider.DataUri -> DataUriFileStorageService
            KiwiStorageProperties.StorageProvider.S3 -> createS3Storage()
            KiwiStorageProperties.StorageProvider.Local -> createLocalFileStorage()
        }
    }

    private fun createS3Storage(): FileStorageService {
        val client = S3Client {
            endpointUrl = Url.parse("${props.s3.scheme}://${props.s3.hostname}")
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

    private fun createLocalFileStorage(): FileStorageService {
        val jwtService = tokenServiceProvider.ifAvailable
            ?: throw Exception("LocalFileStorageService requires AuthenticationTokenService provider")

        return LocalFileStorageService(
            tokenService = jwtService,
            props = props.local,
            servletContext = servletContextProvider.ifAvailable,
        )
    }

    @Bean
    fun whenFileDeletedThenRemoveFromStorage(fileStorageService: FileStorageService) =
        WhenFileDeletedThenRemoveFromStorage(
            enabled = props.entity.onDeleteRemoveFromStorage,
            fileStorageService = fileStorageService,
        )
}
