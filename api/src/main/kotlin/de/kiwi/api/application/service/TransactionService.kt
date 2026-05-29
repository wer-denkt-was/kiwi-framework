package de.kiwi.api.application.service

/**
 * Service for executing functions in a transactional context.
 */
interface TransactionService {
    /**
     * Execute the given [action] within a **read-only** transaction context.
     */
    fun <T> read(action: (TxContext) -> T): T

    /**
     * Execute the given [action] within a transaction context.
     */
    fun <T> write(action: (TxContext) -> T): T

    interface TxContext {
        val isNew: Boolean
        val isDone: Boolean
        val isReadOnly: Boolean
        fun flush()
    }

    object DoNothing : TransactionService {
        override fun <T> read(action: (TxContext) -> T): T = action(NoContext)
        override fun <T> write(action: (TxContext) -> T): T = action(NoContext)

        private object NoContext : TxContext {
            override val isNew = false
            override val isDone = false
            override val isReadOnly = false
            override fun flush() = Unit
        }
    }
}
