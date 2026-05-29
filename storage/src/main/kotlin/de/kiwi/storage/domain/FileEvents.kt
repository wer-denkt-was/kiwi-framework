package de.kiwi.storage.domain

import de.kiwi.api.domain.DetailsProvider
import de.kiwi.api.domain.DomainEvent
import de.kiwi.api.domain.SubjectDomainEvent

data class FileCreated(val file: FileEntity) : SubjectDomainEvent<FileEntity>(file) {
    override val detailsProvider = FileFieldsets.default
    val id = file.id
}

data class FileDeleted(val file: FileEntity) : SubjectDomainEvent<FileEntity>(file) {
    override val detailsProvider = FileFieldsets.default
    val id = file.id
    override val details = FileFieldsets.default.extract(file)
}
