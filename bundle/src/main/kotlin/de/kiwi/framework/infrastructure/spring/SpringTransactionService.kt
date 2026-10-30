package de.kiwi.framework.infrastructure.spring

import de.kiwi.api.application.service.TransactionService
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.support.TransactionTemplate

/**
 * [TransactionService] implementation using Spring's [TransactionTemplate]
 * with an underlying [PlatformTransactionManager].
 */
class SpringTransactionService(
    private val readTemplate: TransactionTemplate,
    private val writeTemplate: TransactionTemplate,
    private val writeNewTemplate: TransactionTemplate,
) : TransactionService {
    constructor(
        manager: PlatformTransactionManager,
        readIsolation: Isolation = Isolation.DEFAULT,
        writeIsolation: Isolation = Isolation.DEFAULT,
    ) : this(
        readTemplate = TransactionTemplate(manager).apply {
            isReadOnly = true
            isolationLevel = readIsolation.value()
            afterPropertiesSet()
        },
        writeTemplate = TransactionTemplate(manager).apply {
            isReadOnly = false
            isolationLevel = writeIsolation.value()
            afterPropertiesSet()
        },
        writeNewTemplate = TransactionTemplate(manager).apply {
            isReadOnly = false
            isolationLevel = writeIsolation.value()
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            afterPropertiesSet()
        },
    )

    override fun <T> write(action: (TransactionService.TxContext) -> T): T =
        writeTemplate.execute { action(SpringTxContext(it)) }

    override fun <T> writeNew(action: (TransactionService.TxContext) -> T): T =
        writeNewTemplate.execute { action(SpringTxContext(it)) }

    override fun <T> read(action: (TransactionService.TxContext) -> T): T =
        readTemplate.execute { action(SpringTxContext(it)) }
}

private class SpringTxContext(private val status: TransactionStatus) : TransactionService.TxContext {
    override val isNew: Boolean get() = status.isNewTransaction
    override val isDone: Boolean get() = status.isCompleted
    override val isReadOnly: Boolean get() = status.isReadOnly
    override fun flush() = status.flush()
}
