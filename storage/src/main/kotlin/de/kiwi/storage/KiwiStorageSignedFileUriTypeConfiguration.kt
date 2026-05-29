package de.kiwi.storage

import de.kiwi.api.application.service.FileStorageService
import de.kiwi.api.util.slf4j
import de.kiwi.storage.domain.SignedFileUriProvider
import de.kiwi.storage.domain.SignedFileUriType
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Configuration

private val log = slf4j<KiwiStorageSignedFileUriTypeConfiguration>()

@Configuration
class KiwiStorageSignedFileUriTypeConfiguration {

    @Autowired
    @ConditionalOnBean(FileStorageService::class)
    fun registerSignedFileUriProvider(service: FileStorageService) {
        log.info("Registered ${SignedFileUriProvider::class.java.name} implementation using FileStorageService: $service")
        SignedFileUriType.provider = SignedFileUriProvider { fileUri, duration ->
            service.signFileUri(fileUri, duration).signedUri
        }
    }
}
