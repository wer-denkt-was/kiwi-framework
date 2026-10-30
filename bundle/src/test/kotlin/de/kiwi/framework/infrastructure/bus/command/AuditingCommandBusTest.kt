package de.kiwi.framework.infrastructure.bus.command

import de.kiwi.api.application.command.CommandBus
import de.kiwi.api.application.command.CommandHandler
import de.kiwi.api.application.service.AuditLoggerService
import de.kiwi.api.application.service.TransactionService
import de.kiwi.api.domain.*
import de.kiwi.framework.infrastructure.bus.KiwiBusAutoConfiguration
import de.kiwi.framework.testUserDescriptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * Tests the order of auditing and transactions of the [CommandBus] configured by [KiwiBusAutoConfiguration]:
 * Domain events are audit-logged within the transaction of the command, errors after its rollback (A-18).
 */
class AuditingCommandBusTest {
    private val transactionService = RecordingTransactionService()
    private val auditLoggerService = RecordingAuditLoggerService(transactionService)

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(KiwiBusAutoConfiguration::class.java))
        .withBean(TransactionService::class.java, { transactionService })
        .withBean(AuditLoggerService::class.java, { auditLoggerService })
        .withBean(SucceedingCommandHandler::class.java)
        .withBean(FailingCommandHandler::class.java)

    private val user = testUserDescriptor(username = "test")

    @Test
    fun auditEventsWithinTransaction() {
        contextRunner.run { context ->
            val commandBus = context.getBean<CommandBus>()
            commandBus.submit(SucceedingCommand(), user)

            assertEquals(listOf(AuditedEntry("Succeeded", transactionDepth = 1)), auditLoggerService.entries)
            assertEquals(listOf(RecordingTransactionService.Outcome.Committed), transactionService.outcomes)
        }
    }

    @Test
    fun auditErrorsAfterRollback() {
        contextRunner.run { context ->
            val commandBus = context.getBean<CommandBus>()
            assertThrows<CommandFailed> { commandBus.submit(FailingCommand(), user) }

            // The error is audit-logged outside the rolled back transaction of the command
            assertEquals(listOf(AuditedEntry("command-failed", transactionDepth = 0)), auditLoggerService.entries)
            assertEquals(listOf(RecordingTransactionService.Outcome.RolledBack), transactionService.outcomes)
        }
    }
}

private data class AuditedEntry(val name: String, val transactionDepth: Int)

private class RecordingAuditLoggerService(private val transactions: RecordingTransactionService) : AuditLoggerService {
    val entries = mutableListOf<AuditedEntry>()

    override fun auditLog(event: Auditable, user: UserDescriptor) {
        val name = if (event is DomainException) event.code else event.javaClass.simpleName
        entries.add(AuditedEntry(name, transactions.depth))
    }
}

private class RecordingTransactionService : TransactionService {
    var depth = 0
    val outcomes = mutableListOf<Outcome>()

    enum class Outcome { Committed, RolledBack }

    override fun <T> read(action: (TransactionService.TxContext) -> T) = write(action)

    override fun <T> write(action: (TransactionService.TxContext) -> T): T {
        try {
            depth++
            val result = action(TestTxContext)
            outcomes.add(Outcome.Committed)
            return result
        } catch (ex: Exception) {
            outcomes.add(Outcome.RolledBack)
            throw ex
        } finally {
            depth--
        }
    }
}

private object TestTxContext : TransactionService.TxContext {
    override val isNew = true
    override val isDone = false
    override val isReadOnly = false
    override fun flush() = Unit
}

private class SucceedingCommand : Command<Unit>()
private class FailingCommand : Command<Unit>()

private class Succeeded : DomainEvent() {
    override val details = Unit
}

private class CommandFailed : DomainException(status = Status.InternalError, code = "command-failed")

private class SucceedingCommandHandler : CommandHandler<SucceedingCommand, Unit> {
    override fun handle(command: SucceedingCommand, user: UserDescriptor) = DomainResult.events(Succeeded())
}

private class FailingCommandHandler : CommandHandler<FailingCommand, Unit> {
    override fun handle(command: FailingCommand, user: UserDescriptor): DomainResult<Unit> = throw CommandFailed()
}
