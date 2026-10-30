package de.kiwi.auditlog.application

import de.kiwi.auditlog.domain.AuditLogEntry
import de.kiwi.auditlog.domain.AuditLogEntryId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface AuditLogRepository : JpaRepository<AuditLogEntry, AuditLogEntryId>
