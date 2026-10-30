package de.kiwi.auditlog.infrastructure

import de.kiwi.api.application.service.AuditLoggerService
import de.kiwi.api.application.service.TransactionService
import de.kiwi.api.domain.Auditable
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.auditlog.application.AuditLogRepository
import de.kiwi.auditlog.application.AuditableDetailsSerializer
import de.kiwi.auditlog.domain.AuditLogEntry

/**
 * [AuditLoggerService] persisting [AuditLogEntry]s using the [AuditLogRepository].
 * Domain events ([Auditable.Severity.Event]) are written within the current transaction, so they are only persisted
 * if the transaction of the command commits. Errors are written in a new, separate transaction
 * ([TransactionService.writeNew]), so they are persisted although the transaction of the failed command is rolled back.
 */
class KiwiAuditLoggerService(
    private val auditLogRepository: AuditLogRepository,
    private val detailsSerializer: AuditableDetailsSerializer,
    private val transactionService: TransactionService,
    private val includeSeverity: Set<Auditable.Severity>,
) : AuditLoggerService {

    override fun auditLog(event: Auditable, user: UserDescriptor) {
        if (event.severity !in includeSeverity) return // skip event

        val details = event.details ?: Unit
        val detailsJson = detailsSerializer.serializeJson(details)

        if (event.severity == Auditable.Severity.Event) {
            transactionService.write { saveEntry(event, detailsJson, user) }
        } else {
            transactionService.writeNew { saveEntry(event, detailsJson, user) }
        }
    }

    private fun saveEntry(event: Auditable, detailsJson: String, user: UserDescriptor): AuditLogEntry {
        val entry = AuditLogEntry.create(event = event, user = user, detailsJson = detailsJson)
        return auditLogRepository.save(entry)
    }
}
