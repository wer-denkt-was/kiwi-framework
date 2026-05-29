package de.kiwi.storage.application;

import de.kiwi.storage.domain.FileEntity
import de.kiwi.storage.domain.FileId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.stereotype.Repository

@Repository
interface FileEntityRepository : JpaRepository<FileEntity, FileId>, JpaSpecificationExecutor<FileEntity>
