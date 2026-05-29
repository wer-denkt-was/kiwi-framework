package de.kiwi.storage.application

import de.kiwi.api.application.service.ClockProvider
import de.kiwi.api.application.service.FileStorageService
import de.kiwi.api.domain.DomainResult
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.storage.domain.FileDeleted
import de.kiwi.storage.domain.FileEntity
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service

@Service
class FileEntityService(
    private val fileRepository: FileEntityRepository,
    private val storageService: FileStorageService,
    private val clockProvider: ClockProvider,
) {
    fun createFileEntity(
        filename: String,
        contentType: String,
        fileResource: Resource,
        user: UserDescriptor,
        siblingFile: FileEntity? = null,
        publicAccess: Boolean = false,
    ) = DomainResult { record ->
        val clock = clockProvider.clock()

        check(siblingFile == null || siblingFile.filename != filename) { "Sibling filename conflict" }

        val (fileUri, fileSize) = storageService.uploadFileData(
            FileStorageService.FileUpload(
                filename = filename,
                contentType = contentType,
                resource = fileResource,
                siblingFileUri = siblingFile?.storageUri,
                publicAccess = publicAccess,
            )
        )

        fileRepository.save(
            record(
                FileEntity.create(
                    filename = filename,
                    fileSize = fileSize,
                    fileUri = fileUri,
                    contentType = contentType,
                    uploader = user,
                    clock = clock,
                )
            )
        )
    }

    fun deleteFileEntity(
        fileEntity: FileEntity,
    ) = DomainResult { record ->
        record(FileDeleted(fileEntity))
        fileRepository.delete(fileEntity)
    }
}
