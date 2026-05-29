package de.kiwi.auditLog.application

import de.kiwi.auditLog.domain.AuditLogEntry
import de.kiwi.auditLog.domain.AuditLogEntryId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface AuditLogRepository : JpaRepository<AuditLogEntry, AuditLogEntryId>
