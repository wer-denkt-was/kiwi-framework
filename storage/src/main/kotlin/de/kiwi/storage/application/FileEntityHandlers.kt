package de.kiwi.storage.application

import de.kiwi.api.application.event.EventSubscriber
import de.kiwi.api.application.service.FileStorageService
import de.kiwi.api.domain.DomainResult
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.storage.domain.FileDeleted

class WhenFileDeletedThenRemoveFromStorage(
    val enabled: Boolean,
    val fileStorageService: FileStorageService,
) : EventSubscriber<FileDeleted> {
    override fun handle(event: FileDeleted, user: UserDescriptor) = DomainResult { _ ->
        if (enabled) {
            val fileUri = event.file.storageUri
            fileStorageService.deleteFileData(fileUri)
        }
    }
}
