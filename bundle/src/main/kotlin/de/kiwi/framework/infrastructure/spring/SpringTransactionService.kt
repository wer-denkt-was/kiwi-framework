package de.kiwi.framework.infrastructure.spring

import de.kiwi.api.application.service.TransactionService
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionTemplate

/**
 * [TransactionService] implementation using Spring's [TransactionTemplate]
 * with an underlying [PlatformTransactionManager].
 */
class SpringTransactionService(
    private val readTemplate: TransactionTemplate,
    private val writeTemplate: TransactionTemplate,
) : TransactionService {
    constructor(manager: PlatformTransactionManager) : this(
        readTemplate = manager.createTemplate(true),
        writeTemplate = manager.createTemplate(false),
    )

    @Suppress("UNCHECKED_CAST")
    override fun <T> write(action: (TransactionService.TxContext) -> T): T =
        writeTemplate.execute { action(SpringTxContext(it)) } as T

    @Suppress("UNCHECKED_CAST")
    override fun <T> read(action: (TransactionService.TxContext) -> T): T =
        readTemplate.execute { action(SpringTxContext(it)) } as T
}

private class SpringTxContext(private val status: TransactionStatus) : TransactionService.TxContext {
    override val isNew: Boolean get() = status.isNewTransaction
    override val isDone: Boolean get() = status.isCompleted
    override val isReadOnly: Boolean get() = status.isReadOnly
    override fun flush() = status.flush()
}

private fun PlatformTransactionManager.createTemplate(readOnly: Boolean) =
    TransactionTemplate(this).apply { isReadOnly = readOnly }
