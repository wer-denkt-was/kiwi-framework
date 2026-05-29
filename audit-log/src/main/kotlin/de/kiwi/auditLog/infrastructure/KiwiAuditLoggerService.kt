package de.kiwi.auditLog.infrastructure

import de.kiwi.api.application.service.AuditLoggerService
import de.kiwi.api.application.service.TransactionService
import de.kiwi.api.domain.Auditable
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.auditLog.application.AuditLogRepository
import de.kiwi.auditLog.application.AuditableDetailsSerializer
import de.kiwi.auditLog.domain.AuditLogEntry

class KiwiAuditLoggerService(
    private val auditLogRepository: AuditLogRepository,
    private val detailsSerializer: AuditableDetailsSerializer,
    private val transactionService: TransactionService,
    private val includeSeverity: Set<Auditable.Severity>,
) : AuditLoggerService {

    override fun auditLog(event: Auditable, user: UserDescriptor) {
        if (includeSeverity.contains(event.severity)) {
            val details = event.details ?: Unit
            val serializedDetails = detailsSerializer.serialize(details)

            transactionService.write {
                val entry = AuditLogEntry.create(event, user, serializedDetails)
                auditLogRepository.save(entry)
            }
        }
    }
}
