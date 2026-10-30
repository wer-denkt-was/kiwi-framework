package de.kiwi.auditlog.infrastructure

import de.kiwi.api.application.service.TransactionService
import de.kiwi.api.domain.Auditable
import de.kiwi.api.domain.DomainEvent
import de.kiwi.api.domain.DomainException
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.auditlog.application.AuditLogRepository
import de.kiwi.auditlog.domain.AuditLogEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock

/**
 * Domain events are written within the current transaction, errors in a new transaction (A-18).
 */
class KiwiAuditLoggerServiceTransactionTest {
    private val transactions = mutableListOf<String>()

    private val transactionService = object : TransactionService {
        override fun <T> read(action: (TransactionService.TxContext) -> T): T = error("unexpected read")

        override fun <T> write(action: (TransactionService.TxContext) -> T): T {
            transactions.add("write")
            return TransactionService.DoNothing.write(action)
        }

        override fun <T> writeNew(action: (TransactionService.TxContext) -> T): T {
            transactions.add("writeNew")
            return TransactionService.DoNothing.write(action)
        }
    }

    private val mockRepository = mock<AuditLogRepository> {
        on { save(any<AuditLogEntry>()) } doAnswer { it.getArgument<AuditLogEntry>(0) }
    }

    private val auditLoggerService = KiwiAuditLoggerService(
        auditLogRepository = mockRepository,
        detailsSerializer = { "{}" },
        transactionService = transactionService,
        includeSeverity = setOf(Auditable.Severity.Event, Auditable.Severity.Error, Auditable.Severity.Unknown),
    )

    @Test
    fun eventsWithinTransactionErrorsInNewTransaction() {
        auditLoggerService.auditLog(TestEvent(), UserDescriptor.SYSTEM)
        auditLoggerService.auditLog(TestError(), UserDescriptor.SYSTEM)
        auditLoggerService.auditLog(DomainException.Unknown(IllegalStateException()), UserDescriptor.SYSTEM)

        assertEquals(listOf("write", "writeNew", "writeNew"), transactions)
    }

    private class TestEvent : DomainEvent() {
        override val details = Unit
    }

    private class TestError : DomainException(status = Status.BadRequest, code = "test-error")
}
